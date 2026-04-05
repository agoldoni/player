package com.example.player.di

import android.content.Context
import androidx.room.Room
import com.example.player.data.local.PlayerDatabase
import com.example.player.data.local.dao.TrackDao
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
        ).build()

    @Provides
    @Singleton
    fun provideTrackDao(db: PlayerDatabase): TrackDao = db.trackDao()
}
