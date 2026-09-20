package us.berkovitz.plexaaos.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import us.berkovitz.plexaaos.data.database.PlexDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class DatabaseModule {
    @Provides
    @Singleton
    fun providesPlexDatabase(@ApplicationContext context: Context): PlexDatabase{
        return Room.databaseBuilder(
            context.applicationContext,
            PlexDatabase::class.java,
            PlexDatabase.PLEX_DB_NAME
        ).build()
    }
}