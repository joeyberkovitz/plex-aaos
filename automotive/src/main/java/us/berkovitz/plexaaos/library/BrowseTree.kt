/*
 * Copyright 2019 Google Inc. All rights reserved.
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

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import us.berkovitz.plexaaos.R
import us.berkovitz.plexaaos.extensions.buildBrowsableItem
import us.berkovitz.plexaaos.extensions.toMediaItem
import us.berkovitz.plexapi.media.Playlist
import us.berkovitz.plexapi.media.Track

class BrowseTree(
    val context: Context,
    var musicSource: MusicSource,
    val recentMediaId: String? = null
) {
    private val mediaIdToChildren = mutableMapOf<String, MutableList<MediaItem>>()

    init {
        reset()
    }

    fun updateMusicSource(newMusicSource: MusicSource) {
        this.musicSource = newMusicSource
        reset()
    }

    fun reset() {
        mediaIdToChildren.clear()
        val rootList = mediaIdToChildren[UAMP_BROWSABLE_ROOT] ?: mutableListOf()

        val playlistsItem = buildBrowsableItem(
            mediaId = UAMP_PLAYLISTS_ROOT,
            title = context.getString(R.string.playlists_title),
            artworkUri = Uri.parse(
                RESOURCE_ROOT_URI +
                        context.resources.getResourceEntryName(R.drawable.baseline_library_music_24)
            )
        )

        rootList += playlistsItem
        mediaIdToChildren[UAMP_BROWSABLE_ROOT] = rootList
        refresh()
    }

    fun refresh() {
        musicSource.forEach { playlist ->
            val playlistId = playlist.ratingKey.toString()
            val playlistChildren = mediaIdToChildren[playlistId] ?: buildPlaylistRoot(playlist)
            for (item in playlist.loadedItems()) {
                if (item is Track) {
                    playlistChildren += item.toMediaItem()
                }
            }
        }
    }

    operator fun get(mediaId: String) = mediaIdToChildren[mediaId]

    private fun buildPlaylistRoot(playlist: Playlist): MutableList<MediaItem> {
        val playlistItem = playlist.toMediaItem()

        val rootList = mediaIdToChildren[UAMP_PLAYLISTS_ROOT] ?: mutableListOf()
        rootList += playlistItem
        mediaIdToChildren[UAMP_PLAYLISTS_ROOT] = rootList

        return mutableListOf<MediaItem>().also {
            mediaIdToChildren[playlistItem.mediaId] = it
        }
    }
}

private const val TAG = "BrowseTree"
const val UAMP_BROWSABLE_ROOT = "/"
const val UAMP_EMPTY_ROOT = "@empty@"
const val UAMP_PLAYLISTS_ROOT = "__PLAYLISTS__"

const val RESOURCE_ROOT_URI = "android.resource://us.berkovitz.plexaaos/drawable/"
