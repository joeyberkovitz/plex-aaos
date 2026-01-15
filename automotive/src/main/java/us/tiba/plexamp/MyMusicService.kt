package us.tiba.plexamp

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.Toast
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector.CustomActionProvider
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.offline.DownloadManager
import com.google.android.exoplayer2.offline.DownloadRequest
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.cache.Cache
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import com.google.android.exoplayer2.util.Util
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.tiba.plexamp.extensions.flag
import us.tiba.plexamp.extensions.id
import us.tiba.plexamp.extensions.title
import us.tiba.plexamp.extensions.toMediaItem
import us.tiba.plexamp.library.ALBUM_PREFIX
import us.tiba.plexamp.library.ARTIST_PREFIX
import us.tiba.plexamp.library.BrowseTree
import us.tiba.plexamp.library.MusicSource
import us.tiba.plexamp.library.PlexSource
import us.tiba.plexamp.library.HOME_PREFIX
import us.tiba.plexamp.library.RESOURCE_ROOT_URI
import us.tiba.plexamp.library.UAMP_ALBUMS_ROOT
import us.tiba.plexamp.library.UAMP_ARTISTS_ROOT
import us.tiba.plexamp.library.UAMP_BROWSABLE_ROOT
import us.tiba.plexamp.library.UAMP_HOME_ROOT
import us.tiba.plexamp.library.UAMP_PLAYLISTS_ROOT
import us.tiba.plexamp.library.UAMP_RECENTLY_ADDED_ROOT
import us.tiba.plexamp.library.UAMP_RECENTLY_PLAYED_ROOT
import us.tiba.plexamp.library.buildMeta
import us.tiba.plexamp.library.from
import us.tiba.plexamp.library.fromAlbumTrack
import us.tiba.plexapi.media.Track
import us.tiba.plexapi.myplex.AuthorizationException
import kotlin.math.ceil
import kotlin.math.min

/**
 * This class provides a MediaBrowser through a service. It exposes the media library to a browsing
 * client, through the onGetRoot and onLoadChildren methods. It also creates a MediaSession and
 * exposes it through its MediaSession.Token, which allows the client to create a MediaController
 * that connects to and send control commands to the MediaSession remotely. This is useful for
 * user interfaces that need to interact with your media session, like Android Auto. You can
 * (should) also use the same service from your app's UI, which gives a seamless playback
 * experience to the user.
 *
 *
 * To implement a MediaBrowserService, you need to:
 *
 *  *  Extend [MediaBrowserServiceCompat], implementing the media browsing
 * related methods [MediaBrowserServiceCompat.onGetRoot] and
 * [MediaBrowserServiceCompat.onLoadChildren];
 *
 *  *  In onCreate, start a new [MediaSessionCompat] and notify its parent
 * with the session"s token [MediaBrowserServiceCompat.setSessionToken];
 *
 *  *  Set a callback on the [MediaSessionCompat.setCallback].
 * The callback will receive all the user"s actions, like play, pause, etc;
 *
 *  *  Handle all the actual music playing using any method your app prefers (for example,
 * [android.media.MediaPlayer])
 *
 *  *  Update playbackState, "now playing" metadata and queue, using MediaSession proper methods
 * [MediaSessionCompat.setPlaybackState]
 * [MediaSessionCompat.setMetadata] and
 * [MediaSessionCompat.setQueue])
 *
 *  *  Declare and export the service in AndroidManifest with an intent receiver for the action
 * android.media.browse.MediaBrowserService
 *
 * To make your app compatible with Android Auto, you also need to:
 *
 *  *  Declare a meta-data tag in AndroidManifest.xml linking to a xml resource
 * with a &lt;automotiveApp&gt; root element. For a media app, this must include
 * an &lt;uses name="media"/&gt; element as a child.
 * For example, in AndroidManifest.xml:
 * &lt;meta-data android:name="com.google.android.gms.car.application"
 * android:resource="@xml/automotive_app_desc"/&gt;
 * And in res/values/automotive_app_desc.xml:
 * &lt;automotiveApp&gt;
 * &lt;uses name="media"/&gt;
 * &lt;/automotiveApp&gt;
 *
 */

const val LOGIN = "us.tiba.plexamp.COMMAND.LOGIN"
const val REFRESH = "us.tiba.plexamp.COMMAND.REFRESH"


class MyMusicService : MediaBrowserServiceCompat() {
    companion object {
        val logger = PlexLoggerFactory.loggerFor(MyMusicService::class)
        val PAGE_SIZE = 100
    }

    private lateinit var plexUtil: PlexUtil
    private lateinit var mediaSource: MusicSource

    // The current player will either be an ExoPlayer (for local playback) or a CastPlayer (for
    // remote playback through a Cast device).
    private lateinit var currentPlayer: Player

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaSessionConnector: MediaSessionConnector
    private var currentPlaylistItems: List<MediaMetadataCompat> = emptyList()
    private var currentMediaItemIndex: Int = 0

    private var plexToken: String? = null
    private var active = false
    private var wasActive = false
    private var previousPosition: Long = 0

