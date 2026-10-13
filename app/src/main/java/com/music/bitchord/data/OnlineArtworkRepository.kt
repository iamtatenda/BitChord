package com.music.bitchord.data

import android.content.Context
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale

/**
 * Automatically discovers high-resolution album and track artwork for local songs
 * that lack embedded covers using multi-strategy lookups (iTunes Search API, Deezer API,
 * and YouTube Music), with persistent disk caching and automatic local cover.jpg export
 * for system-wide music player compatibility (Poweramp, Namida, Samsung Music).
 */
object OnlineArtworkRepository {

    private const val TAG = "OnlineArtwork"
    private const val DISK_FILE = "online_song_art_cache.json"
    private val memoryCache = LinkedHashMap<String, String>(1000, 0.75f, true)
    private val diskLock = Any()
    private var diskLoaded = false

    private val TRACK_NUMBER_REGEX = Regex("""^\d{1,3}[\s.\-_]+""")
    private val FILE_EXT_REGEX = Regex("""\.(mp3|m4a|flac|wav|ogg|opus|aac)$""", RegexOption.IGNORE_CASE)
    private val FEATURING_REGEX = Regex("""(?i)\s*(?:\(|\[)?\s*(?:feat\.?|ft\.?|featuring|with)\b[^\)\]]*(?:\)|\])?""")
    private val VIDEO_DESCRIPTOR_REGEX = Regex("""(?i)\s*(?:\(|\[)\s*(?:official\s+(?:video|audio|music\s+video|visualizer|lyric\s+video)|lyrics?|audio|remastered(?:\s+\d{4})?|\d+kbps|1080p|720p|hd|hq|4k)\s*(?:\)|\])""")
    private val EDITION_REGEX = Regex("""(?i)\s*(?:\(|\[)\s*(?:deluxe|bonus\s+track|expanded|collector's|anniversary|special|remastered)\s+(?:edition|version)?\s*(?:\)|\])""")

    fun sanitizeTitle(raw: String): String {
        return raw
            .replace(FILE_EXT_REGEX, "")
            .replace(TRACK_NUMBER_REGEX, "")
            .replace(FEATURING_REGEX, "")
            .replace(VIDEO_DESCRIPTOR_REGEX, "")
            .trim()
    }

    fun sanitizeArtist(raw: String): String {
        val lower = raw.trim().lowercase(Locale.ROOT)
        if (lower == "unknown artist" || lower == "<unknown>" || lower == "various artists") {
            return ""
        }
        val clean = raw.replace(FEATURING_REGEX, "").trim()
        val parts = clean.split(Regex("""(?i)\s+(?:feat\.?|ft\.?|featuring|&|/)\s+|,"""))
        return parts.firstOrNull()?.trim() ?: clean
    }

    fun sanitizeAlbum(raw: String): String {
        return raw
            .replace(EDITION_REGEX, "")
            .replace(VIDEO_DESCRIPTOR_REGEX, "")
            .trim()
    }

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

    suspend fun resolveAlbumArt(
        context: Context,
        artist: String,
        albumName: String,
    ): String? = withContext(Dispatchers.IO) {
        loadDiskCache(context)
        val cleanArtist = sanitizeArtist(artist)
        val cleanAlbum = sanitizeAlbum(albumName)
        if (cleanAlbum.isBlank()) return@withContext null

        val key = normalizeKey(cleanArtist, cleanAlbum)
        synchronized(memoryCache) { memoryCache[key] }?.let { return@withContext it }

        val query = if (cleanArtist.isNotBlank()) "$cleanArtist $cleanAlbum" else cleanAlbum

        // 1. iTunes Album Search
        var art = fetchItunesArt(query, entity = "album")

        // 2. Deezer Album Search
        if (art == null) {
            art = fetchDeezerAlbumArt(cleanArtist, cleanAlbum)
        }

        // 3. iTunes General Search
        if (art == null) {
            art = fetchItunesArt(query, entity = null)
        }

        // 4. YouTube Music Album Search
        if (art == null) {
            art = fetchYtMusicAlbumArt(query)
        }

        if (art != null) {
            synchronized(memoryCache) { memoryCache[key] = art }
            saveDiskCache(context)
        }
        art
    }

    suspend fun resolveSongArt(
        context: Context,
        artist: String,
        title: String,
        albumName: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        loadDiskCache(context)
        val cleanArtist = sanitizeArtist(artist)
        val cleanTitle = sanitizeTitle(title)
        val cleanAlbum = albumName?.let { sanitizeAlbum(it) }?.takeIf { it.isNotBlank() }

        // If an album name is known, prioritize resolving the full album artwork
        if (cleanAlbum != null) {
            val albumArt = resolveAlbumArt(context, cleanArtist, cleanAlbum)
            if (albumArt != null) return@withContext albumArt
        }

        val key = normalizeKey(cleanArtist, cleanTitle)
        if (key.isBlank()) return@withContext null

        synchronized(memoryCache) { memoryCache[key] }?.let { return@withContext it }

        val query = if (cleanArtist.isNotBlank()) "$cleanArtist $cleanTitle" else cleanTitle

        // 1. iTunes Song Search
        var art = fetchItunesArt(query, entity = "song")

        // 2. Deezer Track Search
        if (art == null) {
            art = fetchDeezerTrackArt(cleanArtist, cleanTitle)
        }

        // 3. iTunes General Search
        if (art == null) {
            art = fetchItunesArt(query, entity = null)
        }

        // 4. YouTube Music Song Search
        if (art == null) {
            art = fetchYtMusicSongArt(query)
        }

        if (art != null) {
            synchronized(memoryCache) { memoryCache[key] = art }
            saveDiskCache(context)
        }
        art
    }

