package us.berkovitz.plexaaos.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import us.berkovitz.plexaaos.data.media.MediaItemEntity
import us.berkovitz.plexaaos.data.media.PlaylistEntity
import us.berkovitz.plexaaos.data.media.PlaylistEntry
import us.berkovitz.plexaaos.data.media.dao.MediaItemDao
import us.berkovitz.plexaaos.data.media.dao.PlaylistDao
import javax.inject.Inject

@Database(
    entities = [PlaylistEntity::class, MediaItemEntity::class, PlaylistEntry::class],
    version = 1,
)
abstract  class PlexDatabase: RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun mediaItemDao(): MediaItemDao

    @Inject
    internal lateinit var plexDatabase: PlexDatabase

    companion object {
        val PLEX_DB_NAME = "plex_db"
    }
}