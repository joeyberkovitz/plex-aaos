package us.berkovitz.plexaaos.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import us.berkovitz.plexapi.media.Playlist
import us.berkovitz.plexapi.media.PlaylistType
import us.berkovitz.plexapi.media.PlexServer
import us.berkovitz.plexapi.myplex.MyPlexAccount

class PlexSource(private val plexToken: String): AbstractMusicSource() {

    private var catalog: List<Playlist> = emptyList()
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
            catalog = emptyList()
            state = STATE_ERROR
        }    }

    override fun iterator(): Iterator<Playlist> = catalog.iterator()

    private suspend fun updateCatalog(): List<Playlist> {
        return withContext(Dispatchers.IO){
            findServer()
            if(plexServer == null){
                return@withContext listOf()
            }

            val playlists = plexServer!!.playlists(PlaylistType.AUDIO)
            for (playlist in playlists) {
                // make sure items are loaded here
                playlist.items()
            }
            playlists.toList()
        }
    }

    private suspend fun findServer(){
        val servers = plexAccount.resources()
        for(server in servers){
            var hasRemote = false
            var connUrl = ""
            if(server.connections != null) {
                for (conn in server.connections!!) {
                    if (conn.local != 0) {
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
