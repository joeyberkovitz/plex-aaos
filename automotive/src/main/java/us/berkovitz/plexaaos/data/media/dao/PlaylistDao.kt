package us.berkovitz.plexaaos.data.media.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Junction
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import us.berkovitz.plexaaos.data.media.MediaItemEntity
import us.berkovitz.plexaaos.data.media.PlaylistEntity
import us.berkovitz.plexaaos.data.media.PlaylistEntry

data class PlaylistWithSongs(
    @Embedded val playlist: PlaylistEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            PlaylistEntry::class,
            parentColumn = "playlistId",
            entityColumn = "mediaItemId"
        )
    )
    val songs: List<MediaItemEntity>
)

@Dao
interface PlaylistDao {
    @Transaction
    @Query("SELECT * FROM PlaylistEntity")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Transaction
    @Query("SELECT * FROM PlaylistEntity WHERE id=:id")
    suspend fun getPlaylistById(id: Long): PlaylistEntity?

    @Transaction
    @Query("SELECT * FROM PlaylistEntity WHERE id=:id")
    suspend fun getPlaylistWithSongs(id: Long): PlaylistWithSongs?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(vararg playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistEntries(vararg playlistEntries: PlaylistEntry)

    @Delete
    suspend fun deletePlaylists(vararg playlist: PlaylistEntity)

    @Delete
    suspend fun deletePlaylistEntries(vararg playlistEntries: PlaylistEntry)
}