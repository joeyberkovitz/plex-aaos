package us.berkovitz.plexaaos

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media.utils.MediaConstants
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.berkovitz.plexaaos.extensions.buildBrowsableItem
import us.berkovitz.plexaaos.extensions.toMediaItem
import us.berkovitz.plexaaos.library.BrowseTree
import us.berkovitz.plexaaos.library.MusicSource
import us.berkovitz.plexaaos.library.PlexSource
import us.berkovitz.plexaaos.library.UAMP_BROWSABLE_ROOT
import us.berkovitz.plexaaos.library.UAMP_PLAYLISTS_ROOT
import us.berkovitz.plexapi.media.Track
import us.berkovitz.plexapi.myplex.AuthorizationException
import kotlin.math.ceil
import kotlin.math.min

const val LOGIN = "us.berkovitz.plexaaos.COMMAND.LOGIN"
const val REFRESH = "us.berkovitz.plexaaos.COMMAND.REFRESH"
const val LOGOUT = "us.berkovitz.plexaaos.COMMAND.LOGOUT"

@OptIn(UnstableApi::class)
class MyMusicService : MediaLibraryService() {
    companion object {
        val logger = PlexLoggerFactory.loggerFor(MyMusicService::class)
        val PAGE_SIZE = 100

        private val SESSION_COMMAND_LOGIN = SessionCommand(LOGIN, Bundle.EMPTY)
        private val SESSION_COMMAND_REFRESH = SessionCommand(REFRESH, Bundle.EMPTY)
        private val SESSION_COMMAND_LOGOUT = SessionCommand(LOGOUT, Bundle.EMPTY)
        private const val PLACEHOLDER_NOTIFICATION_ID = 9999
        private const val ACTION_SHUFFLE = "shuffle"
        private const val ACTION_REPEAT = "repeat"
        private val SESSION_COMMAND_SHUFFLE = SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY)
        private val SESSION_COMMAND_REPEAT = SessionCommand(ACTION_REPEAT, Bundle.EMPTY)
    }

    private lateinit var plexUtil: PlexUtil
    private lateinit var mediaSource: MusicSource

    private lateinit var currentPlayer: Player

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var mediaLibrarySession: MediaLibrarySession
    private var currentPlaylistItems: List<MediaItem> = emptyList()
    private var currentMediaItemIndex: Int = 0

    private var plexToken: String? = null
    private var active = false
    private var wasActive = false
    private var previousPosition: Long = 0

    // Cache infrastructure
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

    // DownloadManager for prefetching
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

    private val browseTree: BrowseTree by lazy {
        BrowseTree(applicationContext, mediaSource)
    }

    private val playerAudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

    private val playerListener = PlayerEventListener()

    private val exoPlayer: ExoPlayer by lazy {
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(cacheDataSourceFactory)
            )
            .build().apply {
                setAudioAttributes(playerAudioAttributes, true)
                setHandleAudioBecomingNoisy(true)
                addListener(playerListener)
                repeatMode = Player.REPEAT_MODE_ALL
            }
    }

    override fun onBind(intent: Intent?): IBinder? {
        logger.info("onBind called with action: ${intent?.action}")
        return super.onBind(intent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        logger.info("onGetSession called")
        return mediaLibrarySession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()
        AndroidPlexApi.initPlexApi(this)
        plexUtil = PlexUtil(this)

        val sessionActivityPendingIntent =
            packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
                PendingIntent.getActivity(
                    this, 0, sessionIntent,
                    PendingIntent.FLAG_IMMUTABLE
                )
            }

        currentPlayer = exoPlayer

        val sessionBuilder = MediaLibrarySession.Builder(this, exoPlayer, LibrarySessionCallback())
        if (sessionActivityPendingIntent != null) {
            sessionBuilder.setSessionActivity(sessionActivityPendingIntent)
        }
        mediaLibrarySession = sessionBuilder.build()

        serviceScope.launch {
            val shuffleMode = AndroidStorage.getShuffleEnabled(applicationContext)
            val repeatMode = AndroidStorage.getRepeatMode(applicationContext)
            currentPlayer.shuffleModeEnabled = shuffleMode
            currentPlayer.repeatMode = repeatMode
        }

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

    override fun onDestroy() {
        mediaLibrarySession.release()

        serviceJob.cancel()

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

        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
        super.onDestroy()
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
        if (this::mediaLibrarySession.isInitialized) {
            mediaLibrarySession.setSessionExtras(extras)
        }
    }

    private fun isAuthenticated(): Boolean {
        plexToken = plexUtil.getToken()
        return plexToken != null
    }

    fun loginCommand(extras: Bundle, callback: ResultReceiver?): Boolean {
        return refreshCommand(extras, callback, true)
    }

    fun logoutCommand(callback: ResultReceiver?): Boolean {
        currentPlayer.stop()
        currentPlayer.clearMediaItems()
        plexUtil.clearToken()
        requireLogin()
        callback?.send(Activity.RESULT_OK, Bundle.EMPTY)
        return true
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

        // Clear error state
        if (this::mediaLibrarySession.isInitialized) {
            mediaLibrarySession.setSessionExtras(Bundle.EMPTY)
            mediaLibrarySession.notifyChildrenChanged(
                UAMP_BROWSABLE_ROOT,
                0,
                null
            )
        }
        return true
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveLastSong()
        active = false
        super.onTaskRemoved(rootIntent)

        currentPlayer.stop()
        currentPlayer.clearMediaItems()
    }

    private fun preparePlaylist(
        metadataList: List<MediaItem>,
        itemToPlay: MediaItem?,
        playWhenReady: Boolean,
        playbackStartPositionMs: Long
    ) {
        val initialWindowIndex = if (itemToPlay == null) 0 else {
            var idx = 0
            for (i in metadataList.indices) {
                if (metadataList[i].mediaId == itemToPlay.mediaId) {
                    idx = i
                    break
                }
            }
            idx
        }
        currentPlaylistItems = metadataList

        currentPlayer.playWhenReady = playWhenReady
        currentPlayer.stop()
        logger.info("starting playback at position $initialWindowIndex, $playbackStartPositionMs")
        currentPlayer.setMediaItems(
            metadataList, initialWindowIndex, playbackStartPositionMs
        )
        currentPlayer.prepare()

        prefetchNextTracks(metadataList, initialWindowIndex, 5)

        active = true
        wasActive = true
    }

    private fun prefetchNextTracks(
        metadataList: List<MediaItem>,
        currentIndex: Int,
        count: Int = 5
    ) {
        if (metadataList.isEmpty() || !this::currentPlayer.isInitialized) return

        val timeline = currentPlayer.currentTimeline
        if (timeline.isEmpty) return

        val currentWindowIndex = currentPlayer.currentMediaItemIndex
        val indicesToPrefetch = mutableListOf<Int>()

        var nextWindowIndex = timeline.getNextWindowIndex(
            currentWindowIndex,
            currentPlayer.repeatMode,
            currentPlayer.shuffleModeEnabled
        )

        var remaining = count
        while (nextWindowIndex != C.INDEX_UNSET && remaining > 0) {
            indicesToPrefetch.add(nextWindowIndex)
            remaining--

            nextWindowIndex = timeline.getNextWindowIndex(
                nextWindowIndex,
                currentPlayer.repeatMode,
                currentPlayer.shuffleModeEnabled
            )

            if (indicesToPrefetch.size >= min(count, timeline.windowCount)) {
                break
            }
        }

        for (windowIndex in indicesToPrefetch) {
            if (windowIndex >= 0 && windowIndex < metadataList.size) {
                val meta = metadataList[windowIndex]
                val uri = meta.localConfiguration?.uri ?: continue
                val id = meta.mediaId

                try {
                    val downloadRequest = DownloadRequest.Builder(id, uri)
                        .build()

                    downloadManager.addDownload(downloadRequest)
                    downloadManager.resumeDownloads()
                    logger.debug("Prefetching track at index $windowIndex: $id (shuffle: ${currentPlayer.shuffleModeEnabled})")
                } catch (e: Exception) {
                    logger.error("Failed to add download for $id: ${e.message}")
                }
            }
        }
    }

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SESSION_COMMAND_LOGIN)
                .add(SESSION_COMMAND_REFRESH)
                .add(SESSION_COMMAND_LOGOUT)
                .add(SESSION_COMMAND_SHUFFLE)
                .add(SESSION_COMMAND_REPEAT)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                LOGIN -> loginCommand(args, null)
                REFRESH -> refreshCommand(args, null)
                LOGOUT -> logoutCommand(null)
                ACTION_SHUFFLE -> {
                    currentPlayer.shuffleModeEnabled = !currentPlayer.shuffleModeEnabled
                    serviceScope.launch {
                        AndroidStorage.setShuffleEnabled(currentPlayer.shuffleModeEnabled, applicationContext)
                    }
                    updateCustomLayout()
                }
                ACTION_REPEAT -> {
                    currentPlayer.repeatMode = when (currentPlayer.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                        else -> Player.REPEAT_MODE_OFF
                    }
                    serviceScope.launch {
                        AndroidStorage.setRepeatMode(currentPlayer.repeatMode, applicationContext)
                    }
                    updateCustomLayout()
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

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

            val rootItem = MediaItem.Builder()
                .setMediaId(UAMP_BROWSABLE_ROOT)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Root")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setExtras(rootExtras)
                        .build()
                )
                .build()

            return Futures.immediateFuture(
                LibraryResult.ofItem(rootItem, null)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            logger.info("onGetChildren: $parentId")

            if (!isAuthenticated()) {
                logger.info("not authenticated")
                return Futures.immediateFuture(
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }
            checkInit()

            if (parentId == UAMP_PLAYLISTS_ROOT || parentId == UAMP_BROWSABLE_ROOT) {
                if (parentId == UAMP_BROWSABLE_ROOT) {
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

                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                val resultsSent = mediaSource.whenReady { successfullyInitialized ->
                    if (successfullyInitialized) {
                        browseTree.refresh()
                        val children = browseTree[parentId]
                            ?.sortedBy { item -> item.mediaMetadata.title?.toString() }
                            ?: listOf()
                        logger.info("Sending ${children.size} results for $parentId")
                        future.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), null))
                    } else {
                        logger.info("Failed to load results for $parentId")
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO))
                    }
                }

                if (!resultsSent) {
                    // Results are async, future will be completed by the callback
                    return future
                }
                return future
            } else {
                var playlistId = parentId
                var pageNum: Int? = null
                val splitMediaId = parentId.split('/')
                if (splitMediaId.size == 2) {
                    playlistId = splitMediaId[0]

                    if (splitMediaId[1].startsWith("page_")) {
                        pageNum = splitMediaId[1].substring(5).toIntOrNull()
                    }
                }

                serviceScope.launch {
                    try {
                        mediaSource.loadPlaylist(playlistId)
                    } catch (exc: AuthorizationException) {
                        plexUtil.clearToken()
                        requireLogin()
                    } catch (exc: Exception) {
                        logger.error("error occurred while loading playlist $playlistId: ${exc.message} ${exc.stackTraceToString()}")
                    }
                }

                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                val resultsSent = mediaSource.playlistWhenReady(playlistId) { plist ->
                    if (plist != null && pageNum == null && plist.leafCount > PAGE_SIZE) {
                        val numPages = ceil(plist.leafCount.toDouble() / PAGE_SIZE).toInt()
                        val children = mutableListOf<MediaItem>()
                        logger.info("Sending paginated playlist results: $numPages")
                        for (i in 0 until numPages) {
                            val start = (i * PAGE_SIZE) + 1
                            val end = min(((i + 1) * PAGE_SIZE), plist.leafCount.toInt())
                            val id = "${plist.ratingKey}/page_$i"

                            children += buildBrowsableItem(
                                mediaId = id,
                                title = "$start - $end"
                            )
                        }
                        future.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), null))
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
                            children += item.toMediaItem(playlistId)
                        }
                        logger.info("Sending playlist results: ${children.size}")
                        future.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), null))
                    } else {
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO))
                    }
                }

                if (!resultsSent) {
                    return future
                }
                return future
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            // Resolve media items by looking up their URIs from the media source
            val resolvedItems = mediaItems.map { requestedItem ->
                val mediaId = requestedItem.mediaId
                val idSplit = mediaId.split('/')
                if (idSplit.size == 2) {
                    val playlistId = idSplit[0]
                    val trackId = idSplit[1]
                    // Try to find the track in the current playlist items
                    currentPlaylistItems.find { it.mediaId == mediaId }
                        ?: requestedItem
                } else {
                    requestedItem
                }
            }
            return Futures.immediateFuture(resolvedItems)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Handle prepareFromMediaId equivalent
            if (mediaItems.size == 1) {
                val prepareId = mediaItems[0].mediaId
                val idSplit = prepareId.split('/')
                if (idSplit.size == 2) {
                    val playlistId = idSplit[0]
                    val mediaId = idSplit[1]

                    val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()

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
                                future.set(
                                    MediaSession.MediaItemsWithStartPosition(
                                        mediaItems, startIndex, startPositionMs
                                    )
                                )
                                return@launch
                            }

                            val builtPlaylist = currPlaylist
                                .filterIsInstance<Track>()
                                .map { it.toMediaItem(playlistId) }

                            val itemIndex = builtPlaylist.indexOfFirst { item ->
                                item.mediaId == prepareId
                            }.let { if (it == -1) 0 else it }

                            currentPlaylistItems = builtPlaylist

                            future.set(
                                MediaSession.MediaItemsWithStartPosition(
                                    builtPlaylist, itemIndex, startPositionMs
                                )
                            )
                        }
                    }

                    return future
                }
            }

            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            )
        }
    }

    private fun updateCustomLayout() {
        if (!this::mediaLibrarySession.isInitialized) return

        val shuffleIcon = if (currentPlayer.shuffleModeEnabled) R.drawable.baseline_shuffle_on_24
        else R.drawable.baseline_shuffle_24

        val repeatIcon = when (currentPlayer.repeatMode) {
            Player.REPEAT_MODE_OFF -> R.drawable.baseline_repeat_24
            Player.REPEAT_MODE_ALL -> R.drawable.baseline_repeat_on_24
            Player.REPEAT_MODE_ONE -> R.drawable.baseline_repeat_one_on_24
            else -> R.drawable.baseline_repeat_24
        }

        val shuffleButton = CommandButton.Builder(CommandButton.ICON_SHUFFLE_OFF)
            .setSessionCommand(SESSION_COMMAND_SHUFFLE)
            .setDisplayName("Shuffle")
            .setIconResId(shuffleIcon)
            .build()

        val repeatButton = CommandButton.Builder(CommandButton.ICON_REPEAT_ALL)
            .setSessionCommand(SESSION_COMMAND_REPEAT)
            .setDisplayName("Repeat")
            .setIconResId(repeatIcon)
            .build()

        mediaLibrarySession.setCustomLayout(listOf(shuffleButton, repeatButton))
    }

    /**
     * Listen for events from ExoPlayer.
     */
    private inner class PlayerEventListener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
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
            previousPosition = oldPosition.positionMs
        }

        override fun onPlayerError(error: PlaybackException) {
            var message = "player error"
            logger.error("Player error: " + error.errorCodeName + " (" + error.errorCode + ")")
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                || error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            ) {
                message = "media not found"
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
            val id = currentPlaylistItems[currentMediaItemIndex].mediaId
            serviceScope.launch {
                AndroidStorage.setLastSong(id, applicationContext)
                AndroidStorage.setLastPosition(
                    currentPlayer.currentPosition,
                    applicationContext
                )
            }
        }
    }
}

const val NETWORK_FAILURE = "us.berkovitz.plexaaos.NETWORK_FAILURE"

/** Content styling constants */
private const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
private const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
private const val CONTENT_STYLE_LIST = 1
private const val CONTENT_STYLE_GRID = 2

const val MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS = "playback_start_position_ms"

const val MEDIA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
