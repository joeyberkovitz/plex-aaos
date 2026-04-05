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
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import us.berkovitz.plexaaos.R
import us.berkovitz.plexaaos.extensions.*
import us.berkovitz.plexapi.media.Playlist
import us.berkovitz.plexapi.media.Track
import androidx.core.net.toUri

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
    var musicSource: MusicSource,
    val recentMediaId: String? = null
) {
    private val mediaIdToChildren = mutableMapOf<String, MutableList<MediaItem>>()

    companion object {
        val PAGE_SIZE = 100
    }

    /**
     * In this example, there's a single root node (identified by the constant
     * [UAMP_BROWSABLE_ROOT]). The root's children are each album included in the
     * [MusicSource], and the children of each album are the songs on that album.
     * (See [BrowseTree.buildAlbumRoot] for more details.)
     *
     * TODO: Expand to allow more browsing types.
     */
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

        val playlistsMetadata = MediaItem.Builder().apply {
            setMediaId(UAMP_PLAYLISTS_ROOT)
            setMediaMetadata(MediaMetadata.Builder().apply {
                setTitle(context.getString(R.string.playlists_title))
                setArtworkUri(
                    (RESOURCE_ROOT_URI +
                            context.resources.getResourceEntryName(R.drawable.baseline_library_music_24)).toUri()
                )
                setIsBrowsable(true)
                setIsPlayable(false)
                setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
            }.build())
        }.build()

        rootList += playlistsMetadata
        mediaIdToChildren[UAMP_BROWSABLE_ROOT] = rootList
        refresh()
    }

    fun refresh() {
        musicSource.forEach { playlist ->
            storePlaylist(playlist)
        }
    }

    fun storePlaylist(playlist: Playlist?) {
        if (playlist == null) return

        val playlistId = playlist.ratingKey.toString()
        val playlistChildren = mediaIdToChildren[playlistId] ?: buildPlaylistRoot(playlist)
        val items = playlist.loadedItems()
        for ((idx, item) in items.withIndex()) {
            var pageNum: String? = null
            if (items.size > PAGE_SIZE) {
                pageNum = idx.floorDiv(PAGE_SIZE).toString()
            }

            playlistChildren += MediaItem.Builder().buildMeta(item, playlistId, pageNum)
        }
    }

    /**
     * Provide access to the list of children with the `get` operator.
     * i.e.: `browseTree\[UAMP_BROWSABLE_ROOT\]`
     */
    operator fun get(mediaId: String) = mediaIdToChildren[mediaId]

    fun getByID(parentMediaId: String): MediaItem? {
        var playlistId = parentMediaId
        val splitMediaId = parentMediaId.split('/')
        if (splitMediaId.size >= 2) {
            playlistId = splitMediaId[0]
        }

        val playlist = mediaIdToChildren[playlistId]
        val item = playlist?.find { it.mediaId == parentMediaId }

        return item
    }

    // Creates a list for the playlist children
    private fun buildPlaylistRoot(mediaItem: Playlist): MutableList<MediaItem> {
        val playlistMetadata = MediaItem.Builder().from(mediaItem).build()

        // Adds this album to the 'Albums' category.
        val rootList = mediaIdToChildren[UAMP_PLAYLISTS_ROOT] ?: mutableListOf()
        rootList += playlistMetadata
        mediaIdToChildren[UAMP_PLAYLISTS_ROOT] = rootList

        // Insert the album's root with an empty list for its children, and return the list.
        return mutableListOf<MediaItem>().also {
            mediaIdToChildren[playlistMetadata.mediaId] = it
        }
    }
}

fun MediaItem.Builder.from(playlist: Playlist): MediaItem.Builder {
    setMediaId(playlist.ratingKey.toString())

    setMediaMetadata(MediaMetadata.Builder().apply {
        setTitle(playlist.title)

        setUri(playlist.getServer()?.urlFor(playlist.key) ?: playlist.key)
        setIsBrowsable(true)
        setIsPlayable(false)
        setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
        setTotalTrackCount(playlist.leafCount.toInt())

        if (playlist.duration > 0) {
            setDurationMs(playlist.duration)
        }

        // entries with 'icon' set are always bad URLs
        var iconUri: Uri? = null
        var iconUrl = if (!playlist.composite.isNullOrEmpty() && playlist.icon.isNullOrEmpty()) {
            playlist.composite
        } else {
            null
        }

        if (iconUrl != null) {
            iconUrl = playlist.getServer()!!.urlFor(iconUrl)
            iconUri = AlbumArtContentProvider.mapUri(iconUrl.toUri())
        }

        setArtworkUri(iconUri)

        setDisplayTitle(playlist.title)
    }.build())

    setUri(playlist.getServer()?.urlFor(playlist.key) ?: playlist.key)

    // Allow it to be used in the typical builder style.
    return this
}

fun MediaItem.Builder.from(
    mediaItem: us.berkovitz.plexapi.media.MediaItem,
    playlistId: String? = null,
    pageNum: String? = null
): MediaItem.Builder {
    if (mediaItem !is Track) {
        return this
    }

    if (playlistId == null)
        setMediaId(mediaItem.ratingKey.toString())
    else if (pageNum != null)
        setMediaId("${playlistId}/page_${pageNum}/${mediaItem.ratingKey}")
    else
        setMediaId("${playlistId}/${mediaItem.ratingKey}")

    var iconUri: Uri? = null

    var iconUrl = if (!mediaItem.thumb.isNullOrEmpty()) {
        mediaItem.thumb
    } else if (!mediaItem.parentThumb.isNullOrEmpty()) {
        mediaItem.parentThumb
    } else if (!mediaItem.grandparentThumb.isNullOrEmpty()) {
        mediaItem.grandparentThumb
    } else {
        null
    }

    if (iconUrl != null) {
        iconUrl = mediaItem._server!!.urlFor(iconUrl)
        iconUri = AlbumArtContentProvider.mapUri(iconUrl.toUri())
    }

    var artistName = mediaItem.grandparentTitle
    if (!mediaItem.originalTitle.isNullOrEmpty()) {
        artistName = mediaItem.originalTitle
    }

    setMediaMetadata(MediaMetadata.Builder().apply {
        setTitle(mediaItem.title)
        setIsPlayable(true)
        setIsBrowsable(false)
        setTotalTrackCount(1)
        setTrackNumber(0)
        setDurationMs(mediaItem.duration)
        setArtworkUri(iconUri)

        setDisplayTitle(mediaItem.title)
        setSubtitle(artistName)
        setDescription(mediaItem.parentTitle)
        setArtist(artistName)
        setAlbumTitle(mediaItem.parentTitle)
        setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        setExtras(Bundle().apply {
            this.putString("URI", mediaItem.getStreamUrl())
        })
    }.build())

    val uri = mediaItem.getStreamUrl().toUri()
    setUri(uri)

    return this
}

fun MediaItem.Builder.buildMeta(
    mediaItem: us.berkovitz.plexapi.media.MediaItem,
    playlistId: String? = null,
    pageNum: String? = null
): MediaItem {
    return from(mediaItem, playlistId, pageNum).build()
}


private const val TAG = "BrowseTree"
const val UAMP_BROWSABLE_ROOT = "/"
const val UAMP_EMPTY_ROOT = "@empty@"
const val UAMP_PLAYLISTS_ROOT = "__PLAYLISTS__"

const val RESOURCE_ROOT_URI = "android.resource://us.berkovitz.plexaaos/drawable/"
