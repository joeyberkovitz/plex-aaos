package us.berkovitz.plexaaos.data.media.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import us.berkovitz.plexaaos.data.media.MediaItemEntity

@Dao
interface MediaItemDao {

    @Transaction
    @Query("SELECT * FROM MediaItemEntity")
    fun getAllMediaItems(): Flow<List<MediaItemEntity>>

    @Transaction
    @Query("SELECT * FROM MediaItemEntity WHERE id=:id")
    suspend fun getMediaItemById(id: Long): MediaItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaItems(vararg mediaItem: MediaItemEntity)

    @Delete
    suspend fun deleteMediaItems(vararg items: MediaItemEntity)
}