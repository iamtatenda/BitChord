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

    override suspend fun fetch(): FetchResult? {
        val rawUri = uriStr.removePrefix("audio:")
        val uri = Uri.parse(rawUri)
        val retriever = MediaMetadataRetriever()
        val pic = try {
            if (uri.scheme == "file") {
                val path = uri.path ?: return null
                retriever.setDataSource(path)
            } else {
                retriever.setDataSource(context, uri)
            }
            retriever.embeddedPicture
        } catch (_: Throwable) {
            null
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        } ?: return null

        val buffer = Buffer().write(pic)
        return SourceFetchResult(
            source = ImageSource(buffer, context),
            mimeType = "image/jpeg",
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
