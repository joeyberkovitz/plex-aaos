package us.berkovitz.plexaaos.library

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import us.berkovitz.plexaaos.PlexLoggerFactory
import us.berkovitz.plexapi.media.MediaItem
import us.berkovitz.plexapi.media.Playlist
import us.berkovitz.plexapi.media.PlaylistType
import us.berkovitz.plexapi.media.PlexServer
import us.berkovitz.plexapi.myplex.MyPlexAccount
import kotlin.coroutines.coroutineContext

class PlexSource(private val plexToken: String) : AbstractMusicSource() {
    companion object {
        val logger = PlexLoggerFactory.loggerFor(PlexSource::class)
    }

    private var catalog: MutableMap<String, Playlist> = hashMapOf()
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
            catalog = hashMapOf()
            state = STATE_ERROR
        }
    }

    override suspend fun loadPlaylist(playlistId: String): Playlist? {
        val plist = loadPlaylistItems(playlistId)
        plist.let{ res ->
            if (res != null) {
                setPlaylistState(playlistId, res, STATE_INITIALIZED)
            } else {
                setPlaylistState(playlistId, null, STATE_ERROR)
            }
        }
        return plist
    }

    override fun iterator(): Iterator<Playlist> {
        return catalog.values.iterator()
    }

    override fun getPlaylist(playlistId: String): Playlist? {
        return catalog[playlistId]
    }

    override fun playlistIterator(playlistId: String): Iterator<MediaItem>? {
        return catalog[playlistId]?.loadedItems()?.iterator()
    }

    override fun getPlaylistItems(playlistId: String): Array<MediaItem>? {
        return catalog[playlistId]?.loadedItems()
    }

    private suspend fun loadPlaylistItems(playlistId: String): Playlist? {
        return withContext(Dispatchers.IO) {
            var playlist = catalog[playlistId]
            if (playlist == null) {
                logger.warn( "Playlist $playlistId missing from catalog")
                if (plexServer == null) {
                    findServer()
                }
                val playlistIdInt = playlistId.toIntOrNull()
                if (playlistIdInt == null) {
                    logger.warn( "Invalid playlist id: $playlistId")
                    return@withContext null
                }

                playlist = Playlist.fromId(playlistId.toInt(), plexServer!!)
                if (playlist == null) {
                    logger.warn( "Failed to find playlist: $playlistId")
                    return@withContext null
                }
                playlist.setServer(plexServer!!)
                catalog[playlistId] = playlist
            }

            try {
                playlist.items()
                logger.info( "Playlist $playlistId loaded")
            } catch (e: Exception) {
                logger.error( "Error loading $playlistId: ${e.message}, ${e.printStackTrace()}")
                return@withContext null
            }
            return@withContext playlist
        }
    }

    private suspend fun updateCatalog(): MutableMap<String, Playlist> {
        return withContext(Dispatchers.IO) {
            findServer()
            if (plexServer == null) {
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

    private suspend fun findServer() {
        if(plexServer != null)
            return

        val servers = plexAccount.resources()
        for (server in servers) {
            var hasRemote = false
            if (server.connections != null) {
                for (conn in server.connections!!) {
                    if (conn.local == 0) {
                        val connUrl = conn.uri
                        val overrideToken = server.accessToken
                        val potentialServer = PlexServer(connUrl, overrideToken ?: plexToken)
                        logger.debug( "Trying server: ${connUrl}")
                        if(potentialServer.testConnection()){
                            logger.debug( "Connection succeeded")
                            hasRemote = true
                            plexServer = potentialServer
                            break
                        } else {
                            logger.debug( "Connection failed")
                        }
                    }
                }
                if (hasRemote) {
                    break
                }
            }
        }
    }
}
