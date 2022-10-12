package us.berkovitz.plexaaos

import android.accounts.AccountManager
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
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.util.Util
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import us.berkovitz.plexaaos.extensions.flag
import us.berkovitz.plexaaos.extensions.id
import us.berkovitz.plexaaos.extensions.toMediaItem
import us.berkovitz.plexaaos.library.BrowseTree
import us.berkovitz.plexaaos.library.MusicSource
import us.berkovitz.plexaaos.library.PlexSource
import us.berkovitz.plexaaos.library.UAMP_BROWSABLE_ROOT
import us.berkovitz.plexaaos.library.from
import us.berkovitz.plexapi.logging.Logger
import us.berkovitz.plexapi.logging.LoggingFactory

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

const val LOGIN = "us.berkovitz.plexaaos.COMMAND.LOGIN"


class MyMusicService : MediaBrowserServiceCompat() {

    init {
        LoggingFactory.setFactory(PlexLoggerFactory)
    }

    companion object {
        const val TAG = "MyMusicService"
    }

    private lateinit var accountManager: AccountManager

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
     * Configure ExoPlayer to handle audio focus for us.
     * See [Player.AudioComponent.setAudioAttributes] for details.
     */
    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(playerAudioAttributes, true)
            setHandleAudioBecomingNoisy(true)
            addListener(playerListener)
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
                //LOGOUT -> logoutCommand(extras ?: Bundle.EMPTY, callback)
                else -> false
            }
    }


    override fun onCreate() {
        super.onCreate()

        accountManager = AccountManager.get(this)

        // Build a PendingIntent that can be used to launch the UI.
        val sessionActivityPendingIntent =
            packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
                PendingIntent.getActivity(this, 0, sessionIntent,
                    PendingIntent.FLAG_IMMUTABLE)
            }

        // Create a new MediaSession.
        mediaSession = MediaSessionCompat(this, "MyMusicService")
            .apply {
                setSessionActivity(sessionActivityPendingIntent)
                isActive = true
            }
        sessionToken = mediaSession.sessionToken

        if(!isAuthenticated()){
            requireLogin()
            return
        }

        // The media library is built from a remote JSON file. We'll create the source here,
        // and then use a suspend function to perform the download off the main thread.
        mediaSource = PlexSource(plexToken!!)
        serviceScope.launch {
            mediaSource.load()
        }

        // ExoPlayer will manage the MediaSession for us.
        mediaSessionConnector = MediaSessionConnector(mediaSession)
        mediaSessionConnector.setPlaybackPreparer(UampPlaybackPreparer())
        mediaSessionConnector.setQueueNavigator(UampQueueNavigator(mediaSession))

        switchToPlayer(
            previousPlayer = null,
            newPlayer = exoPlayer
        )

        // Register to handle login/logout commands.
        mediaSessionConnector.registerCustomCommandReceiver(AutomotiveCommandReceiver())
    }

    override fun onDestroy() {
        mediaSession.run {
            isActive = false
            release()
        }

        // Cancel coroutines when the service is going away.
        serviceJob.cancel()

        // Free ExoPlayer resources.
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
    }

    override fun onGetRoot(clientPackageName: String,
                           clientUid: Int,
                           rootHints: Bundle?): MediaBrowserServiceCompat.BrowserRoot {
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
        if(!isAuthenticated()){
            result.sendResult(null)
            return
        }

        /**
         * If the caller requests the recent root, return the most recently played song.
         */
        /*if (parentMediaId == UAMP_RECENT_ROOT) {
            result.sendResult(storage.loadRecentSong()?.let { song -> listOf(song) })
        } else {*/
            // If the media source is ready, the results will be set synchronously here.
            val resultsSent = mediaSource.whenReady { successfullyInitialized ->
                if (successfullyInitialized) {
                    val children = browseTree[parentMediaId]?.map { item ->
                        MediaItem(item.description, item.flag)
                    }
                    result.sendResult(children)
                } else {
                    mediaSession.sendSessionEvent(NETWORK_FAILURE, null)
                    result.sendResult(null)
                }
            }

            // If the results are not ready, the service must "detach" the results before
            // the method returns. After the source is ready, the lambda above will run,
            // and the caller will be notified that the results are ready.
            //
            // See [MediaItemFragmentViewModel.subscriptionCallback] for how this is passed to the
            // UI/displayed in the [RecyclerView].
            if (!resultsSent) {
                result.detach()
            }
        //}
    }

    private fun isAuthenticated(): Boolean {
        val accounts = accountManager.getAccountsByType(Authenticator.ACCOUNT_TYPE)
        if(accounts.isEmpty()){
            return false
        }

        plexToken = accountManager.getPassword(accounts[0])

        return true
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

    fun loginCommand(extras: Bundle, callback: ResultReceiver?): Boolean {
        // Updated state (including clearing the error) now that the user has logged in.
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_NONE, 0, 0F)
                .build()
        )
        callback?.send(Activity.RESULT_OK, Bundle.EMPTY)
        return true
    }

    /**
     * This is the code that causes UAMP to stop playing when swiping the activity away from
     * recents. The choice to do this is app specific. Some apps stop playback, while others allow
     * playback to continue and allow users to stop it with the notification.
     */
    override fun onTaskRemoved(rootIntent: Intent) {
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
        val initialWindowIndex = if (itemToPlay == null) 0 else metadataList.indexOf(itemToPlay)
        currentPlaylistItems = metadataList

        currentPlayer.playWhenReady = playWhenReady
        currentPlayer.stop()
        // Set playlist and prepare.
        currentPlayer.setMediaItems(
            metadataList.map { it.toMediaItem() }, initialWindowIndex, playbackStartPositionMs)
        currentPlayer.prepare()
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
         *
         * TODO: Add support for ACTION_PREPARE and ACTION_PLAY, which mean "prepare/play something".
         */
        override fun getSupportedPrepareActions(): Long =
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID

        override fun onPrepare(playWhenReady: Boolean) = Unit

        override fun onPrepareFromMediaId(
            mediaId: String,
            playWhenReady: Boolean,
            extras: Bundle?
        ) {
            /*
            mediaSource.whenReady {
                val itemToPlay: MediaMetadataCompat? = mediaSource.find { item ->
                    item.id == mediaId
                }
                if (itemToPlay == null) {
                    Log.w(TAG, "Content not found: MediaID=$mediaId")
                    // TODO: Notify caller of the error.
                } else {

                    val playbackStartPositionMs =
                        extras?.getLong(MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS, C.TIME_UNSET)
                            ?: C.TIME_UNSET

                    preparePlaylist(
                        buildPlaylist(itemToPlay),
                        itemToPlay,
                        playWhenReady,
                        playbackStartPositionMs
                    )
                }
            }
             */
        }

        override fun onPrepareFromSearch(query: String, playWhenReady: Boolean, extras: Bundle?) = Unit

        override fun onPrepareFromUri(uri: Uri, playWhenReady: Boolean, extras: Bundle?) = Unit

        override fun onCommand(
            player: Player,
            command: String,
            extras: Bundle?,
            cb: ResultReceiver?
        ) = false

        /**
         * Builds a playlist based on a [MediaMetadataCompat].
         *
         * TODO: Support building a playlist by artist, genre, etc...
         *
         * @param item Item to base the playlist on.
         * @return a [List] of [MediaMetadataCompat] objects representing a playlist.
         */
        private fun buildPlaylist(item: MediaMetadataCompat): List<MediaMetadataCompat> {
            val plist = mediaSource.filter { it.ratingKey.toString() == item.id }.firstOrNull()
                ?: return emptyList()

            return plist.loadedItems().map { MediaMetadataCompat.Builder().from(it).build() }
        }
    }

    /**
     * Listen for events from ExoPlayer.
     */
    private inner class PlayerEventListener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)
                || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
                || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                currentMediaItemIndex = if (currentPlaylistItems.isNotEmpty()) {
                    Util.constrainValue(
                        player.currentMediaItemIndex,
                        /* min = */ 0,
                        /* max = */ currentPlaylistItems.size - 1
                    )
                } else 0
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            var message = "player error";
            Log.e(TAG, "Player error: " + error.errorCodeName + " (" + error.errorCode + ")");
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                || error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
                message = "media not found";
            }
            Toast.makeText(
                applicationContext,
                message,
                Toast.LENGTH_LONG
            ).show()
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

const val MEDIA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"