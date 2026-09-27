package us.berkovitz.plexaaos.data.media

import androidx.room.Entity
import androidx.room.PrimaryKey


@Entity
data class MediaItemEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val artistName: String,
    val albumName: String,
    val key: String,
    val uri: String,
    val durationMs: Long,
    val iconUri: String?,
    val updatedAt: Long,
    val bitrate: Int,
)
