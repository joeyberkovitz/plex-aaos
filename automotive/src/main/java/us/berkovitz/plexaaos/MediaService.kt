@file:OptIn(UnstableApi::class)

package us.berkovitz.plexaaos

import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT
import androidx.media3.session.MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import us.berkovitz.plexaaos.extensions.id
import us.berkovitz.plexaaos.library.BrowseTree
import us.berkovitz.plexaaos.library.MusicSource
import us.berkovitz.plexaaos.library.PlexSource
import us.berkovitz.plexaaos.library.UAMP_BROWSABLE_ROOT
import us.berkovitz.plexaaos.library.UAMP_PLAYLISTS_ROOT
import us.berkovitz.plexaaos.library.buildMeta
import us.berkovitz.plexapi.media.Track
import us.berkovitz.plexapi.myplex.AuthorizationException
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.min

class PlexMediaService : MediaLibraryService() {
    companion object {
        val logger = PlexLoggerFactory.loggerFor(PlexMediaService::class)
        val PAGE_SIZE = BrowseTree.PAGE_SIZE
    }


    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    lateinit var mediaLibrarySession: MediaLibrarySession
    lateinit var player: PlexPlayer
    lateinit var plexUtil: PlexUtil
    private var plexToken: String? = null
    private lateinit var mediaSource: MusicSource
    private val browseTree: BrowseTree by lazy {
        BrowseTree(applicationContext, mediaSource)
    }

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
            download: Download,
            finalException: Exception?
        ) {
            val logger = PlexLoggerFactory.loggerFor(android.app.DownloadManager::class)
            when (download.state) {
                Download.STATE_COMPLETED -> {
                    logger.debug("Download completed: ${download.request.id}")
                }
                Download.STATE_FAILED -> {
                    logger.error("Download failed: ${download.request.id}, error: ${finalException?.message}")
                }
                Download.STATE_DOWNLOADING -> {
                    val progress = download.percentDownloaded
                    logger.debug("Downloading: ${download.request.id}, progress: $progress%")
                }
                Download.STATE_QUEUED -> {
                    logger.debug("Download queued: ${download.request.id}")
                }
                Download.STATE_REMOVING -> {
                    logger.debug("Download removing: ${download.request.id}")
                }
                Download.STATE_RESTARTING -> {
                    logger.debug("Download restarting: ${download.request.id}")
                }
                Download.STATE_STOPPED -> {
                    logger.debug("Download stopped: ${download.request.id}")
                }
            }
        }

        override fun onDownloadRemoved(
            downloadManager: DownloadManager,
            download: Download
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

    private val uAmpAudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()
    private val playerListener = PlayerEventListener()

    override fun onCreate() {
        super.onCreate()

        AndroidPlexApi.initPlexApi(this)
        plexUtil = PlexUtil(this)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        player = PlexPlayer(
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
                .setLoadControl (
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            10*1000,
                            5*60*1000,
                            10*1000,
                            10*1000,
                        )
                        .build()
                )
                .build().apply {
                setAudioAttributes(uAmpAudioAttributes, true)
                setHandleAudioBecomingNoisy(true)
                addListener(playerListener)
            }
        )

        mediaLibrarySession = with(
            MediaLibrarySession.Builder(
                this, player, MediaLibraryCallback()
            )
        ) {
            setId(packageName)
            packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
                setSessionActivity(
                    PendingIntent.getActivity(
                        /* context= */ this@PlexMediaService,
                        /* requestCode= */ 0,
                        sessionIntent,
                        FLAG_IMMUTABLE
                    )
                )
            }
            build()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    private fun isAuthenticated(): Boolean {
        plexToken = plexUtil.getToken()
        if (plexToken == null) {
            return false
        }

        return true
    }

    fun loginCommand(future: SettableFuture<SessionResult>): Boolean {
        return refreshCommand(future, true)
    }

    fun logoutCommand(future: SettableFuture<SessionResult>): Boolean {
        plexUtil.clearToken()
        requireLogin()
        return refreshCommand(future)
    }

    fun refreshCommand(
        future: SettableFuture<SessionResult>,
        login: Boolean = false
    ): Boolean {
        if (!isAuthenticated()) {
            future.set(
                SessionResult(
                    SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
                    getExpiredAuthenticationResolutionExtras()
                )
            )
            return false
        }

        checkInit(!login)
        if (login) {
            mediaSource.whenReady {
                future.set(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        } else {
            future.set(SessionResult(SessionResult.RESULT_SUCCESS))
        }

//        // Updated state (including clearing the error) now that the user has logged in.
//        mediaSession.setPlaybackState(
//            PlaybackStateCompat.Builder()
//                .setState(PlaybackStateCompat.STATE_NONE, 0, 0F)
//                .build()
//        )
//        mediaSessionConnector.setCustomErrorMessage(null)
//        mediaSessionConnector.invalidateMediaSessionPlaybackState()
//        mediaSessionConnector.invalidateMediaSessionMetadata()
//        mediaSessionConnector.invalidateMediaSessionQueue()
//        this.notifyChildrenChanged(UAMP_BROWSABLE_ROOT)
        return true
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
            } catch (exc: AuthorizationException) {
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

    private fun requireLogin() {
        val loginIntent = Intent(this, LoginActivity::class.java)
        val loginActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            loginIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        mediaLibrarySession.setPlaybackException(
            PlaybackException(
                "sign in required",
                null,
                PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED,
                getExpiredAuthenticationResolutionExtras()
            )
        )
    }

    private fun getExpiredAuthenticationResolutionExtras(): Bundle {
        return Bundle().also {
            it.putString(
                EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT,
                getString(R.string.error_login_button)
            )
            val signInIntent = Intent(this, LoginActivity::class.java)
            val flags = PendingIntent.FLAG_IMMUTABLE
            it.putParcelable(
                EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT,
                PendingIntent.getActivity(this, /* requestCode= */ 0, signInIntent, flags)
            )
        }
    }

    fun onLoadChildren(
        parentMediaId: String,
        result: SettableFuture<LibraryResult<ImmutableList<MediaItem>>>
    ) {
        logger.info("onLoadChildren: $parentMediaId")

        if (!isAuthenticated()) {
            logger.info("not authenticated")
            requireLogin()
            return
        }
        checkInit()

        if (parentMediaId == UAMP_PLAYLISTS_ROOT || parentMediaId == UAMP_BROWSABLE_ROOT) {
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
            // If the media source is ready, the results will be set synchronously here.
            mediaSource.whenReady { successfullyInitialized ->
                if (successfullyInitialized) {
                    browseTree.refresh()
                    val children =
                        browseTree[parentMediaId]?.sortedBy { item -> item.mediaMetadata.title.toString() }
                            ?: listOf()
                    logger.info("Sending ${children.size} results for $parentMediaId")
                    result.set(LibraryResult.ofItemList(children, null))
                } else {
                    logger.info("Failed to load results for $parentMediaId")
                    result.set(LibraryResult.ofError(SessionError.ERROR_IO))
                }
            }
        } else {
            val playlistFuture = loadPlaylist(parentMediaId)
            playlistFuture.addListener({
                try {
                    val res = playlistFuture.get()
                    result.set(LibraryResult.ofItemList(res, null))
                } catch (exc: Exception) {
                    logger.info("Failed to load results for $parentMediaId")
                    result.set(LibraryResult.ofError(SessionError.ERROR_IO))
                }
            }, MoreExecutors.directExecutor())
        }
    }

    fun loadPlaylist(parentMediaId: String): ListenableFuture<List<MediaItem>> {
        val future = SettableFuture.create<List<MediaItem>>()
        val emptyRes = emptyList<MediaItem>()

        var playlistId = parentMediaId
        var pageNum: Int? = null
        val splitMediaId = parentMediaId.split('/')
        if (splitMediaId.size >= 2) {
            playlistId = splitMediaId[0]

            if (splitMediaId[1].startsWith("page_")) {
                pageNum = splitMediaId[1].substring(5).toIntOrNull()
            }
        }

        if (parentMediaId == UAMP_PLAYLISTS_ROOT || parentMediaId == UAMP_BROWSABLE_ROOT) {
            future.setException(Exception("not a media item"))
            return future
        }

        serviceScope.launch {
            logger.info("loading plist ${playlistId}")
            try {
                mediaSource.loadPlaylist(playlistId)
            } catch (exc: AuthorizationException) {
                logger.error("auth err: ${exc}")
                plexUtil.clearToken()
                requireLogin()
                future.setException(exc)
            } catch (exc: Exception) {
                logger.error("error occurred while loading playlist ${playlistId}: ${exc.message} ${exc.stackTraceToString()}")
                future.setException(exc)
            }
        }

        mediaSource.playlistWhenReady(playlistId) { plist ->
            logger.error("plist when ready")
            browseTree.storePlaylist(plist)
            if (plist != null && pageNum == null && plist.leafCount > PAGE_SIZE) {
                val numPages = ceil(plist.leafCount.toDouble() / PAGE_SIZE).toInt()
                val children = mutableListOf<MediaItem>()
                logger.info("Sending paginated playlist results: $numPages")
                for (i in 0 until numPages) {
                    val start = (i * PAGE_SIZE) + 1
                    val end = min(((i + 1) * PAGE_SIZE), plist.leafCount.toInt())
                    val id = "${plist.ratingKey}/page_$i"

                    children += MediaItem.Builder().apply {
                        setMediaId(id)
                        setMediaMetadata(MediaMetadata.Builder().apply {
                            setTitle("$start - $end")
                            setIsBrowsable(true)
                            setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
                            setIsPlayable(false)
                        }.build())
                    }.build()
                }
                future.set(children)
            } else if (plist != null) {
                val children = mutableListOf<MediaItem>()
                var plistItems = plist.loadedItems()
                if (pageNum != null) {
                    val totalItems = plistItems.size
                    val startIndex = pageNum * PAGE_SIZE
                    val endExclusive = min((pageNum + 1) * PAGE_SIZE, totalItems)

                    plistItems = if (startIndex >= totalItems) {
                        emptyArray()
                    } else {
                        plistItems.sliceArray(IntRange(startIndex, endExclusive - 1))
                    }
                }
                plistItems.forEach { item ->
                    if (item !is Track) {
                        return@forEach
                    }
                    children += MediaItem.Builder().buildMeta(item, playlistId, pageNum?.toString())
                }
                logger.info("Sending playlist results: ${children.size} ${playlistId}")
                future.set(children)
            } else {
                future.set(emptyRes)
            }
        }

        return future
    }


    private inner class MediaLibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)
            val sessionCommands =
                connectionResult.availableSessionCommands
                    .buildUpon()
                    .add(SessionCommand(LOGIN, Bundle()))
                    .add(SessionCommand(LOGOUT, Bundle()))
                    .add(SessionCommand(REFRESH, Bundle()))
                    .add(SessionCommand(SHUFFLE, Bundle()))
                    .add(SessionCommand(REPEAT, Bundle()))
                    .build()

            serviceScope.launch {
                // load shuffle/repeat modes from storage
                val shuffleMode = AndroidStorage.getShuffleEnabled(applicationContext)
                val repeatMode = AndroidStorage.getRepeatMode(applicationContext)
                player.shuffleModeEnabled = shuffleMode
                player.repeatMode = repeatMode
                buildUI(session)
            }

            return MediaSession.ConnectionResult.accept(
                sessionCommands, connectionResult.availablePlayerCommands
            )
        }

        fun nextRepeatMode(mode: Int): Int = when (mode) {
            REPEAT_MODE_OFF -> REPEAT_MODE_ALL
            REPEAT_MODE_ALL -> REPEAT_MODE_ONE
            REPEAT_MODE_ONE -> REPEAT_MODE_OFF
            else -> REPEAT_MODE_OFF
        }

        fun buildUI(session: MediaSession) {
            val shuffleIcon = if (player.shuffleModeEnabled) {
                CommandButton.ICON_SHUFFLE_ON
            } else {
                CommandButton.ICON_SHUFFLE_OFF
            }

            val shuffleButton = CommandButton.Builder(shuffleIcon)
                .setDisplayName("shuffle")
                .setSessionCommand(SessionCommand(SHUFFLE, Bundle()))
                .build()

            val repeatIcon = when (player.repeatMode) {
                REPEAT_MODE_OFF -> CommandButton.ICON_REPEAT_OFF
                REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                else -> CommandButton.ICON_REPEAT_OFF
            }

            val repeatButton = CommandButton.Builder(repeatIcon)
                .setDisplayName("repeat")
                .setSessionCommand(SessionCommand(REPEAT, Bundle()))
                .build()
            session.setMediaButtonPreferences(listOf(shuffleButton, repeatButton))
        }

        fun setRepeatMode(future: SettableFuture<SessionResult>) {
            val nextRepeat = nextRepeatMode(player.repeatMode)

            player.repeatMode = nextRepeat

            serviceScope.launch {
                AndroidStorage.setRepeatMode(player.repeatMode, applicationContext)
            }
            buildUI(mediaLibrarySession)
            future.set(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        fun setShuffleMode(future: SettableFuture<SessionResult>) {
            player.shuffleModeEnabled = !player.shuffleModeEnabled
            serviceScope.launch {
                AndroidStorage.setShuffleEnabled(player.shuffleModeEnabled, applicationContext)
            }
            buildUI(mediaLibrarySession)
            future.set(SessionResult(SessionResult.RESULT_SUCCESS))
        }


        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val emptyRes = MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
            if (!isAuthenticated()) {
                return Futures.immediateFuture(emptyRes)
            }

            logger.info("onPlaybackResumption")
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()

            serviceScope.launch {
                val lastSong = AndroidStorage.getLastSong(applicationContext) ?: ""
                var lastPos = AndroidStorage.getLastPosition(applicationContext) ?: 0

                val idSplit = lastSong.split('/')
                if (idSplit.size < 2) {
                    logger.error("media id doesn't include parent id: $lastSong")
                    future.set(emptyRes)
                    return@launch
                }

                val playlistId = idSplit[0]

                val playlistFuture = loadPlaylist(lastSong)
                playlistFuture.addListener({
                    try {
                        val items = playlistFuture.get()

                        var itemIdx = items.indexOfFirst { it.mediaId == lastSong }
                        if (itemIdx < 0) {
                            itemIdx = 0
                            lastPos = 0
                        }

                        logger.info("Sending resumption playlist results: ${items.size} ${playlistId} ${itemIdx} ${lastPos}")
                        future.set(
                            MediaSession.MediaItemsWithStartPosition(
                                items,
                                itemIdx,
                                lastPos
                            )
                        )
                    } catch (exc: Exception) {
                        logger.info("Failed to load results for $playlistId: ${exc.message}")
                        future.set(emptyRes)
                    }
                }, MoreExecutors.directExecutor())
            }

            return future
        }

        @OptIn(UnstableApi::class)
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            var future = SettableFuture.create<SessionResult>()
            when (customCommand.customAction) {
                LOGIN -> {
                    loginCommand(future)
                    return future
                }

                LOGOUT -> {
                    logoutCommand(future)
                    return future
                }

                REFRESH -> {
                    refreshCommand(future)
                    return future
                }

                REPEAT -> {
                    setRepeatMode(future)
                    return future
                }

                SHUFFLE -> {
                    setShuffleMode(future)
                    return future
                }

                else -> {
                    return Futures.immediateFuture(
                        SessionResult(
                            SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
                            getExpiredAuthenticationResolutionExtras()
                        )
                    )
                }
            }
        }

        @OptIn(UnstableApi::class)
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootExtras = Bundle().apply {
                putBoolean(MEDIA_SEARCH_SUPPORTED, false)
                putBoolean(CONTENT_STYLE_SUPPORTED, true)
                putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID)
                putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
            }
            val libraryParams = LibraryParams.Builder().setExtras(rootExtras).build()

            val rootMediaItem = MediaItem.Builder()
                .setMediaId(UAMP_BROWSABLE_ROOT)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
                        .setIsPlayable(false)
                        .build()
                )
                .build()

            return Futures.immediateFuture(LibraryResult.ofItem(rootMediaItem, libraryParams))
        }

        @OptIn(UnstableApi::class)
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return if (isAuthenticated()) {
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                onLoadChildren(parentId, future)
                return future
            } else {
                Futures.immediateFuture(
                    LibraryResult.ofError(
                        SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
                        LibraryParams.Builder()
                            .setExtras(getExpiredAuthenticationResolutionExtras()).build()
                    )
                )
            }
        }

        @OptIn(UnstableApi::class)
        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (!isAuthenticated()) {
                return Futures.immediateFuture(
                    LibraryResult.ofError(
                        SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
                        LibraryParams.Builder()
                            .setExtras(getExpiredAuthenticationResolutionExtras()).build()
                    )
                )
            }

            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            logger.error("onPrepareFromMediaId: $mediaId")
            if (mediaId == UAMP_PLAYLISTS_ROOT || mediaId == UAMP_BROWSABLE_ROOT) {
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_IO))
            }

            val itemsFuture = loadPlaylist(mediaId)
            itemsFuture.addListener({
                var itemToPlay: MediaItem? = null

                try {
                    logger.info("waiting for results")
                    val items = itemsFuture.get()
                    logger.info("got results: ${items.size}")

                    itemToPlay = items.find { item ->
                        item.mediaId == mediaId
                    }
                } catch (exc: Exception) {
                    logger.error("failed to get items: ${exc.message}")
                }

                if (itemToPlay == null) {
                    logger.warn("Content not found: MediaID=$mediaId")
                    // TODO: Notify caller of the error.
                    future.set(LibraryResult.ofError(SessionError.ERROR_IO))
                } else {
                    future.set(
                        LibraryResult.ofItem(
                            itemToPlay,
                            LibraryParams.Builder().build()
                        )
                    )
                }
            }, MoreExecutors.directExecutor())

            return future
        }

        var items: List<MediaItem> = emptyList()

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            _startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // https://github.com/androidx/media/issues/156
            // media3 kind of sucks because AAOS still uses media1 under the hood
            // so we have to hack around that:
            //      we lose the media info - and have to refetch it from the browseTree
            //      we're given a mediaId but if you want the player to track a playlist, you have to build a playlist
            items = mediaItems.map { browseTree.getByID(it.mediaId) ?: MediaItem.Builder().build() }

            val splitMediaId = mediaItems[0].mediaId.split('/')
            val playlistId = splitMediaId[0]
            items = browseTree[playlistId] ?: emptyList()
            val startIndex = items.indexOfFirst { it.mediaId == mediaItems[0].mediaId }

            // Prefetch next tracks using ExoPlayer's DownloadManager
            prefetchNextTracks( 5)

            return super.onSetMediaItems(
                mediaSession,
                controller,
                items,
                startIndex,
                startPositionMs
            )
        }
    }

    /**
     * Prefetch next N tracks using ExoPlayer's DownloadManager.
     * Respects shuffle mode by using the player's timeline to determine the actual next tracks.
     */
    private fun prefetchNextTracks(count: Int = 5) {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return

        val currentWindowIndex = player.currentMediaItemIndex
        val indicesToPrefetch = mutableListOf<Int>()

        // Get the next N windows in playback order (respects shuffle mode)
        var nextWindowIndex = timeline.getNextWindowIndex(
            currentWindowIndex,
            player.repeatMode,
            player.shuffleModeEnabled
        )

        var remaining = count
        while (nextWindowIndex != C.INDEX_UNSET && remaining > 0) {
            indicesToPrefetch.add(nextWindowIndex)
            remaining--

            // Get the next window after this one
            nextWindowIndex = timeline.getNextWindowIndex(
                nextWindowIndex,
                player.repeatMode,
                player.shuffleModeEnabled
            )

            // Prevent infinite loop in case of repeat mode
            if (indicesToPrefetch.size >= min(count, timeline.windowCount)) {
                break
            }
        }

        // Prefetch the tracks in the order they will actually play
        for (windowIndex in indicesToPrefetch) {
            if (windowIndex >= 0 && windowIndex < player.mediaItemCount) {
                val meta = player.getMediaItemAt(windowIndex)
                val uri = meta.localConfiguration?.uri ?: continue
                val id = meta.mediaId

                try {
                    // Create download request for the track
                    val downloadRequest = DownloadRequest.Builder(id, uri)
                        .build()

                    // Add to download manager (it will cache the content)
                    downloadManager.addDownload(downloadRequest)
                    downloadManager.resumeDownloads()
                    logger.debug("Prefetching track at index $windowIndex: $id (shuffle: ${player.shuffleModeEnabled})")
                } catch (e: Exception) {
                    logger.error("Failed to add download for $id: ${e.message}")
                }
            }
        }
    }

    /** Called when swiping the activity away from recents. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // The choice what to do here is app specific. Some apps stop playback, while others allow
        // playback to continue and allow users to stop it with the notification.
        releaseMediaSession()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaSession()
    }


    private fun releaseMediaSession() {
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

        mediaLibrarySession.run {
            release()
            if (player.playbackState != Player.STATE_IDLE) {
                //player.removeListener(playerListener)
                player.release()
            }
        }
        // Cancel coroutines when the service is going away.
        serviceJob.cancel()
    }


    /**
     * Listen for events from ExoPlayer.
     */
    private inner class PlayerEventListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)
            saveLastSong(null, null)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            super.onMediaItemTransition(mediaItem, reason)
            saveLastSong(mediaItem?.mediaId, 0)

            try {
                prefetchNextTracks(5)
            } catch (t: Throwable) {
                logger.error("prefetch failed: ${t.message}")
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            super.onPositionDiscontinuity(oldPosition, newPosition, reason)
            saveLastSong(newPosition.mediaItem?.mediaId, newPosition.positionMs)
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

    private fun saveLastSong(mediaIdIn: String?, positionIn: Long?) {
        val mediaId = mediaIdIn ?: player.currentMediaItem?.mediaId

        val pos = positionIn ?: player.currentPosition
        logger.info("curr media ${mediaId} ${pos}")

        if (mediaId != null) {
            serviceScope.launch {
                //logger.info("Setting last song: ${id}")
                AndroidStorage.setLastSong(mediaId, applicationContext)
                //logger.info("Set last position ${currentPlayer.currentPosition}")
                AndroidStorage.setLastPosition(
                    player.currentPosition,
                    applicationContext
                )
            }
        }
    }
}

const val LOGIN = "us.berkovitz.plexaaos.COMMAND.LOGIN"
const val LOGOUT = "us.berkovitz.plexaaos.COMMAND.LOGOUT"
const val REFRESH = "us.berkovitz.plexaaos.COMMAND.REFRESH"
const val REPEAT = "us.berkovitz.plexaaos.COMMAND.REPEAT"
const val SHUFFLE = "us.berkovitz.plexaaos.COMMAND.SHUFFLE"

/** Content styling constants */
const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
const val CONTENT_STYLE_LIST = 1
const val CONTENT_STYLE_GRID = 2

const val MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS = "playback_start_position_ms"

const val MEDIA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"