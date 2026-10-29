package com.aura.music.data.local.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        FavoriteEntity::class,
        PlayEventEntity::class,
        AudioOverrideEntity::class,
        FolderRuleEntity::class,
        SearchHistoryEntity::class,
        TrackLyricsEntity::class,
        DownloadEntity::class,
        OnlineLyricsEntity::class,
        SongAnalysisEntity::class,
        MetadataOverrideEntity::class,
        CoverLookupEntity::class,
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6)],
)
abstract class AuraDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun playEventDao(): PlayEventDao
    abstract fun classificationDao(): ClassificationDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun downloadDao(): DownloadDao
    abstract fun songAnalysisDao(): SongAnalysisDao
    abstract fun metadataDao(): MetadataDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AuraDatabase =
        Room.databaseBuilder(context, AuraDatabase::class.java, "aura.db").build()

    @Provides
    fun providePlaylistDao(db: AuraDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideFavoriteDao(db: AuraDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun providePlayEventDao(db: AuraDatabase): PlayEventDao = db.playEventDao()

    @Provides
    fun provideClassificationDao(db: AuraDatabase): ClassificationDao = db.classificationDao()

    @Provides
    fun provideSearchHistoryDao(db: AuraDatabase): SearchHistoryDao = db.searchHistoryDao()

    @Provides
    fun provideLyricsDao(db: AuraDatabase): LyricsDao = db.lyricsDao()

    @Provides
    fun provideDownloadDao(db: AuraDatabase): DownloadDao = db.downloadDao()

    @Provides
    fun provideSongAnalysisDao(db: AuraDatabase): SongAnalysisDao = db.songAnalysisDao()

    @Provides
    fun provideMetadataDao(db: AuraDatabase): MetadataDao = db.metadataDao()
}
