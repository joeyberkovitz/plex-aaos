package us.berkovitz.plexaaos.data.media

import androidx.room.Entity

@Entity(primaryKeys = ["playlistId", "mediaItemId"])
data class PlaylistEntry(
    val playlistId: Long,
    val mediaItemId: Long,
)
