package com.aura.music.data.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.util.LruCache
import androidx.core.net.toUri
import com.aura.music.core.model.Track
import com.aura.music.data.local.db.SongAnalysisDao
import com.aura.music.data.local.db.SongAnalysisEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What AURA knows about how each song sounds: its intensity over time (lyric effects), how
 * energetic it is (mood mixes) and how loud it plays (even volume). A song is analyzed once (a few
 * seconds of decoding, one song at a time); the detail is kept in the app's files and a summary in
 * the database, so it's instant from then on and works offline.
 */
@Singleton
class SongAnalysisRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: SongAnalysisDao,
) {
    private val dir = File(context.filesDir, "song_analysis")
    private val memory = LruCache<String, IntensityProfile>(MEMORY_ENTRIES)
    private val analysis = Mutex()

    /** The profile if it's already known, without analyzing. */
    fun cached(track: Track): IntensityProfile? = memory.get(key(track)) ?: readFile(track)?.also { memory.put(key(track), it) }

    /** The stored summary, when it belongs to the current file. */
    suspend fun summary(track: Track): SongAnalysisEntity? = dao.get(track.id)?.takeIf { it.key == key(track) }

    /** True when the song hasn't been analyzed yet (or its file changed) and hasn't failed before. */
    suspend fun needsAnalysis(track: Track): Boolean = summary(track) == null

    /** The song's profile, analyzing it first when needed; null when its audio can't be read. */
    suspend fun profile(track: Track): IntensityProfile? {
        cached(track)?.let { return it.also { remember(track, it) } }
        if (summary(track)?.failed == true) return null
        return analysis.withLock {
            cached(track) ?: withContext(Dispatchers.Default) { analyze(track) }.also { if (it == null) markFailed(track) }?.also { profile ->
                remember(track, profile)
                memory.put(key(track), profile)
                runCatching {
                    dir.mkdirs()
                    val tmp = File(dir, key(track) + ".tmp")
                    tmp.outputStream().use(profile::write)
                    tmp.renameTo(file(track))
                }.onFailure { Log.w(TAG, "Could not store lyric effects profile", it) }
            }
        }
    }

    /** Keeps the summary row in step with the profile (cheap when it's already there). */
    private suspend fun remember(track: Track, profile: IntensityProfile) {
        val key = key(track)
        if (dao.get(track.id)?.key == key) return
        dao.upsert(SongAnalysisEntity(track.id, key, profile.energy, profile.loudnessDb, System.currentTimeMillis()))
    }

    private suspend fun markFailed(track: Track) {
        dao.upsert(SongAnalysisEntity(track.id, key(track), energy = 0f, loudnessDb = 0f, analyzedAt = System.currentTimeMillis(), failed = true))
    }

    private fun key(track: Track) = "${track.id}_${track.sizeBytes}_${track.durationMs}"

    private fun file(track: Track) = File(dir, key(track) + ".fx")

    private fun readFile(track: Track): IntensityProfile? = file(track).takeIf { it.isFile }?.inputStream()?.use(IntensityProfile::read)

    /** Decodes the song to mono and feeds it to the analyzer, chunk by chunk. */
    private suspend fun analyze(track: Track): IntensityProfile? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, track.uri.toUri(), null)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var analyzer: IntensityAnalyzer? = null
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var mono = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(inIndex)!!, 0)
                        if (size < 0 || extractor.sampleTime > MAX_ANALYZED_US) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = decoder.outputFormat
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        floatPcm = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        analyzer = IntensityAnalyzer(out.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    }
                    outIndex >= 0 -> {
                        val buffer = decoder.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val target = analyzer ?: IntensityAnalyzer(format.getInteger(MediaFormat.KEY_SAMPLE_RATE)).also { analyzer = it }
                        val frames = info.size / (channels * if (floatPcm) 4 else 2)
                        if (mono.size < frames) mono = FloatArray(frames)
                        for (f in 0 until frames) {
                            var sum = 0f
                            for (c in 0 until channels) sum += if (floatPcm) buffer.getFloat() else buffer.getShort() / 32_768f
                            mono[f] = sum / channels
                        }
                        target.feed(mono, frames)
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            return analyzer?.finish()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Could not analyze ${track.id}", e)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private companion object {
        const val TAG = "LyricsFx"
        const val MEMORY_ENTRIES = 8
        const val TIMEOUT_US = 10_000L

        /** Very long files (mixes, audiobooks) are analyzed up to here. */
        const val MAX_ANALYZED_US = 20L * 60 * 1_000_000
    }
}
