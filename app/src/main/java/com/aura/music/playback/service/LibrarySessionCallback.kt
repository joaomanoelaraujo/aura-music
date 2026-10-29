package com.aura.music.playback.service

import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.aura.music.data.local.PlaybackStateStore
import com.aura.music.data.local.SettingsRepository
import com.aura.music.data.media.LibraryIndex
import com.aura.music.data.repository.LibraryRepository
import com.aura.music.playback.queue.MediaItemMapper
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.io.File
import kotlinx.coroutines.guava.future

/**
 * Resolves media IDs into playable items and exposes a simple browse tree
 * (used by Bluetooth head units, Android Auto and the system media controls).
 */
@UnstableApi
class LibrarySessionCallback(
    private val library: LibraryRepository,
    private val stateStore: PlaybackStateStore,
    private val settings: SettingsRepository,
    private val buttons: SessionButtons,
    private val scope: CoroutineScope,
    private val cacheDir: File,
) : MediaLibrarySession.Callback {

    /** Grants every controller (System UI, Bluetooth, the app) the extra buttons and their commands. */
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
        MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(buttons.sessionCommands)
            .setMediaButtonPreferences(buttons.buttonsFor(session.player))
            .build()

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> =
        buttons.handle(customCommand, session.player)
            ?: Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        val track = mediaId.toLongOrNull()?.let { library.awaitIndex()?.track(it) }
        if (track == null) {
            LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        } else {
            LibraryResult.ofItem(MediaItemMapper.toMediaItem(track), null)
        }
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        if (parentId != ROOT_ID) return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        val tracks = library.awaitIndex()?.tracks.orEmpty()
        val pageItems = tracks.drop(page * pageSize).take(pageSize).map(MediaItemMapper::toMediaItem)
        LibraryResult.ofItemList(ImmutableList.copyOf(pageItems), params)
    }

    /** Items arriving from controllers carry only IDs; the URIs are restored here from the library. */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = scope.future {
        val index = library.awaitIndex()
        mediaItems.mapNotNull { resolve(it, index) }.toMutableList()
    }

    /**
     * Like [onAddMediaItems], but keeps the requested start item correct when some tracks no
     * longer exist: the start index is shifted by the number of items dropped before it.
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
        val index = library.awaitIndex()
        val resolved = mediaItems.map { resolve(it, index) }
        val kept = resolved.filterNotNull()
        if (kept.isEmpty()) return@future MediaItemsWithStartPosition(emptyList(), 0, 0)
        if (startIndex == C.INDEX_UNSET) return@future MediaItemsWithStartPosition(kept, C.INDEX_UNSET, startPositionMs)
        val requestedSurvived = resolved.getOrNull(startIndex) != null
        val shifted = (startIndex - resolved.take(startIndex).count { it == null }).coerceIn(0, kept.lastIndex)
        MediaItemsWithStartPosition(kept, shifted, if (requestedSurvived) startPositionMs else 0)
    }

    /**
     * Restores the last queue when a headset button or the system's media resumption UI asks for
     * playback after the app was killed. Honours the "resume last session" setting.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
        val saved = stateStore.load()?.takeIf { settings.settings.first().resumeLastSession }
        val tracks = saved?.let { library.awaitIndex()?.tracks(it.trackIds) }.orEmpty()
        if (saved == null || tracks.isEmpty()) {
            MediaItemsWithStartPosition(emptyList(), 0, 0)
        } else {
            mediaSession.player.shuffleModeEnabled = saved.shuffle
            mediaSession.player.repeatMode = saved.repeatMode
            val startIndex = saved.index.coerceIn(0, tracks.lastIndex)
            val position = PlaybackPersistence.resumePosition(tracks[startIndex].durationMs, saved.positionMs)
            MediaItemsWithStartPosition(tracks.map(MediaItemMapper::toMediaItem), startIndex, position)
        }
    }

    /**
     * Maps a requested item to a playable one. Without an index yet (very first scan still running),
     * the MediaStore URI is derived from the ID so playback never silently does nothing.
     */
    private fun resolve(item: MediaItem, index: LibraryIndex?): MediaItem? {
        MediaItemMapper.streamedFile(item, cacheDir)?.let { file ->
            return item.buildUpon().setUri(Uri.fromFile(file)).build()
        }
        val id = MediaItemMapper.trackId(item) ?: return null
        if (index == null) {
            return item.buildUpon().setUri(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)).build()
        }
        return index.track(id)?.let(MediaItemMapper::toMediaItem)
    }

    companion object {
        const val ROOT_ID = "aura.root"

        private val rootItem = MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("AURA")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()
    }
}
