package com.music.bitchord.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale

/**
 * Automatically discovers high-resolution album and track artwork for local songs
 * that lack embedded covers using the iTunes Search API, with persistent disk caching.
 */
object OnlineArtworkRepository {

    private const val TAG = "OnlineArtwork"
    private const val DISK_FILE = "online_song_art_cache.json"
    private val memoryCache = LinkedHashMap<String, String>(500, 0.75f, true)
    private val diskLock = Any()
    private var diskLoaded = false

    private fun normalizeKey(artist: String, titleOrAlbum: String): String {
        val a = artist.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        val t = titleOrAlbum.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        return "$a:$t"
    }

    private fun loadDiskCache(context: Context) {
        if (diskLoaded) return
        val file = File(context.filesDir, DISK_FILE)
        if (!file.exists()) {
            diskLoaded = true
            return
        }
        runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            synchronized(memoryCache) {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = json.optString(k)
                    if (v.isNotBlank()) memoryCache[k] = v
                }
            }
            diskLoaded = true
        }
    }

    private fun saveDiskCache(context: Context) {
        val file = File(context.filesDir, DISK_FILE)
        val tmp = File(context.filesDir, "$DISK_FILE.tmp")
        synchronized(diskLock) {
            runCatching {
                val json = JSONObject()
                synchronized(memoryCache) {
                    for ((k, v) in memoryCache) {
                        json.put(k, v)
                    }
                }
                tmp.writeText(json.toString(), Charsets.UTF_8)
                if (tmp.exists()) {
                    if (file.exists()) file.delete()
                    tmp.renameTo(file)
                }
            }
        }
    }

    suspend fun resolveSongArt(
        context: Context,
        artist: String,
        title: String,
        albumName: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        loadDiskCache(context)
        val term1 = if (!albumName.isNullOrBlank()) albumName else title
        val key = normalizeKey(artist, term1)
        if (key.isBlank()) return@withContext null

        synchronized(memoryCache) { memoryCache[key] }?.let { return@withContext it }

        val query = if (!albumName.isNullOrBlank()) "$artist $albumName" else "$artist $title"
        val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(query, "UTF-8")}&entity=song&limit=1"

        val foundArt = runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: return@runCatching null
                if (results.length() == 0) return@runCatching null
                val first = results.getJSONObject(0)
                val rawArt = first.optString("artworkUrl100").takeIf { it.isNotBlank() } ?: return@runCatching null
                // Upgrade 100x100 to 600x600 high resolution artwork
                rawArt.replace("100x100bb.jpg", "600x600bb.jpg")
                    .replace("100x100bb.png", "600x600bb.png")
            }
        }.getOrNull()

        if (foundArt != null) {
            synchronized(memoryCache) { memoryCache[key] = foundArt }
            saveDiskCache(context)
        }
        foundArt
    }
}
