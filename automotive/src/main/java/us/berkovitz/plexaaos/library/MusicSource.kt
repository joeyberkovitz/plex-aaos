/*
 * Copyright 2017 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package us.berkovitz.plexaaos.library

import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import androidx.annotation.IntDef
import us.berkovitz.plexapi.media.Album
import us.berkovitz.plexapi.media.Artist
import us.berkovitz.plexapi.media.MediaItem
import us.berkovitz.plexapi.media.Playlist
import us.berkovitz.plexapi.media.Track

/**
 * Interface used by [MusicService] for looking up [MediaMetadataCompat] objects.
 *
 * Because Kotlin provides methods such as [Iterable.find] and [Iterable.filter],
 * this is a convenient interface to have on sources.
 */
interface MusicSource : Iterable<Playlist> {

    /**
     * Begins loading the data for this music source.
     */
    suspend fun load()

    suspend fun loadPlaylist(playlistId: String): Playlist?

    fun getPlaylist(playlistId: String): Playlist?
    fun playlistIterator(playlistId: String): Iterator<MediaItem>?

    fun getPlaylistItems(playlistId: String): Array<MediaItem>?

    /**
     * Method which will perform a given action after this [MusicSource] is ready to be used.
     *
     * @param performAction A lambda expression to be called with a boolean parameter when
     * the source is ready. `true` indicates the source was successfully prepared, `false`
     * indicates an error occurred.
     */
    fun whenReady(performAction: (Boolean) -> Unit): Boolean

    fun playlistWhenReady(playlistId: String, performAction: (Playlist?) -> Unit): Boolean

    fun search(query: String, extras: Bundle): List<MediaMetadataCompat>

    // New methods for artists and albums browsing

    /**
     * Get the music library section ID.
     */
    fun getMusicSectionId(): String?

    /**
     * Load all artists from the music library.
     */
    suspend fun loadArtists(): List<Artist>

    /**
     * Load all albums from the music library.
     */
    suspend fun loadAlbums(): List<Album>

    /**
     * Load albums for a specific artist.
     */
    suspend fun loadArtistAlbums(artistId: String): List<Album>

    /**
     * Load tracks for a specific album.
     */
    suspend fun loadAlbumTracks(albumId: String): List<Track>

    /**
     * Get cached artists.
     */
    fun getArtists(): List<Artist>

    /**
     * Get cached albums.
     */
    fun getAlbums(): List<Album>

    /**
     * Get cached album tracks.
     */
    fun getAlbumTracks(albumId: String): List<Track>?

    /**
     * Callback when artists are ready.
     */
    fun artistsWhenReady(performAction: (List<Artist>?) -> Unit): Boolean

    /**
     * Callback when albums are ready.
     */
    fun albumsWhenReady(performAction: (List<Album>?) -> Unit): Boolean

    /**
     * Callback when artist's albums are ready.
     */
    fun artistAlbumsWhenReady(artistId: String, performAction: (List<Album>?) -> Unit): Boolean

    /**
     * Callback when album's tracks are ready.
     */
    fun albumTracksWhenReady(albumId: String, performAction: (List<Track>?) -> Unit): Boolean

    // Home tab methods

    /**
     * Load recently played tracks.
     */
    suspend fun loadRecentlyPlayed(): List<Track>

    /**
     * Load recently added albums.
     */
    suspend fun loadRecentlyAdded(): List<Album>

    /**
     * Load on deck / continue listening tracks.
     */
    suspend fun loadOnDeck(): List<Track>

    /**
     * Get cached recently played tracks.
     */
    fun getRecentlyPlayed(): List<Track>

    /**
     * Get cached recently added albums.
     */
    fun getRecentlyAdded(): List<Album>

    /**
     * Get cached on deck tracks.
     */
    fun getOnDeck(): List<Track>

    /**
     * Callback when recently played are ready.
     */
    fun recentlyPlayedWhenReady(performAction: (List<Track>?) -> Unit): Boolean

    /**
     * Callback when recently added are ready.
     */
    fun recentlyAddedWhenReady(performAction: (List<Album>?) -> Unit): Boolean

