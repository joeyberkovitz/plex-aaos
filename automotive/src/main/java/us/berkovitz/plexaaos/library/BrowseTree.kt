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
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import us.berkovitz.plexaaos.R
import us.berkovitz.plexaaos.extensions.*
import us.berkovitz.plexapi.media.Playlist
import us.berkovitz.plexapi.media.PlexServer
import us.berkovitz.plexapi.media.Track

/**
 * Represents a tree of media that's used by [MusicService.onLoadChildren].
 *
 * [BrowseTree] maps a media id (see: [MediaMetadataCompat.METADATA_KEY_MEDIA_ID]) to one (or
 * more) [MediaMetadataCompat] objects, which are children of that media id.
 *
 * For example, given the following conceptual tree:
 * root
 *  +-- Albums
 *  |    +-- Album_A
 *  |    |    +-- Song_1
 *  |    |    +-- Song_2
 *  ...
 *  +-- Artists
 *  ...
 *
 *  Requesting `browseTree["root"]` would return a list that included "Albums", "Artists", and
 *  any other direct children. Taking the media ID of "Albums" ("Albums" in this example),
 *  `browseTree["Albums"]` would return a single item list "Album_A", and, finally,
 *  `browseTree["Album_A"]` would return "Song_1" and "Song_2". Since those are leaf nodes,
 *  requesting `browseTree["Song_1"]` would return null (there aren't any children of it).
 */
class BrowseTree(
    val context: Context,
    musicSource: MusicSource,
    val recentMediaId: String? = null
) {
    private val mediaIdToChildren = mutableMapOf<String, MutableList<MediaMetadataCompat>>()

    /**
     * In this example, there's a single root node (identified by the constant
     * [UAMP_BROWSABLE_ROOT]). The root's children are each album included in the
     * [MusicSource], and the children of each album are the songs on that album.
     * (See [BrowseTree.buildAlbumRoot] for more details.)
     *
     * TODO: Expand to allow more browsing types.
     */
    init {
        val rootList = mediaIdToChildren[UAMP_BROWSABLE_ROOT] ?: mutableListOf()

        val playlistsMetadata = MediaMetadataCompat.Builder().apply {
            id = UAMP_PLAYLISTS_ROOT
            title = context.getString(R.string.playlists_title)
            albumArtUri = RESOURCE_ROOT_URI +
                    context.resources.getResourceEntryName(R.drawable.baseline_library_music_24)
            flag = MediaItem.FLAG_BROWSABLE
        }.build()

        rootList += playlistsMetadata
        mediaIdToChildren[UAMP_BROWSABLE_ROOT] = rootList

        musicSource.forEach { playlist ->
            val playlistId = playlist.ratingKey.toString()
            val playlistChildren = mediaIdToChildren[playlistId] ?: buildPlaylistRoot(playlist)
            for(item in playlist.loadedItems()){
                playlistChildren += MediaMetadataCompat.Builder().from(item).build()
            }
        }
    }

    /**
     * Provide access to the list of children with the `get` operator.
     * i.e.: `browseTree\[UAMP_BROWSABLE_ROOT\]`
     */
    operator fun get(mediaId: String) = mediaIdToChildren[mediaId]

    // Creates a list for the playlist children
    private fun buildPlaylistRoot(mediaItem: Playlist): MutableList<MediaMetadataCompat> {
        val playlistMetadata = MediaMetadataCompat.Builder().from(mediaItem).build()

        // Adds this album to the 'Albums' category.
        val rootList = mediaIdToChildren[UAMP_PLAYLISTS_ROOT] ?: mutableListOf()
        rootList += playlistMetadata
        mediaIdToChildren[UAMP_PLAYLISTS_ROOT] = rootList

        // Insert the album's root with an empty list for its children, and return the list.
        return mutableListOf<MediaMetadataCompat>().also {
            mediaIdToChildren[playlistMetadata.id!!] = it
        }
    }
}

fun MediaMetadataCompat.Builder.from(playlist: Playlist): MediaMetadataCompat.Builder {
    id = playlist.ratingKey.toString()
    title = playlist.title
    mediaUri = playlist.getServer()?.urlFor(playlist.key) ?: playlist.key
    flag = MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
    trackCount = playlist.leafCount.toLong()
    duration = playlist.duration.toLong()

    // To make things easier for *displaying* these, set the display properties as well.
    displayTitle = playlist.title

    // Add downloadStatus to force the creation of an "extras" bundle in the resulting
    // MediaMetadataCompat object. This is needed to send accurate metadata to the
    // media session during updates.
    downloadStatus = MediaDescriptionCompat.STATUS_NOT_DOWNLOADED

    // Allow it to be used in the typical builder style.
    return this
}

fun MediaMetadataCompat.Builder.from(mediaItem: us.berkovitz.plexapi.media.MediaItem): MediaMetadataCompat.Builder {
    if(mediaItem !is Track){
        return this
    }

    id = mediaItem.ratingKey.toString()
    title = mediaItem.title
    mediaUri = mediaItem.getStreamUrl()
    flag = MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
    trackCount = 1
    duration = mediaItem.duration.toLong()

    val iconUrl = if(mediaItem.thumb.isNotEmpty()){
        mediaItem.thumb
    } else if(mediaItem.parentThumb.isNotEmpty()) {
        mediaItem.parentThumb
    } else {
        mediaItem.grandparentThumb
    }

    mediaUri = iconUrl

    // To make things easier for *displaying* these, set the display properties as well.
    displayIconUri = iconUrl
    displayTitle = mediaItem.title

    // Add downloadStatus to force the creation of an "extras" bundle in the resulting
    // MediaMetadataCompat object. This is needed to send accurate metadata to the
    // media session during updates.
    downloadStatus = MediaDescriptionCompat.STATUS_NOT_DOWNLOADED

    // Allow it to be used in the typical builder style.
    return this
}

const val UAMP_BROWSABLE_ROOT = "/"
const val UAMP_EMPTY_ROOT = "@empty@"
const val UAMP_PLAYLISTS_ROOT = "__ALBUMS__"

const val RESOURCE_ROOT_URI = "android.resource://us.berkovitz.plexaaos/drawable/"