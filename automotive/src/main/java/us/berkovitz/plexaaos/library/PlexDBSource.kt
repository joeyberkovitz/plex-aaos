package us.berkovitz.plexaaos.library

import us.berkovitz.plexaaos.data.media.MediaItemEntity
import us.berkovitz.plexaaos.data.repositories.MediaItemRepository
import us.berkovitz.plexaaos.data.repositories.PlaylistRepository
import javax.inject.Inject

class PlexDBSource @Inject constructor(
    private var playlistRepository: PlaylistRepository,
    private var mediaItemRepository: MediaItemRepository
) {
    suspend fun getPlaylistItemsAsync(playlistID: Long) : Array<MediaItemEntity> {
        return playlistRepository.getPlaylistSongs(playlistID)
    }
}