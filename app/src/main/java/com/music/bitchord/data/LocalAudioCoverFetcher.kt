package com.music.bitchord.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Buffer
import okio.FileSystem
import java.io.File

/**
 * Lazily extracts embedded cover art from local audio files on-demand for Coil.
 * This prevents blocking library scans with thousands of synchronous image decodes
 * and disk writes.
 */
class LocalAudioCoverFetcher(
    private val context: Context,
    private val uriStr: String,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val rawUri = uriStr.removePrefix("audio:")
        val uri = Uri.parse(rawUri)
        val retriever = MediaMetadataRetriever()
        val pic = try {
            val directPath = if (uri.scheme == "file") {
                uri.path
            } else if (uri.scheme.isNullOrBlank() || rawUri.startsWith("/")) {
                rawUri
            } else {
                null
            }

            if (directPath != null) {
                retriever.setDataSource(directPath)
            } else {
                retriever.setDataSource(context, uri)
            }
            retriever.embeddedPicture
        } catch (_: Throwable) {
            null
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        } ?: throw java.io.FileNotFoundException("No embedded artwork found for $rawUri")

        val mimeType = if (pic.size >= 8 &&
            pic[0] == 0x89.toByte() &&
            pic[1] == 0x50.toByte() &&
            pic[2] == 0x4E.toByte() &&
            pic[3] == 0x47.toByte()
        ) {
            "image/png"
        } else {
            "image/jpeg"
        }

        val buffer = Buffer().write(pic)
        return SourceFetchResult(
            source = ImageSource(buffer, FileSystem.SYSTEM),
            mimeType = mimeType,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.startsWith("audio:")) {
                return LocalAudioCoverFetcher(context, data)
            }
            return null
        }
    }
}
