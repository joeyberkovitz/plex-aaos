package us.berkovitz.plexaaos.data.repositories

import kotlinx.coroutines.flow.Flow
import us.berkovitz.plexaaos.data.database.PlexDatabase
import us.berkovitz.plexaaos.data.media.MediaItemEntity
import us.berkovitz.plexaaos.data.media.PlaylistEntity
import javax.inject.Inject

class MediaItemRepository @Inject constructor(
    private var plexDatabase: PlexDatabase
) {
    val allMediaItems: Flow<List<MediaItemEntity>> = plexDatabase.mediaItemDao().getAllMediaItems()
}