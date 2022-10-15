package us.berkovitz.plexaaos.library

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import us.berkovitz.plexapi.media.MediaItem
import us.berkovitz.plexapi.media.Playlist
import us.berkovitz.plexapi.media.PlaylistType
import us.berkovitz.plexapi.media.PlexServer
import us.berkovitz.plexapi.myplex.MyPlexAccount
import kotlin.coroutines.coroutineContext

private const val TAG = "PlexSource"

class PlexSource(private val plexToken: String): AbstractMusicSource() {

    private var catalog: Map<String, Playlist> = hashMapOf()
    private val plexAccount = MyPlexAccount(plexToken)
    private var plexServer: PlexServer? = null

    init {
        state = STATE_INITIALIZING
    }

    override suspend fun load() {
        updateCatalog().let { updatedCatalog ->
            catalog = updatedCatalog
            state = STATE_INITIALIZED
        } ?: run {
            catalog = emptyMap()
            state = STATE_ERROR
        }    }

    override suspend fun loadPlaylist(playlistId: String) {
        loadPlaylistItems(playlistId).let { res ->
            if (res) {
                setPlaylistState(playlistId, STATE_INITIALIZED)
            } else {
                setPlaylistState(playlistId, STATE_ERROR)
            }
        }
    }

    override fun iterator(): Iterator<Playlist> {
        return catalog.values.iterator()
    }

    override fun playlistIterator(playlistId: String): Iterator<MediaItem>? {
        return catalog[playlistId]?.loadedItems()?.iterator()
    }

    override fun getPlaylistItems(playlistId: String): Array<MediaItem>? {
        return catalog[playlistId]?.loadedItems()
    }

    private suspend fun loadPlaylistItems(playlistId: String): Boolean {
        return withContext(Dispatchers.IO){
            if(catalog[playlistId] == null) {
                Log.w(TAG, "Playlist $playlistId missing from catalog")
                return@withContext false
            }

            try {
                catalog[playlistId]?.items()
                Log.i(TAG, "Playlist $playlistId loaded")
            } catch (e: Exception){
                Log.e(TAG, "Error loading $playlistId", e)
                return@withContext false
            }

            return@withContext true
        }
    }

    private suspend fun updateCatalog(): Map<String, Playlist> {
        return withContext(Dispatchers.IO){
            findServer()
            if(plexServer == null){
                return@withContext hashMapOf()
            }

            val playlists = plexServer!!.playlists(PlaylistType.AUDIO)
            val retMap = hashMapOf<String, Playlist>()
            playlists.forEach {
                retMap[it.ratingKey.toString()] = it
            }
            /*for (playlist in playlists) {
                // make sure items are loaded here
                playlist.items()
            }*/
            return@withContext retMap
        }
    }

    private suspend fun findServer(){
        val servers = plexAccount.resources()
        for(server in servers){
            var hasRemote = false
            var connUrl = ""
            if(server.connections != null) {
                for (conn in server.connections!!) {
                    if (conn.local == 0) {
                        hasRemote = true
                        connUrl = conn.uri
                        break
                    }
                }
                if (hasRemote) {
                    plexServer = PlexServer(connUrl, plexToken)
                    break
                }
            }
        }
    }
}
