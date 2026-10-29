package com.aura.music.data.analysis

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aura.music.data.local.SettingsRepository
import com.aura.music.data.repository.LibraryRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Analyzes the songs nobody played yet, so mood mixes and even volume cover the whole library.
 * Runs only while the phone charges with a healthy battery, one song at a time; stopping it loses
 * nothing, since every song analyzed is stored right away.
 */
class LibraryAnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun library(): LibraryRepository
        fun analysis(): SongAnalysisRepository
        fun settings(): SettingsRepository
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        if (!deps.settings().settings.first().analyzeLibrary) return Result.success()
        val index = deps.library().awaitIndex() ?: return Result.retry()
        val analysis = deps.analysis()
        var done = 0
        for (track in index.tracks) {
            if (isStopped) break
            if (!analysis.needsAnalysis(track)) continue
            runCatching { analysis.profile(track) }.onFailure { Log.w(TAG, "Skipped ${track.id}", it) }
            done++
        }
        Log.i(TAG, "Analyzed $done songs")
        return Result.success()
    }

    companion object {
        private const val TAG = "LibraryAnalysis"
        private const val WORK = "library-analysis"

        /** Daily, while charging; new songs get analyzed the next time the phone charges. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LibraryAnalysisWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }
    }
}