    private fun fetchItunesArt(query: String, entity: String?): String? = runCatching {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val entityParam = if (entity != null) "&entity=$entity" else ""
        val url = "https://itunes.apple.com/search?term=$encoded$entityParam&limit=3"
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
            // Upscale from 100x100 to 1000x1000 high-resolution cover
            rawArt.replace("100x100bb.jpg", "1000x1000bb.jpg")
                .replace("100x100bb.png", "1000x1000bb.png")
                .replace("100x100", "1000x1000")
        }
    }.getOrNull()

    private fun fetchDeezerAlbumArt(artist: String, album: String): String? = runCatching {
        val q = if (artist.isNotBlank()) "$artist $album" else album
        val encoded = URLEncoder.encode(q, "UTF-8")
        val url = "https://api.deezer.com/search/album?q=$encoded&limit=1"
        val req = Request.Builder().url(url).build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val body = resp.body?.string() ?: return@runCatching null
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return@runCatching null
            if (data.length() == 0) return@runCatching null
            val first = data.getJSONObject(0)
            first.optString("cover_xl").takeIf { it.isNotBlank() }
                ?: first.optString("cover_big").takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private fun fetchDeezerTrackArt(artist: String, title: String): String? = runCatching {
        val q = if (artist.isNotBlank()) "$artist $title" else title
        val encoded = URLEncoder.encode(q, "UTF-8")
        val url = "https://api.deezer.com/search/track?q=$encoded&limit=1"
        val req = Request.Builder().url(url).build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val body = resp.body?.string() ?: return@runCatching null
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return@runCatching null
            if (data.length() == 0) return@runCatching null
            val first = data.getJSONObject(0)
            val albumObj = first.optJSONObject("album")
            albumObj?.optString("cover_xl")?.takeIf { it.isNotBlank() }
                ?: albumObj?.optString("cover_big")?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private suspend fun fetchYtMusicAlbumArt(query: String): String? = runCatching {
        YtMusicRepository.search(query, SearchFilter.ALBUMS).getOrNull()
            ?.filterIsInstance<SearchResult.Browse>()
            ?.firstOrNull()
            ?.item
            ?.thumbnailUrl
    }.getOrNull()

    private suspend fun fetchYtMusicSongArt(query: String): String? = runCatching {
        YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
            ?.filterIsInstance<SearchResult.Track>()
            ?.firstOrNull()
            ?.song
            ?.thumbnailUrl
    }.getOrNull()

    /**
     * Saves discovered artwork as `cover.jpg` directly into the album's physical folder.
     * This makes the artwork universally visible to all third-party music players
     * (Poweramp, Namida, Samsung Music, VLC) and triggers the Android MediaScanner.
     */
    fun saveCoverToAlbumFolder(context: Context, song: Song, artworkUrl: String): Boolean {
        val path = song.localPath ?: return false
        val songFile = File(path)
        val folder = songFile.parentFile ?: return false
        if (!folder.exists() || !folder.isDirectory) return false

        val coverFile = File(folder, "cover.jpg")
        if (coverFile.exists() && coverFile.length() > 0) return true

        return runCatching {
            val req = Request.Builder().url(artworkUrl).build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching false
                val bytes = resp.body?.bytes() ?: return@runCatching false
                coverFile.writeBytes(bytes)
                if (com.music.bitchord.data.settings.AppSettings.downloadCreateNomedia.value ||
                    com.music.bitchord.data.settings.AppSettings.noLocalAlbums.value
                ) {
                    val nomedia = File(folder, ".nomedia")
                    if (!nomedia.exists()) {
                        runCatching { nomedia.createNewFile() }
                    }
                }
                // Do NOT call MediaScannerConnection.scanFile with "image/jpeg" here.
                // Doing so would index cover.jpg into the Android gallery/Photos app,
                // flooding the user's photo library with hundreds of album cover images.
                // Third-party music players (Poweramp, Namida, Samsung Music, VLC)
                // discover cover.jpg autonomously through their own folder scans and
                // do NOT require a MediaStore entry for folder artwork to work.
                true
            }
        }.getOrDefault(false)
    }

    fun saveCoverToAlbumFolder(context: Context, songs: List<Song>, artworkUrl: String): Boolean {
        val firstWithPath = songs.firstOrNull { !it.localPath.isNullOrBlank() } ?: return false
        return saveCoverToAlbumFolder(context, firstWithPath, artworkUrl)
    }
}
