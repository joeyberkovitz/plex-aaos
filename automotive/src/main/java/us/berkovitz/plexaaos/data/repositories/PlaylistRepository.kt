package us.berkovitz.plexaaos.data.repositories

import kotlinx.coroutines.flow.Flow
import us.berkovitz.plexaaos.data.database.PlexDatabase
import us.berkovitz.plexaaos.data.media.PlaylistEntity
import javax.inject.Inject

class PlaylistRepository @Inject constructor(
    private var plexDatabase: PlexDatabase
) {
    val allPlaylists: Flow<List<PlaylistEntity>> = plexDatabase.playlistDao().getAllPlaylists()
}