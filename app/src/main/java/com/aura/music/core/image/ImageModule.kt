package com.aura.music.core.image

import android.content.Context
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
object ImageModule {

    @Provides
    @Singleton
    fun provideArtworkFetcherFactory(@ApplicationContext context: Context): ArtworkFetcher.Factory =
        ArtworkFetcher.Factory(context)

    /**
     * Artwork is either a MediaStore thumbnail (already cached on disk by the system) or the
     * original picture inside the audio file itself, so only an in-memory cache is configured.
     */
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        artworkFetcherFactory: ArtworkFetcher.Factory,
    ): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(artworkFetcherFactory)
            add(ArtworkFetcher.Key())
            add(RemoteCoverFetcher.Factory())
            add(RemoteCoverFetcher.Key())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, MEMORY_CACHE_PERCENT).build() }
        .diskCache(null)
        .crossfade(CROSSFADE_MS)
        // Opening audio files for their pictures is heavy: a few at a time keeps scrolling smooth.
        .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(PARALLEL_FETCHES))
        .decoderCoroutineContext(Dispatchers.Default.limitedParallelism(PARALLEL_DECODES))
        .build()

    private const val MEMORY_CACHE_PERCENT = 0.2
    private const val CROSSFADE_MS = 220
    private const val PARALLEL_FETCHES = 4
    private const val PARALLEL_DECODES = 2
}
