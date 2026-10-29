package com.aura.music

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.aura.music.core.common.ApplicationScope
import com.aura.music.core.image.ArtworkFetcher
import com.aura.music.data.analysis.LibraryAnalysisWorker
import com.aura.music.data.local.SettingsRepository
import com.aura.music.data.repository.LibraryRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@HiltAndroidApp
class AuraApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var imageLoader: ImageLoader
    @Inject lateinit var artworkFetcherFactory: ArtworkFetcher.Factory
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        // Artwork may have been added since the last scan; let previously missing albums retry.
        libraryRepository.index
            .drop(1)
            .onEach { artworkFetcherFactory.clearMissing() }
            .launchIn(applicationScope)
        // Background analysis of the library follows its setting.
        settings.settings
            .map { it.analyzeLibrary }
            .distinctUntilChanged()
            .onEach { if (it) LibraryAnalysisWorker.schedule(this) else LibraryAnalysisWorker.cancel(this) }
            .launchIn(applicationScope)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}
