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
import us.berkovitz.plexapi.media.MediaItem
import us.berkovitz.plexapi.media.Playlist

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
        when (playlistState[playlistId]) {
            null, STATE_CREATED, STATE_INITIALIZING -> {
                if(playlistReadyListeners[playlistId] == null){
                    playlistReadyListeners[playlistId] = mutableListOf()
                }

                playlistReadyListeners[playlistId]!! += performAction
                false
            }
            else -> {
                performAction(getPlaylist(playlistId))
                true
            }
        }


    /**
     * Handles searching a [MusicSource] from a focused voice search, often coming
     * from the Google Assistant.
     */
    override fun search(query: String, extras: Bundle): List<MediaMetadataCompat> {
        return emptyList()
    }
}

private const val TAG = "MusicSource"
