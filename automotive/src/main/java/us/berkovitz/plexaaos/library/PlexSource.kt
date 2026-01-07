package us.berkovitz.plexaaos.library

import android.content.Context
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import us.berkovitz.plexaaos.AndroidStorage
import us.berkovitz.plexaaos.PlexLoggerFactory
import us.berkovitz.plexaaos.PlexUtil
import us.berkovitz.plexapi.media.Album
import us.berkovitz.plexapi.media.Artist
import us.berkovitz.plexapi.media.MediaItem
import us.berkovitz.plexapi.media.Playlist
import us.berkovitz.plexapi.media.PlaylistType
import us.berkovitz.plexapi.media.PlexServer
import us.berkovitz.plexapi.media.Track
import us.berkovitz.plexapi.myplex.MyPlexAccount
import kotlin.coroutines.coroutineContext

class PlexSource(
    private val plexToken: String,
    private val context: Context
) :
    AbstractMusicSource() {
    companion object {
        val logger = PlexLoggerFactory.loggerFor(PlexSource::class)
    }

    private var catalog: MutableMap<String, Playlist> = hashMapOf()
    private val plexAccount = MyPlexAccount(plexToken)
    private var plexServer: PlexServer? = null

    // New caches for artists and albums
    private var musicSectionId: String? = null
    private var artistsCache: List<Artist> = emptyList()
    private var albumsCache: List<Album> = emptyList()
    private var artistAlbumsCache: MutableMap<String, List<Album>> = hashMapOf()
    private var albumTracksCache: MutableMap<String, List<Track>> = hashMapOf()

    // Home tab caches
    private var recentlyPlayedCache: List<Track> = emptyList()
    private var recentlyAddedCache: List<Album> = emptyList()
    private var onDeckCache: List<Track> = emptyList()
    private var allTracksCache: List<Track> = emptyList()

    init {
        state = STATE_INITIALIZING
    }

    override suspend fun load() {
        state = updateCatalog()
    }

    override suspend fun loadPlaylist(playlistId: String): Playlist? {
        val state = getPlaylistState(playlistId)
        if (state == STATE_INITIALIZING) return null
        else if (state != STATE_INITIALIZED) setPlaylistState(playlistId, null, STATE_INITIALIZING)

        val plist = loadPlaylistItems(playlistId)
        plist.let { res ->
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
                logger.warn("Playlist $playlistId missing from catalog")
                if (plexServer == null) {
                    findServer()
                }
                val playlistIdLong = playlistId.toLongOrNull()
                if (playlistIdLong == null) {
                    logger.warn("Invalid playlist id: $playlistId")
                    return@withContext null
                }

                try {
                    playlist = Playlist.fromId(playlistId.toLong(), plexServer!!)
                } catch (exc: Exception) {
                    FirebaseCrashlytics.getInstance().recordException(exc)
                }
                if (playlist == null) {
                    logger.warn("Failed to find playlist: $playlistId")
                    return@withContext null
                }
                playlist!!.setServer(plexServer!!)
                catalog[playlistId] = playlist!!
            }

            try {
                val items = playlist!!.items()
                logger.info("Playlist $playlistId loaded, ${items.size}")
            } catch (e: Exception) {
                logger.error("Error loading $playlistId: ${e.message}, ${e.printStackTrace()}")
                return@withContext null
            }
            return@withContext playlist
        }
    }

    private suspend fun updateCatalog(): Int {
        return withContext(Dispatchers.IO) {
            findServer()
            if (plexServer == null) {
                return@withContext STATE_ERROR
            }

            // Find music library section
            try {
                val musicSection = plexServer!!.musicSection()
                if (musicSection != null) {
                    musicSectionId = musicSection.key
                    logger.info("Found music section: ${musicSection.title} (id: ${musicSection.key})")
                } else {
                    logger.warn("No music library section found")
                }
            } catch (e: Exception) {
                logger.error("Error finding music section: ${e.message}")
            }

            val playlists = plexServer!!.playlists(PlaylistType.AUDIO)
            playlists.forEach {
                if (!catalog.containsKey(it.ratingKey.toString()))
                    catalog[it.ratingKey.toString()] = it
            }
            /*for (playlist in playlists) {
                // make sure items are loaded here
                playlist.items()
            }*/
            return@withContext STATE_INITIALIZED
        }
    }

    private suspend fun findServer() {
        if (plexServer != null)
            return

        val selectedServer = AndroidStorage.getServer(context)

        //TODO: if setting is changed, need to force a reload
        val servers = PlexUtil.getServers(plexToken)
        for (server in servers) {
            // If a server is set, force that one
            if(selectedServer != null && server.clientIdentifier != selectedServer){
                continue
            }

            var hasRemote = false
            if (server.connections != null) {
                for (conn in server.connections!!) {
                    if (conn.local == 0) {
                        val connUrl = conn.uri
                        val overrideToken = server.accessToken
                        val potentialServer = PlexServer(connUrl, overrideToken ?: plexToken)
                        logger.debug("Trying server: $connUrl")
                        if (potentialServer.testConnection()) {
                            logger.debug("Connection succeeded")
                            hasRemote = true
                            plexServer = potentialServer
                            break
                        } else {
                            logger.debug("Connection failed")
                        }
                    }
                }
                if (hasRemote) {
                    break
                }
            }
        }
    }

    // New methods for artists and albums browsing

    override fun getMusicSectionId(): String? = musicSectionId

    override suspend fun loadArtists(): List<Artist> {
        return withContext(Dispatchers.IO) {
            findServer()
            if (plexServer == null || musicSectionId == null) {
                logger.warn("Cannot load artists: server or music section not available")
                return@withContext emptyList()
            }

            try {
                setArtistsState(STATE_INITIALIZING, null)
                val artists = plexServer!!.artists(musicSectionId!!)
                artistsCache = artists
                setArtistsState(STATE_INITIALIZED, artists)
                logger.info("Loaded ${artists.size} artists")
                return@withContext artists
            } catch (e: Exception) {
                logger.error("Error loading artists: ${e.message}")
                setArtistsState(STATE_ERROR, null)
                return@withContext emptyList()
            }
        }
    }

    override suspend fun loadAlbums(): List<Album> {
        return withContext(Dispatchers.IO) {
            findServer()
            if (plexServer == null || musicSectionId == null) {
                logger.warn("Cannot load albums: server or music section not available")
                return@withContext emptyList()
            }

            try {
                setAlbumsState(STATE_INITIALIZING, null)
                val albums = plexServer!!.albums(musicSectionId!!)
                albumsCache = albums
                setAlbumsState(STATE_INITIALIZED, albums)
                logger.info("Loaded ${albums.size} albums")
                return@withContext albums
            } catch (e: Exception) {
                logger.error("Error loading albums: ${e.message}")
                setAlbumsState(STATE_ERROR, null)
                return@withContext emptyList()
            }
        }
    }

    override suspend fun loadArtistAlbums(artistId: String): List<Album> {
        return withContext(Dispatchers.IO) {
            findServer()
            if (plexServer == null) {
                logger.warn("Cannot load artist albums: server not available")
                return@withContext emptyList()
            }

            try {
                setArtistAlbumsState(artistId, STATE_INITIALIZING, null)
                val artistRatingKey = artistId.toLongOrNull()
                if (artistRatingKey == null) {
                    logger.warn("Invalid artist id: $artistId")
                    setArtistAlbumsState(artistId, STATE_ERROR, null)
                    return@withContext emptyList()
                }

                val albums = plexServer!!.artistAlbums(artistRatingKey)
                artistAlbumsCache[artistId] = albums
                setArtistAlbumsState(artistId, STATE_INITIALIZED, albums)
                logger.info("Loaded ${albums.size} albums for artist $artistId")
                return@withContext albums
            } catch (e: Exception) {
                logger.error("Error loading artist albums: ${e.message}")
                setArtistAlbumsState(artistId, STATE_ERROR, null)
                return@withContext emptyList()
            }
        }
    }

    override suspend fun loadAlbumTracks(albumId: String): List<Track> {
        return withContext(Dispatchers.IO) {
            findServer()
            if (plexServer == null) {
                logger.warn("Cannot load album tracks: server not available")
                return@withContext emptyList()
            }

            try {
                setAlbumTracksState(albumId, STATE_INITIALIZING, null)
                val albumRatingKey = albumId.toLongOrNull()
                if (albumRatingKey == null) {
                    logger.warn("Invalid album id: $albumId")
                    setAlbumTracksState(albumId, STATE_ERROR, null)
                    return@withContext emptyList()
                }

                val tracks = plexServer!!.albumTracks(albumRatingKey)
                albumTracksCache[albumId] = tracks
                setAlbumTracksState(albumId, STATE_INITIALIZED, tracks)
                logger.info("Loaded ${tracks.size} tracks for album $albumId")
                return@withContext tracks
            } catch (e: Exception) {
                logger.error("Error loading album tracks: ${e.message}")
                setAlbumTracksState(albumId, STATE_ERROR, null)
                return@withContext emptyList()
            }
        }
    }

    override fun getArtists(): List<Artist> = artistsCache

    override fun getAlbums(): List<Album> = albumsCache

    override fun getAlbumTracks(albumId: String): List<Track>? = albumTracksCache[albumId]

    // Home tab implementations

    override suspend fun loadRecentlyPlayed(): List<Track> {
        return withContext(Dispatchers.IO) {
            findServer()
            if (plexServer == null) {
                logger.warn("Cannot load recently played: server not available")
                setRecentlyPlayedState(STATE_ERROR, null)
                return@withContext emptyList()
            }

            // Ensure music section is found
            if (musicSectionId == null) {
                try {
                    val musicSection = plexServer!!.musicSection()
                    if (musicSection != null) {
                        musicSectionId = musicSection.key
                        logger.info("Found music section for recently played: ${musicSection.title}")
                    }
                } catch (e: Exception) {
                    logger.error("Error finding music section: ${e.message}")
                }
            }

            if (musicSectionId == null) {
                logger.warn("Cannot load recently played: music section not found")
                setRecentlyPlayedState(STATE_ERROR, null)
                return@withContext emptyList()
            }

            try {
                setRecentlyPlayedState(STATE_INITIALIZING, null)
                val tracks = plexServer!!.recentlyPlayedTracks(musicSectionId!!, 50)
                recentlyPlayedCache = tracks
                setRecentlyPlayedState(STATE_INITIALIZED, tracks)
                logger.info("Loaded ${tracks.size} recently played tracks")
                return@withContext tracks
            } catch (e: Exception) {
                logger.error("Error loading recently played: ${e.message}")
                setRecentlyPlayedState(STATE_ERROR, null)
                return@withContext emptyList()
            }
        }
    }

    override suspend fun loadRecentlyAdded(): List<Album> {
        return withContext(Dispatchers.IO) {
            findServer()
            if (plexServer == null) {
                logger.warn("Cannot load recently added: server not available")
                setRecentlyAddedState(STATE_ERROR, null)
                return@withContext emptyList()
            }

            // Ensure music section is found
            if (musicSectionId == null) {
                try {
                    val musicSection = plexServer!!.musicSection()
                    if (musicSection != null) {
                        musicSectionId = musicSection.key
                        logger.info("Found music section for recently added: ${musicSection.title}")
                    }
                } catch (e: Exception) {
                    logger.error("Error finding music section: ${e.message}")
                }
            }

            if (musicSectionId == null) {
                logger.warn("Cannot load recently added: music section not found")
                setRecentlyAddedState(STATE_ERROR, null)
                return@withContext emptyList()
            }

            try {
                setRecentlyAddedState(STATE_INITIALIZING, null)
                val albums = plexServer!!.recentlyAddedAlbums(musicSectionId!!, 50)
                recentlyAddedCache = albums
                setRecentlyAddedState(STATE_INITIALIZED, albums)
                logger.info("Loaded ${albums.size} recently added albums")
                return@withContext albums
            } catch (e: Exception) {
                logger.error("Error loading recently added: ${e.message}")
                setRecentlyAddedState(STATE_ERROR, null)
                return@withContext emptyList()
            }
        }
    }

    override suspend fun loadOnDeck(): List<Track> {
        return withContext(Dispatchers.IO) {
            findServer()
            if (plexServer == null) {
                logger.warn("Cannot load on deck: server not available")
                return@withContext emptyList()
            }

            try {
                setOnDeckState(STATE_INITIALIZING, null)
                val tracks = plexServer!!.onDeck()
                onDeckCache = tracks
                setOnDeckState(STATE_INITIALIZED, tracks)
                logger.info("Loaded ${tracks.size} on deck tracks")
                return@withContext tracks
            } catch (e: Exception) {
                logger.error("Error loading on deck: ${e.message}")
                setOnDeckState(STATE_ERROR, null)
                return@withContext emptyList()
            }
        }
    }

    override fun getRecentlyPlayed(): List<Track> = recentlyPlayedCache

    override fun getRecentlyAdded(): List<Album> = recentlyAddedCache

    override fun getOnDeck(): List<Track> = onDeckCache

    // All Music implementation

    override suspend fun loadAllTracks(): List<Track> {
        return withContext(Dispatchers.IO) {
            findServer()
            if (plexServer == null) {
                logger.warn("Cannot load all tracks: server not available")
                setAllTracksState(STATE_ERROR, null)
                return@withContext emptyList()
            }

            // Ensure music section is found
            if (musicSectionId == null) {
                try {
                    val musicSection = plexServer!!.musicSection()
                    if (musicSection != null) {
                        musicSectionId = musicSection.key
                        logger.info("Found music section for all tracks: ${musicSection.title}")
                    }
                } catch (e: Exception) {
                    logger.error("Error finding music section: ${e.message}")
                }
            }

            if (musicSectionId == null) {
                logger.warn("Cannot load all tracks: music section not found")
                setAllTracksState(STATE_ERROR, null)
                return@withContext emptyList()
            }

            try {
                setAllTracksState(STATE_INITIALIZING, null)
                val tracks = plexServer!!.tracks(musicSectionId!!)
                allTracksCache = tracks
                setAllTracksState(STATE_INITIALIZED, tracks)
                logger.info("Loaded ${tracks.size} tracks from library")
                return@withContext tracks
            } catch (e: Exception) {
                logger.error("Error loading all tracks: ${e.message}")
                setAllTracksState(STATE_ERROR, null)
                return@withContext emptyList()
            }
        }
    }

    override fun getAllTracks(): List<Track> = allTracksCache
}
