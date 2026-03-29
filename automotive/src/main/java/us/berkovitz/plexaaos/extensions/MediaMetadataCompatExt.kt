package us.berkovitz.plexaaos.extensions

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import us.berkovitz.plexaaos.library.AlbumArtContentProvider
import us.berkovitz.plexapi.media.Playlist
import us.berkovitz.plexapi.media.Track

const val METADATA_KEY_DURATION = "us.berkovitz.plexaaos.METADATA_KEY_DURATION"

@OptIn(UnstableApi::class)
fun buildBrowsableItem(
    mediaId: String,
    title: String?,
    artworkUri: Uri? = null
): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtworkUri(artworkUri)
        .setIsBrowsable(true)
        .setIsPlayable(false)
        .build()

    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(metadata)
        .build()
}

@OptIn(UnstableApi::class)
fun Playlist.toMediaItem(): MediaItem {
    var iconUrl = if (!composite.isNullOrEmpty() && icon.isNullOrEmpty()) {
        composite
    } else {
        null
    }

    if (iconUrl != null) {
        iconUrl = getServer()!!.urlFor(iconUrl)
        iconUrl = AlbumArtContentProvider.mapUri(Uri.parse(iconUrl)).toString()
    }

    val artworkUri = iconUrl?.let { Uri.parse(it) }
    val extras = Bundle().apply {
        if (duration > 0) {
            putLong(METADATA_KEY_DURATION, duration)
        }
    }

    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtworkUri(artworkUri)
        .setIsBrowsable(true)
        .setIsPlayable(false)
        .setExtras(extras)
        .build()

    return MediaItem.Builder()
        .setMediaId(ratingKey.toString())
        .setMediaMetadata(metadata)
        .build()
}

@OptIn(UnstableApi::class)
fun Track.toMediaItem(playlistId: String? = null): MediaItem {
    val mediaId = if (playlistId == null) ratingKey.toString()
    else "$playlistId/$ratingKey"

    var iconUrl = when {
        !thumb.isNullOrEmpty() -> thumb
        !parentThumb.isNullOrEmpty() -> parentThumb
        !grandparentThumb.isNullOrEmpty() -> grandparentThumb
        else -> null
    }

    if (iconUrl != null) {
        iconUrl = _server!!.urlFor(iconUrl)
        iconUrl = AlbumArtContentProvider.mapUri(Uri.parse(iconUrl)).toString()
    }

    var artistName = grandparentTitle
    if (!originalTitle.isNullOrEmpty()) {
        artistName = originalTitle
    }

    val artworkUri = iconUrl?.let { Uri.parse(it) }
    val extras = Bundle().apply {
        putLong(METADATA_KEY_DURATION, duration)
    }

    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artistName)
        .setAlbumTitle(parentTitle)
        .setArtworkUri(artworkUri)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setExtras(extras)
        .build()

    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(getStreamUrl())
        .setMediaMetadata(metadata)
        .build()
}