    // ExoPlayer cache infrastructure
    private val downloadCache: Cache by lazy {
        val cacheDir = File(cacheDir, "exoplayer_cache")
        val databaseProvider = StandaloneDatabaseProvider(this)
        SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(200L * 1024L * 1024L), databaseProvider)
    }

    // DownloadManager Listener to track download events
    private val downloadListener = object : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: com.google.android.exoplayer2.offline.Download,
            finalException: Exception?
        ) {
            val logger = PlexLoggerFactory.loggerFor(android.app.DownloadManager::class)
            when (download.state) {
                com.google.android.exoplayer2.offline.Download.STATE_COMPLETED -> {
                    logger.debug("Download completed: ${download.request.id}")
                }
                com.google.android.exoplayer2.offline.Download.STATE_FAILED -> {
                    logger.error("Download failed: ${download.request.id}, error: ${finalException?.message}")
                }
                com.google.android.exoplayer2.offline.Download.STATE_DOWNLOADING -> {
                    val progress = download.percentDownloaded
                    logger.debug("Downloading: ${download.request.id}, progress: $progress%")
                }
                com.google.android.exoplayer2.offline.Download.STATE_QUEUED -> {
                    logger.debug("Download queued: ${download.request.id}")
                }
                com.google.android.exoplayer2.offline.Download.STATE_REMOVING -> {
                    logger.debug("Download removing: ${download.request.id}")
                }
                com.google.android.exoplayer2.offline.Download.STATE_RESTARTING -> {
                    logger.debug("Download restarting: ${download.request.id}")
                }
                com.google.android.exoplayer2.offline.Download.STATE_STOPPED -> {
                    logger.debug("Download stopped: ${download.request.id}")
                }
            }
        }

        override fun onDownloadRemoved(
            downloadManager: DownloadManager,
            download: com.google.android.exoplayer2.offline.Download
        ) {
            logger.debug("Download removed: ${download.request.id}")
        }
    }

    // ExoPlayer DownloadManager for prefetching
    private val downloadManager: DownloadManager by lazy {
        val downloadExecutor = Executors.newFixedThreadPool(6)
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        DownloadManager(
            this,
            StandaloneDatabaseProvider(this),
            downloadCache,
            httpDataSourceFactory,
            downloadExecutor
        ).apply {
            maxParallelDownloads = 3
            requirements = DownloadManager.DEFAULT_REQUIREMENTS
            addListener(downloadListener)
        }
    }

    /**
     * This must be `by lazy` because the source won't initially be ready.
     * See [MusicService.onLoadChildren] to see where it's accessed (and first
     * constructed).
     */
    private val browseTree: BrowseTree by lazy {
        BrowseTree(applicationContext, mediaSource)
    }

    private val playerAudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

    private val playerListener = PlayerEventListener()

    /**
     * Configure ExoPlayer to handle audio focus for us and use cache for playback.
     * See [Player.AudioComponent.setAudioAttributes] for details.
     */
    private val exoPlayer: ExoPlayer by lazy {
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                com.google.android.exoplayer2.source.DefaultMediaSourceFactory(cacheDataSourceFactory)
            )
            .build().apply {
                setAudioAttributes(playerAudioAttributes, true)
                setHandleAudioBecomingNoisy(true)
                addListener(playerListener)
                repeatMode = Player.REPEAT_MODE_ALL
            }
    }

    private inner class AutomotiveCommandReceiver : MediaSessionConnector.CommandReceiver {
        override fun onCommand(
            player: Player,
            command: String,
            extras: Bundle?,
            callback: ResultReceiver?
        ): Boolean =
            when (command) {
                LOGIN -> loginCommand(extras ?: Bundle.EMPTY, callback)
                REFRESH -> refreshCommand(extras ?: Bundle.EMPTY, callback)
                //LOGOUT -> logoutCommand(extras ?: Bundle.EMPTY, callback)
                else -> false
            }
    }


    override fun onCreate() {
        super.onCreate()
        AndroidPlexApi.initPlexApi(this)
        plexUtil = PlexUtil(this)

        // Build a PendingIntent that can be used to launch the UI.
        val sessionActivityPendingIntent =
            packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
                PendingIntent.getActivity(
                    this, 0, sessionIntent,
                    PendingIntent.FLAG_IMMUTABLE
                )
            }

        // Create a new MediaSession.
        mediaSession = MediaSessionCompat(this, "MyMusicService")
            .apply {
                setSessionActivity(sessionActivityPendingIntent)
                isActive = true
            }
        sessionToken = mediaSession.sessionToken

        // ExoPlayer will manage the MediaSession for us.
        mediaSessionConnector = MediaSessionConnector(mediaSession).apply {
            setPlaybackPreparer(UampPlaybackPreparer())
            setQueueNavigator(UampQueueNavigator(mediaSession))
            setCustomActionProviders(
                object : CustomActionProvider {
                    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
                        player.shuffleModeEnabled = !player.shuffleModeEnabled
                        serviceScope.launch {
                            AndroidStorage.setShuffleEnabled( player.shuffleModeEnabled, applicationContext)
                        }
                    }

                    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction {
                        val icon = if (player.shuffleModeEnabled) R.drawable.baseline_shuffle_on_24
                        else R.drawable.baseline_shuffle_24
                        return PlaybackStateCompat.CustomAction.Builder(
                            "shuffle",
                            "Shuffle",
                            icon
                        ).build()
                    }
                },
                object : CustomActionProvider {
                    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
                        player.repeatMode = when (player.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                            else -> Player.REPEAT_MODE_OFF
                        }
                        serviceScope.launch {
                            AndroidStorage.setRepeatMode( player.repeatMode, applicationContext)
                        }
                    }

                    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction {
                        val icon = when (player.repeatMode) {
                            Player.REPEAT_MODE_OFF -> R.drawable.baseline_repeat_24
                            Player.REPEAT_MODE_ALL -> R.drawable.baseline_repeat_on_24
                            Player.REPEAT_MODE_ONE -> R.drawable.baseline_repeat_one_on_24
                            else -> R.drawable.baseline_repeat_24
                        }
                        return PlaybackStateCompat.CustomAction.Builder(
                            "repeat",
                            "Repeat",
                            icon
                        ).build()
                    }
                }

            )
        }

        switchToPlayer(
            previousPlayer = null,
            newPlayer = exoPlayer
        )

        serviceScope.launch {
            // load shuffle/repeat modes from storage
            val shuffleMode = AndroidStorage.getShuffleEnabled(applicationContext)
            val repeatMode = AndroidStorage.getRepeatMode(applicationContext)
            currentPlayer.shuffleModeEnabled = shuffleMode
            currentPlayer.repeatMode = repeatMode
        }

        // Register to handle login/logout commands.
        mediaSessionConnector.registerCustomCommandReceiver(AutomotiveCommandReceiver())

        if (!isAuthenticated()) {
            logger.info("Not logged in")
            requireLogin()
            return
        }

        checkInit()
    }

    fun checkInit(force: Boolean = false) {
        if (this::mediaSource.isInitialized && !force) {
            return
        }
        // The media library is built from a remote JSON file. We'll create the source here,
        // and then use a suspend function to perform the download off the main thread.
        mediaSource = PlexSource(plexToken!!, this)
        serviceScope.launch {
            try {
                mediaSource.load()
            } catch (exc: AuthorizationException){
                plexUtil.clearToken()
                requireLogin()
            } catch (exc: Exception) {
                logger.error("error occurred while loading media source: ${exc.message} ${exc.stackTraceToString()}")
            }
        }

        if (force) {
            browseTree.updateMusicSource(mediaSource)
        }
    }

    override fun onDestroy() {
        mediaSession.run {
            isActive = false
            release()
        }

        // Cancel coroutines when the service is going away.
        serviceJob.cancel()

        // Release download manager and cache resources.
        try {
            downloadManager.removeListener(downloadListener)
            downloadManager.release()
        } catch (e: Exception) {
            // downloadManager might not have been initialized
        }

        try {
            downloadCache.release()
        } catch (e: Exception) {
            // downloadCache might not have been initialized
        }

        // Free ExoPlayer resources.
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): MediaBrowserServiceCompat.BrowserRoot {
        val rootExtras = Bundle().apply {
            putBoolean(MEDIA_SEARCH_SUPPORTED, false) //TODO: enable search
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
        }

        /**
         * By default return the browsable root. Treat the EXTRA_RECENT flag as a special case
         * and return the recent root instead.
         */
        val isRecentRequest = rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT) ?: false
        val browserRootPath = UAMP_BROWSABLE_ROOT
        // if (isRecentRequest) UAMP_RECENT_ROOT else UAMP_BROWSABLE_ROOT
        return BrowserRoot(browserRootPath, rootExtras)
    }

    override fun onLoadChildren(parentMediaId: String, result: Result<List<MediaItem>>) {
        logger.info("onLoadChildren: $parentMediaId")

        if (!isAuthenticated()) {
            result.sendResult(null)
            logger.info("not authenticated")
            return
        }
        checkInit()

        var resultsSent = false

        when {
            // Root or Playlists root
            parentMediaId == UAMP_BROWSABLE_ROOT || parentMediaId == UAMP_PLAYLISTS_ROOT -> {
                if (parentMediaId == UAMP_BROWSABLE_ROOT) {
                    serviceScope.launch {
                        try {
                            mediaSource.load()
                        } catch (exc: AuthorizationException) {
                            plexUtil.clearToken()
                            requireLogin()
                        } catch (exc: Exception) {
                            logger.error("error occurred while loading media source: ${exc.message} ${exc.stackTraceToString()}")
                        }
                    }
                }
                resultsSent = mediaSource.whenReady { successfullyInitialized ->
                    if (successfullyInitialized) {
                        browseTree.refresh()
                        // Don't sort root menu items - preserve order (Home first)
                        // Only sort playlists alphabetically
                        val items = browseTree[parentMediaId]
                        val children = if (parentMediaId == UAMP_BROWSABLE_ROOT) {
                            items?.map { item -> MediaItem(item.description, item.flag) }
                        } else {
                            items?.sortedBy { item -> item.title }?.map { item ->
                                MediaItem(item.description, item.flag)
                            }
                        } ?: listOf()
                        logger.info("Sending ${children.size} results for $parentMediaId")
                        result.sendResult(children)
                    } else {
                        logger.info("Failed to load results for $parentMediaId")
                        mediaSession.sendSessionEvent(NETWORK_FAILURE, null)
                        result.sendResult(null)
                    }
                }
            }

            // Artists root - load all artists
            parentMediaId == UAMP_ARTISTS_ROOT -> {
                serviceScope.launch {
                    try {
                        mediaSource.loadArtists()
                    } catch (exc: Exception) {
                        logger.error("error loading artists: ${exc.message}")
                    }
                }
                resultsSent = mediaSource.artistsWhenReady { artists ->
                    if (artists != null) {
                        val children = artists.sortedBy { it.title }.map { artist ->
                            val metadata = MediaMetadataCompat.Builder().from(artist).build()
                            MediaItem(metadata.description, MediaItem.FLAG_BROWSABLE)
                        }
                        logger.info("Sending ${children.size} artists")
                        result.sendResult(children)
                    } else {
                        mediaSession.sendSessionEvent(NETWORK_FAILURE, null)
                        result.sendResult(null)
                    }
                }
            }

            // Albums root - load all albums
            parentMediaId == UAMP_ALBUMS_ROOT -> {
                serviceScope.launch {
                    try {
                        mediaSource.loadAlbums()
                    } catch (exc: Exception) {
                        logger.error("error loading albums: ${exc.message}")
                    }
                }
                resultsSent = mediaSource.albumsWhenReady { albums ->
                    if (albums != null) {
                        val children = albums.sortedBy { it.title }.map { album ->
                            val metadata = MediaMetadataCompat.Builder().from(album).build()
                            MediaItem(metadata.description, MediaItem.FLAG_BROWSABLE)
                        }
                        logger.info("Sending ${children.size} albums")
                        result.sendResult(children)
                    } else {
                        mediaSession.sendSessionEvent(NETWORK_FAILURE, null)
                        result.sendResult(null)
                    }
                }
            }

            // Home root - show home menu items
            parentMediaId == UAMP_HOME_ROOT -> {
                val children = mutableListOf<MediaItem>()

                // Recently Played - no icon, Android Auto will show generic placeholder
                val recentlyPlayedMetadata = MediaMetadataCompat.Builder().apply {
                    putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, UAMP_RECENTLY_PLAYED_ROOT)
                    putString(MediaMetadataCompat.METADATA_KEY_TITLE, getString(R.string.recently_played_title))
                    putLong(MediaMetadataCompat.METADATA_KEY_BT_FOLDER_TYPE, MediaDescriptionCompat.BT_FOLDER_TYPE_PLAYLISTS.toLong())
                }.build()
                children += MediaItem(recentlyPlayedMetadata.description, MediaItem.FLAG_BROWSABLE)

                // Recently Added
                val recentlyAddedMetadata = MediaMetadataCompat.Builder().apply {
                    putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, UAMP_RECENTLY_ADDED_ROOT)
                    putString(MediaMetadataCompat.METADATA_KEY_TITLE, getString(R.string.recently_added_title))
                    putLong(MediaMetadataCompat.METADATA_KEY_BT_FOLDER_TYPE, MediaDescriptionCompat.BT_FOLDER_TYPE_PLAYLISTS.toLong())
                }.build()
                children += MediaItem(recentlyAddedMetadata.description, MediaItem.FLAG_BROWSABLE)

                logger.info("Sending ${children.size} home menu items")
                result.sendResult(children)
                resultsSent = true
            }

            // Recently Played - load recently played tracks
            parentMediaId == UAMP_RECENTLY_PLAYED_ROOT -> {
                serviceScope.launch {
                    try {
                        mediaSource.loadRecentlyPlayed()
                    } catch (exc: Exception) {
                        logger.error("error loading recently played: ${exc.message}")
                    }
                }
                resultsSent = mediaSource.recentlyPlayedWhenReady { tracks ->
                    if (tracks != null) {
                        val children = tracks.map { track ->
                            val metadata = MediaMetadataCompat.Builder().buildMeta(track, HOME_PREFIX + "recently_played")
                            MediaItem(metadata.description, MediaItem.FLAG_PLAYABLE)
                        }
                        logger.info("Sending ${children.size} recently played tracks")
                        result.sendResult(children)
                    } else {
                        mediaSession.sendSessionEvent(NETWORK_FAILURE, null)
                        result.sendResult(null)
                    }
                }
            }

            // Recently Added - load recently added albums
            parentMediaId == UAMP_RECENTLY_ADDED_ROOT -> {
                serviceScope.launch {
                    try {
                        mediaSource.loadRecentlyAdded()
                    } catch (exc: Exception) {
                        logger.error("error loading recently added: ${exc.message}")
                    }
                }
                resultsSent = mediaSource.recentlyAddedWhenReady { albums ->
                    if (albums != null) {
                        val children = albums.map { album ->
                            val metadata = MediaMetadataCompat.Builder().from(album).build()
                            MediaItem(metadata.description, MediaItem.FLAG_BROWSABLE)
                        }
                        logger.info("Sending ${children.size} recently added albums")
                        result.sendResult(children)
                    } else {
                        mediaSession.sendSessionEvent(NETWORK_FAILURE, null)
                        result.sendResult(null)
                    }
                }
            }

            // Artist detail - load albums for this artist
            parentMediaId.startsWith(ARTIST_PREFIX) -> {
                val artistId = parentMediaId.removePrefix(ARTIST_PREFIX)
                serviceScope.launch {
                    try {
                        mediaSource.loadArtistAlbums(artistId)
                    } catch (exc: Exception) {
                        logger.error("error loading artist albums: ${exc.message}")
                    }
                }
                resultsSent = mediaSource.artistAlbumsWhenReady(artistId) { albums ->
                    if (albums != null) {
                        val children = albums.sortedBy { it.title }.map { album ->
                            val metadata = MediaMetadataCompat.Builder().from(album).build()
                            MediaItem(metadata.description, MediaItem.FLAG_BROWSABLE)
                        }
                        logger.info("Sending ${children.size} albums for artist $artistId")
                        result.sendResult(children)
                    } else {
                        mediaSession.sendSessionEvent(NETWORK_FAILURE, null)
                        result.sendResult(null)
                    }
                }
            }

            // Album detail - load tracks for this album
            parentMediaId.startsWith(ALBUM_PREFIX) && !parentMediaId.contains("/") -> {
                val albumId = parentMediaId.removePrefix(ALBUM_PREFIX)
                serviceScope.launch {
                    try {
                        mediaSource.loadAlbumTracks(albumId)
                    } catch (exc: Exception) {
                        logger.error("error loading album tracks: ${exc.message}")
                    }
                }
                resultsSent = mediaSource.albumTracksWhenReady(albumId) { tracks ->
                    if (tracks != null) {
                        val children = tracks.map { track ->
                            val metadata = MediaMetadataCompat.Builder().fromAlbumTrack(track, albumId).build()
                            logger.debug("Album track mediaId: ${metadata.description.mediaId}, title: ${track.title}")
                            MediaItem(metadata.description, MediaItem.FLAG_PLAYABLE)
                        }
                        logger.info("Sending ${children.size} tracks for album $albumId")
                        result.sendResult(children)
                    } else {
                        mediaSession.sendSessionEvent(NETWORK_FAILURE, null)
                        result.sendResult(null)
                    }
                }
            }

            // Playlist handling (existing logic)
            else -> {
                var playlistId = parentMediaId
                var letterFilter: String? = null
                val splitMediaId = parentMediaId.split('/')
                if (splitMediaId.size == 2) {
                    playlistId = splitMediaId[0]

                    if (splitMediaId[1].startsWith("letter_")) {
                        letterFilter = splitMediaId[1].substring(7)
                    }
                }

                serviceScope.launch {
                    try {
                        mediaSource.loadPlaylist(playlistId)
                    } catch (exc: AuthorizationException) {
                        plexUtil.clearToken()
                        requireLogin()
                    } catch (exc: Exception) {
                        logger.error("error occurred while loading playlist ${playlistId}: ${exc.message} ${exc.stackTraceToString()}")
                    }
                }
                resultsSent = mediaSource.playlistWhenReady(playlistId) { plist ->
                    if (plist != null && letterFilter == null && plist.leafCount > PAGE_SIZE) {
                        // Group by first letter for large playlists
                        val plistItems = plist.loadedItems()
                        val letterGroups = mutableMapOf<String, Int>()

                        plistItems.forEach { item ->
                            if (item is Track) {
                                val firstChar = item.title.firstOrNull()?.uppercaseChar() ?: '#'
                                val letter = if (firstChar.isLetter()) firstChar.toString() else "#"
                                letterGroups[letter] = (letterGroups[letter] ?: 0) + 1
                            }
                        }

                        val children = mutableListOf<MediaItem>()
                        // Sort letters alphabetically, with # at the end
                        val sortedLetters = letterGroups.keys.sortedWith(compareBy {
                            if (it == "#") "ZZZ" else it
                        })

                        logger.info("Sending alphabetical playlist groups: ${sortedLetters.size} letters")
                        for (letter in sortedLetters) {
                            val count = letterGroups[letter] ?: 0
                            val id = "${plist.ratingKey}/letter_$letter"

                            children += MediaItem(
                                MediaMetadataCompat.Builder()
                                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
                                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, letter)
                                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "$count songs")
                                    .build().description,
                                MediaItem.FLAG_BROWSABLE
                            )
                        }
                        result.sendResult(children)
                    } else if (plist != null) {
                        val children = mutableListOf<MediaItem>()
                        var plistItems = plist.loadedItems()

                        if (letterFilter != null) {
                            // Filter by letter
                            plistItems = plistItems.filter { item ->
                                if (item is Track) {
                                    val firstChar = item.title.firstOrNull()?.uppercaseChar() ?: '#'
                                    val itemLetter = if (firstChar.isLetter()) firstChar.toString() else "#"
                                    itemLetter == letterFilter
                                } else false
                            }.toTypedArray()
                        }

                        plistItems.forEach { item ->
                            if (item !is Track) {
                                return@forEach
                            }
                            children += MediaItem(
                                (MediaMetadataCompat.Builder().buildMeta(item, playlistId)).description,
                                MediaItem.FLAG_PLAYABLE
                            )
                        }
                        logger.info("Sending playlist results: ${children.size}")
                        result.sendResult(children)
                    } else {
                        mediaSession.sendSessionEvent(NETWORK_FAILURE, null)
                        result.sendResult(null)
                    }
                }
            }
        }

        // If the results are not ready, the service must "detach" the results before
        // the method returns. After the source is ready, the lambda above will run,
        // and the caller will be notified that the results are ready.
        if (!resultsSent) {
            result.detach()
        }
    }

    private fun requireLogin() {
        val loginIntent = Intent(this, LoginActivity::class.java)
        val loginActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            loginIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val extras = Bundle().apply {
            putString(
                MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL,
                getString(R.string.error_login_button)
            )
            putParcelable(
                MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT,
                loginActivityPendingIntent
            )
        }
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_ERROR, 0, 0.0F)
                .setErrorMessage(
                    PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED,
                    "sign in required"
                )
                .setExtras(extras)
                .build()
        )
    }

    private fun isAuthenticated(): Boolean {
        plexToken = plexUtil.getToken()
        if (plexToken == null) {
            return false
        }

        return true
    }

    fun loginCommand(extras: Bundle, callback: ResultReceiver?): Boolean {
        return refreshCommand(extras, callback, true)
    }

    fun refreshCommand(
        extras: Bundle?,
        callback: ResultReceiver?,
        login: Boolean = false
    ): Boolean {
        isAuthenticated()
        checkInit(!login)
        if (login) {
            mediaSource.whenReady {
                serviceScope.launch {
                    delay(500L)
                    callback?.send(Activity.RESULT_OK, Bundle.EMPTY)
                }
            }
        }

        // Updated state (including clearing the error) now that the user has logged in.
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_NONE, 0, 0F)
                .build()
        )
        mediaSessionConnector.setCustomErrorMessage(null)
        mediaSessionConnector.invalidateMediaSessionPlaybackState()
        mediaSessionConnector.invalidateMediaSessionMetadata()
        mediaSessionConnector.invalidateMediaSessionQueue()
        this.notifyChildrenChanged(UAMP_BROWSABLE_ROOT)
        return true
    }

    /**
     * This is the code that causes UAMP to stop playing when swiping the activity away from
     * recents. The choice to do this is app specific. Some apps stop playback, while others allow
     * playback to continue and allow users to stop it with the notification.
     */
    override fun onTaskRemoved(rootIntent: Intent) {
        // set last song, ignore upcoming event
        saveLastSong()
        active = false
        super.onTaskRemoved(rootIntent)

        /**
         * By stopping playback, the player will transition to [Player.STATE_IDLE] triggering
         * [Player.EventListener.onPlayerStateChanged] to be called. This will cause the
         * notification to be hidden and trigger
         * [PlayerNotificationManager.NotificationListener.onNotificationCancelled] to be called.
         * The service will then remove itself as a foreground service, and will call
         * [stopSelf].
         */
        currentPlayer.stop()
        currentPlayer.clearMediaItems()
    }

    /**
     * Load the supplied list of songs and the song to play into the current player.
     */
    private fun preparePlaylist(
        metadataList: List<MediaMetadataCompat>,
        itemToPlay: MediaMetadataCompat?,
        playWhenReady: Boolean,
        playbackStartPositionMs: Long
    ) {
        // Since the playlist was probably based on some ordering (such as tracks
        // on an album), find which window index to play first so that the song the
        // user actually wants to hear plays first.
        val initialWindowIndex = if (itemToPlay == null) 0 else {
            var idx = 0
            for (i in metadataList.indices) {
                if (metadataList[i].id == itemToPlay.id) {
                    idx = i
                    break
                }
            }
            idx
        }
        currentPlaylistItems = metadataList

        currentPlayer.playWhenReady = playWhenReady
        currentPlayer.stop()
        // Set playlist and prepare.
        logger.info("starting playback at position $initialWindowIndex, $playbackStartPositionMs")
        currentPlayer.setMediaItems(
            metadataList.map { it.toMediaItem() }, initialWindowIndex, playbackStartPositionMs
        )
        currentPlayer.prepare()

        // Prefetch next tracks using ExoPlayer's DownloadManager
        prefetchNextTracks(metadataList, initialWindowIndex, 5)

        active = true
        wasActive = true
    }

    /**
     * Prefetch next N tracks using ExoPlayer's DownloadManager.
     * Respects shuffle mode by using the player's timeline to determine the actual next tracks.
     */
    private fun prefetchNextTracks(
        metadataList: List<MediaMetadataCompat>,
        currentIndex: Int,
        count: Int = 5
    ) {
        if (metadataList.isEmpty() || !this::currentPlayer.isInitialized) return

        val timeline = currentPlayer.currentTimeline
        if (timeline.isEmpty) return

        val currentWindowIndex = currentPlayer.currentMediaItemIndex
        val indicesToPrefetch = mutableListOf<Int>()

        // Get the next N windows in playback order (respects shuffle mode)
        var nextWindowIndex = timeline.getNextWindowIndex(
            currentWindowIndex,
            currentPlayer.repeatMode,
            currentPlayer.shuffleModeEnabled
        )

        var remaining = count
        while (nextWindowIndex != C.INDEX_UNSET && remaining > 0) {
            indicesToPrefetch.add(nextWindowIndex)
            remaining--

            // Get the next window after this one
            nextWindowIndex = timeline.getNextWindowIndex(
                nextWindowIndex,
                currentPlayer.repeatMode,
                currentPlayer.shuffleModeEnabled
            )

            // Prevent infinite loop in case of repeat mode
            if (indicesToPrefetch.size >= min(count, timeline.windowCount)) {
                break
            }
        }

        // Prefetch the tracks in the order they will actually play
        for (windowIndex in indicesToPrefetch) {
            if (windowIndex >= 0 && windowIndex < metadataList.size) {
                val meta = metadataList[windowIndex]
                val uri = meta.description.mediaUri ?: continue
                val id = meta.description.mediaId ?: uri.toString()

                try {
                    // Create download request for the track
                    val downloadRequest = DownloadRequest.Builder(id, uri)
                        .build()

                    // Add to download manager (it will cache the content)
                    downloadManager.addDownload(downloadRequest)
                    downloadManager.resumeDownloads()
                    logger.debug("Prefetching track at index $windowIndex: $id (shuffle: ${currentPlayer.shuffleModeEnabled})")
                } catch (e: Exception) {
                    logger.error("Failed to add download for $id: ${e.message}")
                }
            }
        }
    }

    private fun switchToPlayer(previousPlayer: Player?, newPlayer: Player) {
        if (previousPlayer == newPlayer) {
            return
        }
        currentPlayer = newPlayer
        mediaSessionConnector.setPlayer(newPlayer)
        previousPlayer?.stop()
        previousPlayer?.clearMediaItems()
    }

    private inner class UampQueueNavigator(
        mediaSession: MediaSessionCompat
    ) : TimelineQueueNavigator(mediaSession) {
        override fun getMediaDescription(player: Player, windowIndex: Int): MediaDescriptionCompat {
            if (windowIndex < currentPlaylistItems.size) {
                return currentPlaylistItems[windowIndex].description
            }
            return MediaDescriptionCompat.Builder().build()
        }
    }

    private inner class UampPlaybackPreparer : MediaSessionConnector.PlaybackPreparer {

        /**
         * UAMP supports preparing (and playing) from search, as well as media ID, so those
         * capabilities are declared here.
         */
        override fun getSupportedPrepareActions(): Long = PlaybackStateCompat.ACTION_PREPARE or
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID

        override fun onPrepare(playWhenReady: Boolean) {
            if (!isAuthenticated()) {
                logger.info("Not logged in")
                requireLogin()
                return
            }
            checkInit()
            logger.debug("onPrepare")
            serviceScope.launch {
                if (currentPlayer.isPlaying) {
                    logger.info("Skipping prepare since already playing")
                    return@launch
                }

                val lastSong = AndroidStorage.getLastSong(applicationContext)
                if (lastSong == null) {
                    logger.warn("Last song not found")
                    return@launch
                }
                var position = AndroidStorage.getLastPosition(applicationContext)
                if (position == null)
                    position = 0
                val extras = Bundle()
                extras.putLong(MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS, position)
                //logger.info("Triggering prepareFromMediaId: $lastSong, $position")
                onPrepareFromMediaId(lastSong, playWhenReady, extras)
            }
        }

        override fun onPrepareFromMediaId(
            prepareId: String,
            playWhenReady: Boolean,
            extras: Bundle?
        ) {
            logger.info("onPrepareFromMediaId called with: prepareId=$prepareId, playWhenReady=$playWhenReady")
            val idSplit = prepareId.split('/')
            if (idSplit.size != 2) {
                logger.error("media id doesn't include parent id: $prepareId")
                return
            }

            val parentId = idSplit[0]
            val mediaId = idSplit[1]

            // Check if this is an album track (starts with album_ prefix)
            if (parentId.startsWith(ALBUM_PREFIX)) {
                val albumId = parentId.removePrefix(ALBUM_PREFIX)
                serviceScope.launch {
                    try {
                        mediaSource.loadAlbumTracks(albumId)
                    } catch (exc: Exception) {
                        logger.error("Failed to load album tracks: $albumId: ${exc.message}")
                    }
                }

                mediaSource.albumTracksWhenReady(albumId) { tracks ->
                    serviceScope.launch {
                        if (tracks == null) {
                            logger.error("Failed to load album tracks: $albumId")
                            return@launch
                        }

                        val itemToPlay = tracks.find { track ->
                            track.ratingKey.toString() == mediaId
                        }
                        if (itemToPlay == null) {
                            logger.warn("Track not found in album: MediaID=$mediaId")
                        } else {
                            val playbackStartPositionMs =
                                extras?.getLong(
                                    MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS,
                                    C.TIME_UNSET
                                ) ?: C.TIME_UNSET

                            preparePlaylist(
                                buildAlbumPlaylist(tracks, albumId),
                                MediaMetadataCompat.Builder().fromAlbumTrack(itemToPlay, albumId).build(),
                                playWhenReady,
                                playbackStartPositionMs
                            )
                        }
                    }
                }
            } else if (parentId.startsWith(HOME_PREFIX)) {
                // Home section playback (Recently Played, On Deck)
                val homeSection = parentId.removePrefix(HOME_PREFIX)
                logger.info("Playing from home section: $homeSection, track: $mediaId")

                when (homeSection) {
                    "recently_played" -> {
                        serviceScope.launch {
                            try {
                                mediaSource.loadRecentlyPlayed()
                            } catch (exc: Exception) {
                                logger.error("Failed to load recently played: ${exc.message}")
                            }
                        }
                        mediaSource.recentlyPlayedWhenReady { tracks ->
                            serviceScope.launch {
                                if (tracks == null) {
                                    logger.error("Failed to load recently played tracks")
                                    return@launch
                                }
                                val itemToPlay = tracks.find { it.ratingKey.toString() == mediaId }
                                if (itemToPlay == null) {
                                    logger.warn("Track not found in recently played: $mediaId")
                                } else {
                                    val playbackStartPositionMs = extras?.getLong(
                                        MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS,
                                        C.TIME_UNSET
                                    ) ?: C.TIME_UNSET

                                    preparePlaylist(
                                        buildHomePlaylist(tracks, parentId),
                                        MediaMetadataCompat.Builder().buildMeta(itemToPlay, parentId),
                                        playWhenReady,
                                        playbackStartPositionMs
                                    )
                                }
                            }
                        }
                    }
                    "on_deck" -> {
                        serviceScope.launch {
                            try {
                                mediaSource.loadOnDeck()
                            } catch (exc: Exception) {
                                logger.error("Failed to load on deck: ${exc.message}")
                            }
                        }
                        mediaSource.onDeckWhenReady { tracks ->
                            serviceScope.launch {
                                if (tracks == null) {
                                    logger.error("Failed to load on deck tracks")
                                    return@launch
                                }
                                val itemToPlay = tracks.find { it.ratingKey.toString() == mediaId }
                                if (itemToPlay == null) {
                                    logger.warn("Track not found in on deck: $mediaId")
                                } else {
                                    val playbackStartPositionMs = extras?.getLong(
                                        MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS,
                                        C.TIME_UNSET
                                    ) ?: C.TIME_UNSET

                                    preparePlaylist(
                                        buildHomePlaylist(tracks, parentId),
                                        MediaMetadataCompat.Builder().buildMeta(itemToPlay, parentId),
                                        playWhenReady,
                                        playbackStartPositionMs
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        logger.warn("Unknown home section: $homeSection")
                    }
                }
            } else {
                // Playlist-based playback (existing logic)
                val playlistId = parentId

                serviceScope.launch {
                    try {
                        mediaSource.loadPlaylist(playlistId)
                    } catch (exc: Exception) {
                        logger.error("Failed to find playlist: $playlistId: ${exc.message}, ${exc.printStackTrace()}")
                    }
                }

                mediaSource.playlistWhenReady(playlistId) { plist ->
                    serviceScope.launch {
                        val currPlaylist = plist?.items()
                        if (currPlaylist == null) {
                            logger.error("Failed to load playlist: $playlistId")
                            return@launch
                        }

                        val itemToPlay = currPlaylist.find { item ->
                            if (item !is Track) {
                                logger.warn("Skipping unknown playlist item: $item")
                                return@find false
                            }
                            item.ratingKey.toString() == mediaId
                        }
                        if (itemToPlay == null) {
                            logger.warn("Content not found: MediaID=$mediaId")
                        } else {
                            val playbackStartPositionMs =
                                extras?.getLong(
                                    MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS,
                                    C.TIME_UNSET
                                ) ?: C.TIME_UNSET

                            preparePlaylist(
                                buildPlaylist(currPlaylist, playlistId),
                                MediaMetadataCompat.Builder().buildMeta(itemToPlay, playlistId),
                                playWhenReady,
                                playbackStartPositionMs
                            )
                        }
                    }
                }
            }
        }

        override fun onPrepareFromSearch(query: String, playWhenReady: Boolean, extras: Bundle?) {
            logger.error("onPrepareFromSearch: $query")
        }

        override fun onPrepareFromUri(uri: Uri, playWhenReady: Boolean, extras: Bundle?) {
            logger.error("onPrepareFromUri: $uri")
        }

        override fun onCommand(
            player: Player,
            command: String,
            extras: Bundle?,
            cb: ResultReceiver?
        ) = false

        /**
         * Builds a playlist based on a [MediaMetadataCompat].
         *
         * @param item Item to base the playlist on.
         * @return a [List] of [MediaMetadataCompat] objects representing a playlist.
         */
        private fun buildPlaylist(
            playlist: Array<us.tiba.plexapi.media.MediaItem>,
            playlistId: String
        ): List<MediaMetadataCompat> {
            return playlist.map { MediaMetadataCompat.Builder().buildMeta(it, playlistId) }
        }

        /**
         * Builds a playlist from album tracks.
         *
         * @param tracks List of tracks from an album.
         * @param albumId The album's rating key.
         * @return a [List] of [MediaMetadataCompat] objects representing the album tracks.
         */
        private fun buildAlbumPlaylist(
            tracks: List<Track>,
            albumId: String
        ): List<MediaMetadataCompat> {
            return tracks.map { track ->
                MediaMetadataCompat.Builder().fromAlbumTrack(track, albumId).build()
            }
        }

        /**
         * Builds a playlist from home section tracks (recently played, on deck).
         *
         * @param tracks List of tracks from a home section.
         * @param sectionId The home section identifier (e.g., "home_recently_played").
         * @return a [List] of [MediaMetadataCompat] objects representing the tracks.
         */
        private fun buildHomePlaylist(
            tracks: List<Track>,
            sectionId: String
        ): List<MediaMetadataCompat> {
            return tracks.map { track ->
                MediaMetadataCompat.Builder().buildMeta(track, sectionId)
            }
        }
    }

    /**
     * Listen for events from ExoPlayer.
     */
    private inner class PlayerEventListener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            //logger.info("Position: ${currentPlayer.currentPosition}")
            if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)
                || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
                || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)
            ) {
                saveLastSong()
                try {
                    prefetchNextTracks(currentPlaylistItems, currentPlayer.currentMediaItemIndex, 5)
                } catch (t: Throwable) {
                    logger.error("prefetch failed: ${t.message}")
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            super.onPositionDiscontinuity(oldPosition, newPosition, reason)

            //logger.info("position discontinuity: ${oldPosition.positionMs}, ${newPosition.positionMs}")
            previousPosition = oldPosition.positionMs
        }

        override fun onPlayerError(error: PlaybackException) {
            var message = "player error";
            logger.error("Player error: " + error.errorCodeName + " (" + error.errorCode + ")");
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                || error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            ) {
                message = "media not found";
            }
            Toast.makeText(
                applicationContext,
                message,
                Toast.LENGTH_LONG
            ).show()
        }


    }

    private fun saveLastSong() {
        if (!active || currentPlayer.mediaItemCount == 0) {
            if (wasActive) {
                serviceScope.launch {
                    //logger.info("Set last position to previous $previousPosition")
                    AndroidStorage.setLastPosition(previousPosition, applicationContext)
                }
            }
            return
        }
        currentMediaItemIndex = if (currentPlaylistItems.isNotEmpty()) {
            Util.constrainValue(
                currentPlayer.currentMediaItemIndex,
                /* min = */ 0,
                /* max = */ currentPlaylistItems.size - 1
            )
        } else 0

        if (currentPlaylistItems.isNotEmpty()) {
            val id = currentPlaylistItems[currentMediaItemIndex].id
            if (id != null) {
                serviceScope.launch {
                    //logger.info("Setting last song: ${id}")
                    AndroidStorage.setLastSong(id, applicationContext)
                    //logger.info("Set last position ${currentPlayer.currentPosition}")
                    AndroidStorage.setLastPosition(
                        currentPlayer.currentPosition,
                        applicationContext
                    )
                }
            }
        }
    }
}

const val NETWORK_FAILURE = "us.tiba.plexamp.NETWORK_FAILURE"

/** Content styling constants */
private const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
private const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
private const val CONTENT_STYLE_LIST = 1
private const val CONTENT_STYLE_GRID = 2

const val MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS = "playback_start_position_ms"

const val MEDIA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"