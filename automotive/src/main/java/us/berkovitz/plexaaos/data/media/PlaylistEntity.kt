package us.berkovitz.plexaaos.data.media

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class PlaylistEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val key: String,
    val durationMs: Long,
    val iconUri: String?,
    val updatedAt: Long,
)