    /**
     * Callback when on deck are ready.
     */
    fun onDeckWhenReady(performAction: (List<Track>?) -> Unit): Boolean

    // All Music methods

    /**
     * Load all tracks from the music library.
     */
    suspend fun loadAllTracks(): List<Track>

    /**
     * Get cached all tracks.
     */
    fun getAllTracks(): List<Track>

    /**
     * Callback when all tracks are ready.
     */
    fun allTracksWhenReady(performAction: (List<Track>?) -> Unit): Boolean
}

@IntDef(
    STATE_CREATED,
    STATE_INITIALIZING,
    STATE_INITIALIZED,
    STATE_ERROR
)
@Retention(AnnotationRetention.SOURCE)
annotation class State

/**
 * State indicating the source was created, but no initialization has performed.
 */
const val STATE_CREATED = 1

/**
 * State indicating initialization of the source is in progress.
 */
const val STATE_INITIALIZING = 2

/**
 * State indicating the source has been initialized and is ready to be used.
 */
const val STATE_INITIALIZED = 3

/**
 * State indicating an error has occurred.
 */
const val STATE_ERROR = 4

/**
 * Base class for music sources in UAMP.
 */
abstract class AbstractMusicSource : MusicSource {
    @State
    var state: Int = STATE_CREATED
        set(value) {
            if (value == STATE_INITIALIZED || value == STATE_ERROR) {
                synchronized(onReadyListeners) {
                    field = value
                    onReadyListeners.forEach { listener ->
                        listener(state == STATE_INITIALIZED)
                    }
                    onReadyListeners.clear()
                }
            } else {
                field = value
            }
        }


    private val playlistState: MutableMap<String, Int> = hashMapOf()
    private val playlistReadyListeners = mutableMapOf<String, MutableList<(Playlist?) -> Unit>>()

    fun getPlaylistState(playlistId: String): Int {
        return playlistState[playlistId] ?: STATE_CREATED
    }

    fun setPlaylistState(playlistId: String, playlist: Playlist?, state: Int){
        synchronized(playlistState) {
            if (state == STATE_INITIALIZED || state == STATE_ERROR) {
                synchronized(playlistReadyListeners) {
                    playlistState[playlistId] = state
                    playlistReadyListeners[playlistId]?.forEach { listener ->
                        listener(playlist)
                    }
                    playlistReadyListeners.clear()
                }
            } else {
                playlistState[playlistId] = state
            }
        }
    }

    private val onReadyListeners = mutableListOf<(Boolean) -> Unit>()

    /**
     * Performs an action when this MusicSource is ready.
     *
     * This method is *not* threadsafe. Ensure actions and state changes are only performed
     * on a single thread.
     */
    override fun whenReady(performAction: (Boolean) -> Unit): Boolean =
        when (state) {
            STATE_CREATED, STATE_INITIALIZING -> {
                onReadyListeners += performAction
                false
            }
            else -> {
                performAction(state != STATE_ERROR)
                true
            }
        }

    override fun playlistWhenReady(playlistId: String, performAction: (Playlist?) -> Unit): Boolean =
        synchronized(playlistState) {
            when (playlistState[playlistId]) {
                null, STATE_CREATED, STATE_INITIALIZING -> {
                    synchronized(playlistReadyListeners) {
                        if (playlistReadyListeners[playlistId] == null) {
                            playlistReadyListeners[playlistId] = mutableListOf()
                        }

                        playlistReadyListeners[playlistId]!! += performAction
                        false
                    }
                }

                else -> {
                    performAction(getPlaylist(playlistId))
                    true
                }
            }
        }


    /**
     * Handles searching a [MusicSource] from a focused voice search, often coming
     * from the Google Assistant.
     */
    override fun search(query: String, extras: Bundle): List<MediaMetadataCompat> {
        return emptyList()
    }

    // State management for artists and albums
    @State
    protected var artistsState: Int = STATE_CREATED
    @State
    protected var albumsState: Int = STATE_CREATED
    private val artistAlbumsState: MutableMap<String, Int> = hashMapOf()
    private val albumTracksState: MutableMap<String, Int> = hashMapOf()

