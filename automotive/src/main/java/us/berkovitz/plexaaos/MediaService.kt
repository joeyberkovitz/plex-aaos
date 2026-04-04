@file:OptIn(UnstableApi::class) package us.berkovitz.plexaaos

import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.content.Intent
import android.media.browse.MediaBrowser
import android.os.Bundle
import androidx.media3.session.MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT
import androidx.media3.session.MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import us.berkovitz.plexaaos.library.BrowseTree
import us.berkovitz.plexaaos.library.MusicSource
import us.berkovitz.plexaaos.library.PlexSource
import us.berkovitz.plexaaos.library.UAMP_BROWSABLE_ROOT
import us.berkovitz.plexaaos.library.UAMP_PLAYLISTS_ROOT
import us.berkovitz.plexaaos.library.buildMeta
import us.berkovitz.plexaaos.library.from
import us.berkovitz.plexapi.media.Track
import us.berkovitz.plexapi.myplex.AuthorizationException
import kotlin.math.ceil
import kotlin.math.min

class PlexMediaService : MediaLibraryService() {
    companion object {
        val logger = PlexLoggerFactory.loggerFor(PlexMediaService::class)
        val PAGE_SIZE = 100
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

    private val uAmpAudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()


    override fun onCreate() {
        super.onCreate()

        AndroidPlexApi.initPlexApi(this)
        plexUtil = PlexUtil(this)

        player = PlexPlayer(
            ExoPlayer.Builder(this).build().apply {
                setAudioAttributes(uAmpAudioAttributes, true)
                setHandleAudioBecomingNoisy(true)
                //addListener(playerListener)
            }
        )

        mediaLibrarySession = with(MediaLibrarySession.Builder(
            this, player, MediaLibraryCallback())) {
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
            var playlistId = parentMediaId
            var pageNum: Int? = null
            val splitMediaId = parentMediaId.split('/')
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
                    logger.error("error occurred while loading playlist ${playlistId}: ${exc.message} ${exc.stackTraceToString()}")
                }
            }

            mediaSource.playlistWhenReady(playlistId) { plist ->
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
                    result.set(LibraryResult.ofItemList(children, null))
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
                        children += MediaItem.Builder().buildMeta(item, playlistId)
                    }
                    logger.info("Sending playlist results: ${children.size}")
                    result.set(LibraryResult.ofItemList(children, null))
                } else {
                    result.set(LibraryResult.ofError(SessionError.ERROR_IO))
                }
            }
        }
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
                    .build()
            return MediaSession.ConnectionResult.accept(
                sessionCommands, connectionResult.availablePlayerCommands
            )
        }

        @OptIn(UnstableApi::class) override fun onCustomCommand(
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

                REFRESH -> {
                    refreshCommand(future)
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

        @OptIn(UnstableApi::class) override fun onGetLibraryRoot(
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

        @OptIn(UnstableApi::class) override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if(!isAuthenticated()){
               return Futures.immediateFuture(
                    LibraryResult.ofError(
                        SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
                        LibraryParams.Builder()
                            .setExtras(getExpiredAuthenticationResolutionExtras()).build()
                    )
                )
            }


            logger.error("onPrepareFromMediaId: $mediaId")
            val idSplit = mediaId.split('/')
            if (idSplit.size != 2) {
                logger.error("media id doesn't include parent id: $mediaId")
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_IO))
            }

            val playlistId = idSplit[0]
            val mediaId = idSplit[1]

            serviceScope.launch {
                //logger.info("load playlist starting")
                try {
                    mediaSource.loadPlaylist(playlistId)
                } catch (exc: Exception) {
                    logger.error("Failed to find playlist: $playlistId: ${exc.message}, ${exc.printStackTrace()}")
                }
                //logger.info("load playlist complete")
            }

            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            mediaSource.playlistWhenReady(playlistId) { plist ->
                //logger.info("playlist when ready")
                serviceScope.launch {
                    //logger.info("playlist when ready scope")
                    val currPlaylist = plist?.items()
                    if (currPlaylist == null) {
                        logger.error("Failed to load playlist: $playlistId")
                        future.set(LibraryResult.ofError(SessionError.ERROR_IO))
                        return@launch
                    }

                    val itemToPlay = currPlaylist.find { item ->
                        if (item !is Track) {
                            logger.warn("Skipping unknown playlist item: $item")
                            future.set(LibraryResult.ofError(SessionError.ERROR_IO))
                            return@find false
                        }
                        item.ratingKey.toString() == mediaId
                    }
                    if (itemToPlay == null) {
                        logger.warn("Content not found: MediaID=$mediaId")
                        // TODO: Notify caller of the error.
                        future.set(LibraryResult.ofError(SessionError.ERROR_IO))
                    } else {
                        future.set(
                            LibraryResult.ofItem(
                                MediaItem.Builder().from(itemToPlay, playlistId).build(),
                                LibraryParams.Builder().build()
                            )
                        )
//                        val playbackStartPositionMs =
//                            extras?.getLong(
//                                MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS,
//                                C.TIME_UNSET
//                            )
//                                ?: C.TIME_UNSET
                    }
                }
            }

            return future
        }

        var items : List<MediaItem> = emptyList()

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
            items= browseTree.get(playlistId) ?: emptyList()
            val startIndex = items.indexOfFirst { it -> it.mediaId == mediaItems[0].mediaId }


            return super.onSetMediaItems(
                mediaSession,
                controller,
                items,
                startIndex,
                startPositionMs
            )
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

}

const val LOGIN = "us.berkovitz.plexaaos.COMMAND.LOGIN"
const val REFRESH = "us.berkovitz.plexaaos.COMMAND.REFRESH"
/** Content styling constants */
const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
const val CONTENT_STYLE_LIST = 1
const val CONTENT_STYLE_GRID = 2

const val MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS = "playback_start_position_ms"

const val MEDIA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"