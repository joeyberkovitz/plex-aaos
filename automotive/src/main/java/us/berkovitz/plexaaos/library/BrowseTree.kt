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
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import us.berkovitz.plexaaos.AndroidStorage
import us.berkovitz.plexaaos.R
import us.berkovitz.plexaaos.data.media.MediaItemEntity
import us.berkovitz.plexaaos.data.media.PlaylistEntity
import us.berkovitz.plexapi.media.Playlist
import us.berkovitz.plexapi.media.PlexServer
import us.berkovitz.plexapi.media.Track

fun browsableRootMediaItems(ctx: Context): List<MediaItem> {
    return listOf(
        MediaItem.Builder().apply {
            setMediaId(UAMP_PLAYLISTS_ROOT)
            setMediaMetadata(MediaMetadata.Builder().apply {
                setTitle(ctx.getString(R.string.playlists_title))
                setArtworkUri(
                    (RESOURCE_ROOT_URI +
                            ctx.resources.getResourceEntryName(R.drawable.baseline_library_music_24)).toUri()
                )
                setIsBrowsable(true)
                setIsPlayable(false)
                setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
            }.build())
        }.build()
    )
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

fun MediaItem.Builder.from(server: PlexServer, playlist: PlaylistEntity): MediaItem.Builder {
    setMediaId(playlist.id.toString())

    setMediaMetadata(MediaMetadata.Builder().apply {
        setTitle(playlist.name)

        setUri(server.urlFor(playlist.key))
        setIsBrowsable(true)
        setIsPlayable(false)
        setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
        setTotalTrackCount(1) // TODO

//        if (playlist.duration > 0) {
//            setDurationMs(playlist.duration)
//        }

        // entries with 'icon' set are always bad URLs
        var iconUri: Uri? = null
        var iconUrl = if (!playlist.iconUri.isNullOrEmpty()) {
            playlist.iconUri
        } else {
            null
        }

        if (iconUrl != null) {
            iconUrl = server.urlFor(iconUrl)
            iconUri = AlbumArtContentProvider.mapUri(iconUrl.toUri())
        }

        setArtworkUri(iconUri)

        setDisplayTitle(playlist.name)
    }.build())

    setUri(server.urlFor(playlist.key))

    // Allow it to be used in the typical builder style.
    return this
}

fun MediaItem.Builder.from(
    mediaItem: us.berkovitz.plexapi.media.MediaItem,
    audioQuality: Int,
    transcodeQuality: Int,
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

    val mediaBitrate = mediaItem.media?.firstOrNull()?.bitrate ?: 0
    val shouldTranscode = audioQuality != AndroidStorage.MAXIMUM_AUDIO_QUALITY && mediaBitrate > audioQuality
    var transcodeStreamUrl = mediaItem.getTranscodeStreamUrl(transcodeQuality)

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
            this.putString("TRANSCODE_URI", transcodeStreamUrl)
        })
    }.build())

    if (shouldTranscode) {
        setUri(transcodeStreamUrl.toUri())
    } else {
        setUri(mediaItem.getStreamUrl().toUri())
    }

    return this
}

fun MediaItem.Builder.buildMeta(
    mediaItem: us.berkovitz.plexapi.media.MediaItem,
    audioQuality: Int,
    transcodeQuality: Int,
    playlistId: String? = null,
    pageNum: String? = null
): MediaItem {
    return from(mediaItem, audioQuality, transcodeQuality, playlistId, pageNum).build()
}

fun MediaItem.Builder.buildMeta(
    server: PlexServer,
    mediaItem: MediaItemEntity,
    audioQuality: Int,
    transcodeQuality: Int,
    playlistId: String? = null,
    pageNum: String? = null
): MediaItem {
    if (playlistId == null)
        setMediaId(mediaItem.id.toString())
    else if (pageNum != null)
        setMediaId("${playlistId}/page_${pageNum}/${mediaItem.id}")
    else
        setMediaId("${playlistId}/${mediaItem.id}")

    var iconUri: Uri? = null



    var iconUrl = if (!mediaItem.iconUri.isNullOrEmpty()) {
        mediaItem.iconUri
    } else {
        null
    }

    if (iconUrl != null) {
        iconUrl = server.urlFor(iconUrl)
        iconUri = AlbumArtContentProvider.mapUri(iconUrl.toUri())
    }

    val mediaBitrate = mediaItem.bitrate
    val shouldTranscode = audioQuality != AndroidStorage.MAXIMUM_AUDIO_QUALITY && mediaBitrate > audioQuality

    var transcodeUri: String? = null
    if(shouldTranscode) {
        val track = Track(mediaItem.id, mediaItem.key, 0, 0, "",
            null, null, null, "Track", mediaItem.name, mediaItem.name,
            null, null, null, null,null,
            null, null, null, null, null, null, null, 0,
            0, null, null, null, null, null, null, null,
            null, mediaItem.durationMs, null, mediaItem.updatedAt.toString(), null, null,
            )
        track.setServer(server)
        transcodeUri = track.getTranscodeStreamUrl(audioQuality)
    }

    val mediaUri = server.urlFor(mediaItem.uri)

    setMediaMetadata(MediaMetadata.Builder().apply {
        setTitle(mediaItem.name)
        setIsPlayable(true)
        setIsBrowsable(false)
        setTotalTrackCount(1)
        setTrackNumber(0)
        setDurationMs(mediaItem.durationMs)
        setArtworkUri(iconUri)

        setDisplayTitle(mediaItem.name)
        setSubtitle(mediaItem.artistName)
        setDescription(mediaItem.albumName)
        setArtist(mediaItem.artistName)
        setAlbumTitle(mediaItem.albumName)
        setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        setExtras(Bundle().apply {
            this.putString("URI", mediaUri)
            this.putString("TRANSCODE_URI", transcodeUri)
        })
    }.build())

    if (shouldTranscode && transcodeUri != null) {
        setUri(transcodeUri.toUri())
    } else {
        setUri(mediaUri.toUri())
    }
    return this.build()
}


private const val TAG = "BrowseTree"
const val UAMP_BROWSABLE_ROOT = "/"
const val UAMP_EMPTY_ROOT = "@empty@"
const val UAMP_PLAYLISTS_ROOT = "__PLAYLISTS__"

const val RESOURCE_ROOT_URI = "android.resource://us.berkovitz.plexaaos/drawable/"
