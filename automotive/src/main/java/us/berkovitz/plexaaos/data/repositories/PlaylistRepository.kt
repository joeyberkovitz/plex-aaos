package us.berkovitz.plexaaos.data.repositories

import kotlinx.coroutines.flow.Flow
import us.berkovitz.plexaaos.data.database.PlexDatabase
import us.berkovitz.plexaaos.data.media.MediaItemEntity
import us.berkovitz.plexaaos.data.media.PlaylistEntity
import javax.inject.Inject

class PlaylistRepository @Inject constructor(
    private var plexDatabase: PlexDatabase
) {
    val playlistFlow: Flow<List<PlaylistEntity>> = plexDatabase.playlistDao().playlistFlow()

    suspend fun allPlaylists(): List<PlaylistEntity> {
        return plexDatabase.playlistDao().getAllPlaylists()
    }

    suspend fun getPlaylist(playlistID: Long): PlaylistEntity? {
        val plist = plexDatabase.playlistDao().getPlaylistWithSongs(playlistID) ?: return null
        return plist.playlist
    }

    suspend fun getPlaylistSongs(playlistID: Long): Array<MediaItemEntity> {
        val plist = plexDatabase.playlistDao().getPlaylistWithSongs(playlistID) ?: return arrayOf()
        return plist.songs.toTypedArray()
    }
}
