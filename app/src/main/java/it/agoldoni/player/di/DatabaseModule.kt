package it.agoldoni.player.di

import android.content.Context
import androidx.room.Room
import it.agoldoni.player.data.local.PlayerDatabase
import it.agoldoni.player.data.local.dao.FtpConfigDao
import it.agoldoni.player.data.local.dao.PlaylistDao
import it.agoldoni.player.data.local.dao.TrackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun providePlayerDatabase(@ApplicationContext context: Context): PlayerDatabase =
        Room.databaseBuilder(
            context,
            PlayerDatabase::class.java,
            PlayerDatabase.DATABASE_NAME
        )
            .addMigrations(
                PlayerDatabase.MIGRATION_1_2,
                PlayerDatabase.MIGRATION_2_3,
                PlayerDatabase.MIGRATION_3_4,
                PlayerDatabase.MIGRATION_4_5,
                PlayerDatabase.MIGRATION_5_6
            )
            .build()

    @Provides
    @Singleton
    fun provideTrackDao(db: PlayerDatabase): TrackDao = db.trackDao()

    @Provides
    @Singleton
    fun providePlaylistDao(db: PlayerDatabase): PlaylistDao = db.playlistDao()

    @Provides
    @Singleton
    fun provideFtpConfigDao(db: PlayerDatabase): FtpConfigDao = db.ftpConfigDao()
}
