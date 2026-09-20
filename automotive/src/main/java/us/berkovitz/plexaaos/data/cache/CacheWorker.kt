package us.berkovitz.plexaaos.data.cache

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import us.berkovitz.plexaaos.PlexUtil
import us.berkovitz.plexaaos.data.database.PlexDatabase
import us.berkovitz.plexaaos.data.media.MediaItemEntity
import us.berkovitz.plexaaos.data.media.PlaylistEntity
import us.berkovitz.plexaaos.data.media.PlaylistEntry
import us.berkovitz.plexapi.media.Playlist
import us.berkovitz.plexapi.media.PlaylistType
import us.berkovitz.plexapi.media.PlexServer
import us.berkovitz.plexapi.media.Track

@HiltWorker
class CacheWorker @AssistedInject constructor(
    @Assisted val appContext: Context,
    @Assisted val workerParams: WorkerParameters,
    private var plexDatabase: PlexDatabase
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val TAG = "CACHE"
    }

    override suspend fun doWork(): Result {
        withContext(Dispatchers.IO) {
            refreshCache()
        }
        return Result.success()
    }


    suspend fun refreshCache() {
        Log.i("CACHE", "cache worker starting")
        val plexUtil = PlexUtil(appContext)
        val token = plexUtil.getToken() ?: return

        val server = PlexUtil.findServer(appContext, token) ?: return

        val playlists = server.playlists(PlaylistType.AUDIO)
        Log.i("CACHE", "got playlists: ${playlists.contentToString()}")
        for (playlist in playlists) {
            cachePlaylist(playlist, server)
        }
    }

    suspend fun cachePlaylist(playlist: Playlist, server: PlexServer) {
        val playlistIdLong = playlist.ratingKey
        if (playlistIdLong == null) {
            Log.w(TAG, "invalid playlist ID: $playlistIdLong")
            return
        }

        val existingPlaylist = plexDatabase.playlistDao().getPlaylistById(playlistIdLong)
        val needsUpdate =
            existingPlaylist == null || existingPlaylist.updatedAt < playlist.updatedAt
        if (!needsUpdate) {
            Log.i(TAG, "playlist ${playlist.key} already up to date")
            return
        }

        val existingPlaylistWithSongs =
            plexDatabase.playlistDao().getPlaylistWithSongs(playlistIdLong)
        val items = playlist.items()

        val existingSongIds =
            existingPlaylistWithSongs?.songs?.map { s -> s.id }?.toSet() ?: emptySet()
        val newSongIds = items.filterIsInstance<Track>().map { it.ratingKey }.toSet()

        val songsToDelete = (existingSongIds - newSongIds).map {
            PlaylistEntry(playlistIdLong, it)
        }.toTypedArray()

        val songsToAdd = (newSongIds - existingSongIds).map {
            PlaylistEntry(playlistIdLong, it)
        }.toTypedArray()

        plexDatabase.playlistDao().deletePlaylistEntries(*songsToDelete)

        plexDatabase.mediaItemDao().insertMediaItems(*(items.filterIsInstance<Track>().map {
            val itemIcon: String? = if (!it.thumb.isNullOrEmpty()) {
                it.thumb
            } else if (!it.parentThumb.isNullOrEmpty()) {
                it.parentThumb
            } else if (!it.grandparentThumb.isNullOrEmpty()) {
                it.grandparentThumb
            } else {
                null
            }

            var artistName = it.grandparentTitle
            if (!it.originalTitle.isNullOrEmpty()) {
                artistName = it.originalTitle
            }

            MediaItemEntity(
                it.ratingKey,
                it.title,
                artistName ?: "",
                it.parentTitle ?: "",
                it.getStreamUrl(),
                it.duration,
                itemIcon,
                it.updatedAt?.toLongOrNull() ?: 0,
            )
        }.toTypedArray()))

        var playlistIconUri: String? = playlist.composite
        if(playlistIconUri.isNullOrEmpty()) {
            playlistIconUri = playlist.icon
        }

        // only mark the playlist updated if entries are created as well
        plexDatabase.withTransaction {
            plexDatabase.playlistDao().insertPlaylists(
                PlaylistEntity(
                    playlistIdLong,
                    playlist.title,
                    playlist.key,
                    playlist.duration,
                    playlistIconUri,
                    playlist.updatedAt

                )
            )

            plexDatabase.playlistDao().insertPlaylistEntries(*songsToAdd)
        }

        Log.i(TAG, "playlist $playlistIdLong cached")
    }
}