    private val artistsReadyListeners = mutableListOf<(List<Artist>?) -> Unit>()
    private val albumsReadyListeners = mutableListOf<(List<Album>?) -> Unit>()
    private val artistAlbumsReadyListeners = mutableMapOf<String, MutableList<(List<Album>?) -> Unit>>()
    private val albumTracksReadyListeners = mutableMapOf<String, MutableList<(List<Track>?) -> Unit>>()

    // State management for Home tab features
    @State
    protected var recentlyPlayedState: Int = STATE_CREATED
    @State
    protected var recentlyAddedState: Int = STATE_CREATED
    @State
    protected var onDeckState: Int = STATE_CREATED
    @State
    protected var allTracksState: Int = STATE_CREATED

    private val recentlyPlayedReadyListeners = mutableListOf<(List<Track>?) -> Unit>()
    private val recentlyAddedReadyListeners = mutableListOf<(List<Album>?) -> Unit>()
    private val onDeckReadyListeners = mutableListOf<(List<Track>?) -> Unit>()
    private val allTracksReadyListeners = mutableListOf<(List<Track>?) -> Unit>()

    protected fun setArtistsState(state: Int, artists: List<Artist>?) {
        synchronized(artistsReadyListeners) {
            artistsState = state
            if (state == STATE_INITIALIZED || state == STATE_ERROR) {
                artistsReadyListeners.forEach { it(artists) }
                artistsReadyListeners.clear()
            }
        }
    }

    protected fun setAlbumsState(state: Int, albums: List<Album>?) {
        synchronized(albumsReadyListeners) {
            albumsState = state
            if (state == STATE_INITIALIZED || state == STATE_ERROR) {
                albumsReadyListeners.forEach { it(albums) }
                albumsReadyListeners.clear()
            }
        }
    }

    protected fun setArtistAlbumsState(artistId: String, state: Int, albums: List<Album>?) {
        synchronized(artistAlbumsState) {
            artistAlbumsState[artistId] = state
            if (state == STATE_INITIALIZED || state == STATE_ERROR) {
                synchronized(artistAlbumsReadyListeners) {
                    artistAlbumsReadyListeners[artistId]?.forEach { it(albums) }
                    artistAlbumsReadyListeners[artistId]?.clear()
                }
            }
        }
    }

    protected fun setAlbumTracksState(albumId: String, state: Int, tracks: List<Track>?) {
        synchronized(albumTracksState) {
            albumTracksState[albumId] = state
            if (state == STATE_INITIALIZED || state == STATE_ERROR) {
                synchronized(albumTracksReadyListeners) {
                    albumTracksReadyListeners[albumId]?.forEach { it(tracks) }
                    albumTracksReadyListeners[albumId]?.clear()
                }
            }
        }
    }

    override fun artistsWhenReady(performAction: (List<Artist>?) -> Unit): Boolean =
        synchronized(artistsReadyListeners) {
            when (artistsState) {
                STATE_CREATED, STATE_INITIALIZING -> {
                    artistsReadyListeners += performAction
                    false
                }
                else -> {
                    performAction(getArtists())
                    true
                }
            }
        }

    override fun albumsWhenReady(performAction: (List<Album>?) -> Unit): Boolean =
        synchronized(albumsReadyListeners) {
            when (albumsState) {
                STATE_CREATED, STATE_INITIALIZING -> {
                    albumsReadyListeners += performAction
                    false
                }
                else -> {
                    performAction(getAlbums())
                    true
                }
            }
        }

    override fun artistAlbumsWhenReady(artistId: String, performAction: (List<Album>?) -> Unit): Boolean =
        synchronized(artistAlbumsState) {
            when (artistAlbumsState[artistId]) {
                null, STATE_CREATED, STATE_INITIALIZING -> {
                    synchronized(artistAlbumsReadyListeners) {
                        if (artistAlbumsReadyListeners[artistId] == null) {
                            artistAlbumsReadyListeners[artistId] = mutableListOf()
                        }
                        artistAlbumsReadyListeners[artistId]!! += performAction
                        false
                    }
                }
                else -> {
                    performAction(null) // Will be loaded fresh
                    true
                }
            }
        }

    override fun albumTracksWhenReady(albumId: String, performAction: (List<Track>?) -> Unit): Boolean =
        synchronized(albumTracksState) {
            when (albumTracksState[albumId]) {
                null, STATE_CREATED, STATE_INITIALIZING -> {
                    synchronized(albumTracksReadyListeners) {
                        if (albumTracksReadyListeners[albumId] == null) {
                            albumTracksReadyListeners[albumId] = mutableListOf()
                        }
                        albumTracksReadyListeners[albumId]!! += performAction
                        false
                    }
                }
                else -> {
                    performAction(getAlbumTracks(albumId))
                    true
                }
            }
        }

    // Home tab state management

    protected fun setRecentlyPlayedState(state: Int, tracks: List<Track>?) {
        synchronized(recentlyPlayedReadyListeners) {
            recentlyPlayedState = state
            if (state == STATE_INITIALIZED || state == STATE_ERROR) {
                recentlyPlayedReadyListeners.forEach { it(tracks) }
                recentlyPlayedReadyListeners.clear()
            }
        }
    }

    protected fun setRecentlyAddedState(state: Int, albums: List<Album>?) {
        synchronized(recentlyAddedReadyListeners) {
            recentlyAddedState = state
            if (state == STATE_INITIALIZED || state == STATE_ERROR) {
                recentlyAddedReadyListeners.forEach { it(albums) }
                recentlyAddedReadyListeners.clear()
            }
        }
    }

    protected fun setOnDeckState(state: Int, tracks: List<Track>?) {
        synchronized(onDeckReadyListeners) {
            onDeckState = state
            if (state == STATE_INITIALIZED || state == STATE_ERROR) {
                onDeckReadyListeners.forEach { it(tracks) }
                onDeckReadyListeners.clear()
            }
        }
    }

    override fun recentlyPlayedWhenReady(performAction: (List<Track>?) -> Unit): Boolean =
        synchronized(recentlyPlayedReadyListeners) {
            when (recentlyPlayedState) {
                STATE_CREATED, STATE_INITIALIZING -> {
                    recentlyPlayedReadyListeners += performAction
                    false
                }
                else -> {
                    performAction(getRecentlyPlayed())
                    true
                }
            }
        }

    override fun recentlyAddedWhenReady(performAction: (List<Album>?) -> Unit): Boolean =
        synchronized(recentlyAddedReadyListeners) {
            when (recentlyAddedState) {
                STATE_CREATED, STATE_INITIALIZING -> {
                    recentlyAddedReadyListeners += performAction
                    false
                }
                else -> {
                    performAction(getRecentlyAdded())
                    true
                }
            }
        }

    override fun onDeckWhenReady(performAction: (List<Track>?) -> Unit): Boolean =
        synchronized(onDeckReadyListeners) {
            when (onDeckState) {
                STATE_CREATED, STATE_INITIALIZING -> {
                    onDeckReadyListeners += performAction
                    false
                }
                else -> {
                    performAction(getOnDeck())
                    true
                }
            }
        }

    // All tracks state management

    protected fun setAllTracksState(state: Int, tracks: List<Track>?) {
        synchronized(allTracksReadyListeners) {
            allTracksState = state
            if (state == STATE_INITIALIZED || state == STATE_ERROR) {
                allTracksReadyListeners.forEach { it(tracks) }
                allTracksReadyListeners.clear()
            }
        }
    }

    override fun allTracksWhenReady(performAction: (List<Track>?) -> Unit): Boolean =
        synchronized(allTracksReadyListeners) {
            when (allTracksState) {
                STATE_CREATED, STATE_INITIALIZING -> {
                    allTracksReadyListeners += performAction
                    false
                }
                else -> {
                    performAction(getAllTracks())
                    true
                }
            }
        }
}

private const val TAG = "MusicSource"
