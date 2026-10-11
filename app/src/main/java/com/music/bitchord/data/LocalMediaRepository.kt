package com.music.bitchord.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.music.bitchord.data.DebugLog as Log
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.DownloadStore
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object LocalMediaRepository {

    private const val TAG = "BitChord"
    private const val MIN_LOCAL_MUSIC_DURATION_MS = 30_000L

    // `mp4` only ever reaches this list as an audio row — MediaStore files a
    // video .mp4 under Video, not Audio — so it is the audio-only MP4s Dolby
    // and Apple music downloads come as (AC-4, E-AC-3, ALAC). `dsf`/`dff` are
    // DSD, which DsdExtractor plays.
    private val localMusicExtensions = setOf(
        "mp3", "m4a", "mp4", "flac", "ogg", "opus", "aac", "wav", "wave", "webm", "alac", "wma", "aiff", "dsf", "dff",
    )

    private val nonMusicPathSegments = listOf(
        "/alarms/",
        "/notifications/",
        "/ringtones/",
        "/podcasts/",
        "/audiobooks/",
        "/recordings/",
        "/voice recorder/",
        "/sound_recorder/",
        "/call_rec/",
        "/whatsapp voice notes/",
    )

    /** Check if storage/audio permission is granted to query device local music. */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Retrieves all songs in the `Music/BitChord` directory, combining app downloads
     * with any local audio files present in that folder.
     *
     * The download record is the better source for a title and a credit — it
     * holds what the catalogue row said, not what a scanner guessed off a
     * filename — but it only started carrying the album at all recently, and
     * an album page's rows never name their own release. So whatever the media
     * scanner read off each file is collected alongside and used to fill the
     * gaps, which is what keeps the Albums tab from being empty for everything
     * downloaded before that field existed.
     */
    suspend fun getDownloadedSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val appDownloads = Downloads.getDownloadedSongs(context)
        val knownUris = appDownloads.mapNotNull { it.localUri }.toSet()
        val extraSongs = mutableListOf<Song>()

        /** uri to what the media scanner read off that file. */
        val scanned = mutableMapOf<String, ScannedTags>()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                )
                val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("%${DownloadStore.FOLDER}%")

                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                    val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                    val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: continue
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                        val albumId = cursor.getLong(albumIdCol)
                        val tags = ScannedTags(
                            albumName = cursor.getString(albumCol).cleanTag(),
                            artworkUrl = if (albumId > 0) {
                                ContentUris.withAppendedId(albumArtBaseUri, albumId).toString()
                            } else {
                                null
                            },
                            dateAddedSeconds = cursor.getLong(dateAddedCol),
                            dateModifiedSeconds = cursor.getLong(dateModifiedCol),
                        )
                        scanned[contentUri] = tags
                        if (contentUri !in knownUris && isAudioFileName(name)) {
                            extraSongs.add(buildSongFromUri(context, contentUri, name, tags))
                        }
                    }
                }
            } else {
                val folder = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_MUSIC,
                    ),
                    DownloadStore.FOLDER,
                )
                if (folder.exists() && folder.isDirectory) {
                    folder.walkTopDown().forEach { file ->
                        if (file.isFile && isAudioFileName(file.name)) {
                            val uriStr = Uri.fromFile(file).toString()
                            if (uriStr !in knownUris) {
                                val modified = file.lastModified().takeIf { it > 0 }?.div(1_000)
                                extraSongs.add(
                                    buildSongFromUri(
                                        context,
                                        uriStr,
                                        file.name,
                                        ScannedTags(null, null, modified, modified),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Failed scanning Music/BitChord directory: ${it.message}") }

        val filled = appDownloads.map { song ->
            val uri = song.localUri ?: return@map song
            val tags = scanned[uri] ?: return@map song
            song.copy(
                albumName = song.albumName ?: tags.albumName,
                localDateAddedSeconds = tags.dateAddedSeconds,
                localDateModifiedSeconds = tags.dateModifiedSeconds,
            )
        }

        (filled + extraSongs).distinctBy { it.localUri ?: it.videoId }
    }

    /**
     * The parts of a scanner row worth reading back — everything else about a
     * download is better known from the record that made it.
     */
    private class ScannedTags(
        val albumName: String?,
        val artworkUrl: String?,
        val dateAddedSeconds: Long?,
        val dateModifiedSeconds: Long?,
    )

    /** What MediaStore writes into a column it has nothing for. */
    private fun String?.cleanTag(): String? =
        takeUnless { it.isNullOrBlank() || it == "<unknown>" }

    /**
     * Queries MediaStore for all audio files available on the device.
     */
    suspend fun getLocalMusic(context: Context): List<Song> = withContext(Dispatchers.IO) {
        if (!hasStoragePermission(context)) return@withContext emptyList()

        val songs = mutableListOf<Song>()
        // This scan runs over every audio file on the device, which includes
        // whatever this app has downloaded into Music/BitChord alongside
        // everything else — but by content URI, the only thing MediaStore
        // offers here, that download is indistinguishable from a file the
        // user copied on by hand. Reversing [Downloads.saved] hands a
        // downloaded track its real YouTube id back, which is what lets
        // PlaybackTracker recognise it as a video worth registering a play
        // for — a content URI fails its id check on purpose, since most rows
        // here really are just local files with nothing to sync.
        val videoIdByUri = Downloads.saved.value.entries.associate { (id, uri) -> uri to id }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )

        val filterNonMusic = AppSettings.filterNonMusicAudio.value
        val selection = if (filterNonMusic) {
            val nonSpecial = "(${MediaStore.Audio.Media.IS_ALARM} = 0 OR ${MediaStore.Audio.Media.IS_ALARM} IS NULL)" +
                " AND (${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 OR ${MediaStore.Audio.Media.IS_NOTIFICATION} IS NULL)" +
                " AND (${MediaStore.Audio.Media.IS_RINGTONE} = 0 OR ${MediaStore.Audio.Media.IS_RINGTONE} IS NULL)" +
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) " AND (${MediaStore.Audio.Media.IS_PODCAST} = 0 OR ${MediaStore.Audio.Media.IS_PODCAST} IS NULL)" else "")
            "$nonSpecial AND (${MediaStore.Audio.Media.DURATION} >= ?" +
                " OR ${MediaStore.Audio.Media.DURATION} <= 0" +
                " OR ${MediaStore.Audio.Media.DURATION} IS NULL)"
        } else {
            null
        }
        val selectionArgs = if (filterNonMusic) {
            arrayOf(MIN_LOCAL_MUSIC_DURATION_MS.toString())
        } else {
            null
        }
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val contentUris = mutableListOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val volumes = MediaStore.getExternalVolumeNames(context)
                for (vol in volumes) {
                    val u = MediaStore.Audio.Media.getContentUri(vol)
                    if (u !in contentUris) contentUris.add(u)
                }
            }
        }

        val seenUris = mutableSetOf<String>()
        for (volumeUri in contentUris) {
            runCatching {
                context.contentResolver.query(
                    volumeUri,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder,
                )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val displayNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

                val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(displayNameCol).orEmpty()
                    val rawTitle = cursor.getString(titleCol)
                    val rawArtist = cursor.getString(artistCol)
                    val rawAlbum = cursor.getString(albumCol)
                    val albumId = cursor.getLong(albumIdCol)
                    val durationMs = cursor.getLong(durationCol)
                    val path = cursor.getString(dataCol)

                    if (filterNonMusic && !isEligibleLocalMusic(durationMs, displayName, path)) continue
                    if (!isInSelectedFolder(path, AppSettings.localMusicFolderUri.value)) continue

                    val contentUri = ContentUris.withAppendedId(volumeUri, id).toString()
                    if (!seenUris.add(contentUri)) continue
                    val title = rawTitle.cleanTag()?.removeAudioExtension()
                        ?: displayName.substringBeforeLast('.').takeIf { it.isNotBlank() }
                        ?: "Track $id"
                    val artist = rawArtist.takeUnless { it.isNullOrBlank() || it == "<unknown>" } ?: "Unknown Artist"
                    val albumName = rawAlbum.takeUnless { it.isNullOrBlank() || it == "<unknown>" }
                    
                    val folderArt = path?.let { p ->
                        val parent = File(p).parentFile
                        if (parent != null && parent.exists()) {
                            File(parent, "cover.jpg").takeIf { it.exists() }?.let { Uri.fromFile(it).toString() }
                                ?: File(parent, "folder.jpg").takeIf { it.exists() }?.let { Uri.fromFile(it).toString() }
                                ?: File(parent, "Cover.jpg").takeIf { it.exists() }?.let { Uri.fromFile(it).toString() }
                        } else null
                    }
                    val artworkUrl = if (albumId > 0) ContentUris.withAppendedId(albumArtBaseUri, albumId).toString() else folderArt
                    val durationText = durationMs.takeIf { it > 0 }?.let(::formatDuration)

                    songs.add(
                        Song(
                            videoId = videoIdByUri[contentUri] ?: contentUri,
                            title = title,
                            artist = artist,
                            thumbnailUrl = artworkUrl,
                            durationText = durationText,
                            albumName = albumName,
                            localUri = contentUri,
                            localPath = path,
                            localDateAddedSeconds = cursor.getLong(dateAddedCol),
                            localDateModifiedSeconds = cursor.getLong(dateModifiedCol),
                        )
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Failed scanning volume $volumeUri: ${it.message}") }
        }

        // Also perform direct file discovery on selected folder and standard music paths
        // to find songs not indexed by Android's system MediaScanner (Namida parity).
        val knownUris = songs.mapNotNull { it.localUri }.toMutableSet()
        val knownPaths = songs.mapNotNull { it.localPath?.normalizedPath() }.toMutableSet()

        val selectedUri = AppSettings.localMusicFolderUri.value
        val selectedPath = selectedFolderPath(selectedUri)
        val targetFolders = mutableListOf<File>()

        if (!selectedPath.isNullOrBlank()) {
            val f = File(selectedPath)
            if (f.exists() && f.isDirectory) targetFolders.add(f)
        } else {
            val stdMusic = File("/storage/emulated/0/Music")
            if (stdMusic.exists() && stdMusic.isDirectory) targetFolders.add(stdMusic)
            val stdDownload = File("/storage/emulated/0/Download")
            if (stdDownload.exists() && stdDownload.isDirectory) targetFolders.add(stdDownload)
        }

        for (folder in targetFolders) {
            runCatching {
                folder.walkTopDown().maxDepth(15).forEach { file ->
                    if (file.isFile && isAudioFileName(file.name)) {
                        val norm = file.absolutePath.normalizedPath()
                        if (norm !in knownPaths) {
                            if (filterNonMusic && !isEligibleLocalMusic(0, file.name, file.absolutePath)) return@forEach
                            val song = buildSongFromFile(context, file)
                            if (song != null) {
                                knownPaths.add(norm)
                                val u = song.localUri ?: return@forEach
                                if (knownUris.add(u)) {
                                    songs.add(song)
                                }
                            }
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "Direct folder scan failed for ${folder.path}: ${it.message}") }
        }

        // Also if SAF tree URI is configured, walk using DocumentFile for folders restricted by scoped storage
        if (selectedUri.isNotBlank()) {
            runCatching {
                val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(selectedUri))
                if (rootDoc != null && rootDoc.exists()) {
                    fun scanSaf(dir: DocumentFile, depth: Int) {
                        if (depth <= 0) return
                        for (doc in dir.listFiles()) {
                            if (doc.isDirectory) {
                                scanSaf(doc, depth - 1)
                            } else if (doc.isFile) {
                                val docName = doc.name ?: continue
                                if (isAudioFileName(docName)) {
                                    val uriStr = doc.uri.toString()
                                    if (uriStr !in knownUris) {
                                        val song = buildSongFromDocument(context, doc)
                                        if (song != null && knownUris.add(uriStr)) {
                                            songs.add(song)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    scanSaf(rootDoc, 15)
                }
            }.onFailure { Log.w(TAG, "SAF tree scan failed: ${it.message}") }
        }

        songs
    }

    /** Human-readable path for the folder setting without exposing provider internals. */
    fun selectedFolderLabel(treeUri: String?): String? =
        if (treeUri.isNullOrBlank()) null else selectedFolder(treeUri)?.label

    /** Decoded physical filesystem path for a SAF folder tree, preserving exact casing. */
    fun selectedFolderPath(treeUri: String?): String? =
        if (treeUri.isNullOrBlank()) null else selectedFolder(treeUri)?.absolutePath

    internal fun isInSelectedFolder(path: String?, treeUri: String?): Boolean {
        if (treeUri.isNullOrBlank()) return true
        val folder = selectedFolder(treeUri) ?: return true
        if (path.isNullOrBlank()) return true
        val candidate = path.normalizedPath()
        val target = folder.normalizedPath
        if (candidate == target || candidate.startsWith("$target/")) return true
        val rel = folder.label.lowercase(Locale.ROOT)
        if (rel.isNotBlank() && candidate.contains("/$rel/")) return true
        return false
    }

    private data class SelectedFolder(
        val absolutePath: String,
        val label: String,
        val normalizedPath: String = absolutePath.normalizedPath(),
    )

    private fun selectedFolder(treeUri: String): SelectedFolder? = runCatching {
        val parsedUri = Uri.parse(treeUri)
        val rawDocId = runCatching { DocumentsContract.getTreeDocumentId(parsedUri) }.getOrNull()
            ?: parsedUri.lastPathSegment.orEmpty()
        val documentId = runCatching { java.net.URLDecoder.decode(rawDocId, "UTF-8") }.getOrDefault(rawDocId)
        if (documentId.startsWith("raw:")) {
            val raw = documentId.removePrefix("raw:").replace('\\', '/').trimEnd('/')
            return@runCatching SelectedFolder(raw, raw.substringAfterLast('/'))
        }
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':', "").trim('/')
        val volumeRoot = if (volume.equals("primary", ignoreCase = true)) {
            "/storage/emulated/0"
        } else {
            "/storage/$volume"
        }
        val exactPath = listOf(volumeRoot, relative).filter { it.isNotBlank() }.joinToString("/").replace('\\', '/').trimEnd('/')
        SelectedFolder(
            absolutePath = exactPath,
            label = relative.ifBlank { volume },
        )
    }.getOrNull()

    private fun String.normalizedPath(): String {
        var p = replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)
        if (p.startsWith("/sdcard/")) {
            p = "/storage/emulated/0/" + p.removePrefix("/sdcard/")
        } else if (p == "/sdcard") {
            p = "/storage/emulated/0"
        } else if (p.startsWith("/storage/self/primary/")) {
            p = "/storage/emulated/0/" + p.removePrefix("/storage/self/primary/")
        }
        return p
    }

    /**
     * MediaStore's `IS_MUSIC` flag is advisory and often includes notification
     * sounds, voice notes and recorder output. Keep this second gate independent
     * of scanner metadata so the same bad rows stay out across Android vendors.
     */
    internal fun isEligibleLocalMusic(durationMs: Long, displayName: String, path: String?): Boolean {
        if (durationMs in 1 until MIN_LOCAL_MUSIC_DURATION_MS) return false

        val fileName = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: displayName
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in localMusicExtensions) return false

        val normalizedPath = path
            ?.replace('\\', '/')
            ?.lowercase(Locale.ROOT)
            ?: return true
        return nonMusicPathSegments.none(normalizedPath::contains)
    }

    private fun String.removeAudioExtension(): String {
        val extension = substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (extension in localMusicExtensions || extension == "wav") {
            substringBeforeLast('.').ifBlank { this }
        } else {
            this
        }
    }

    private fun isAudioFileName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") ||
            lower.endsWith(".flac") || lower.endsWith(".wav") ||
            lower.endsWith(".ogg") || lower.endsWith(".opus") ||
            lower.endsWith(".aac") || lower.endsWith(".webm") ||
            lower.endsWith(".3gp")
    }

    /**
     * A song built from a file in the downloads folder the app has no record of
     * — one copied in by hand, or left behind by an install whose record is
     * gone. The file's own tags are the only thing there is to go on; [scanned]
     * fills in what the retriever couldn't read, since the media scanner and
     * `MediaMetadataRetriever` do not agree on every container.
     */
    private fun buildSongFromUri(
        context: Context,
        uriStr: String,
        fileName: String,
        scanned: ScannedTags? = null,
    ): Song {
        var title = fileName.substringBeforeLast(".")
        var artist = "Unknown Artist"
        var albumName: String? = null
        var durationText: String? = null

        runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, Uri.parse(uriStr))
            val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val metaDur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()

            if (!metaTitle.isNullOrBlank()) title = metaTitle
            if (!metaArtist.isNullOrBlank()) artist = metaArtist
            albumName = metaAlbum.cleanTag()
            if (metaDur != null && metaDur > 0) durationText = formatDuration(metaDur)
            retriever.release()
        }

        return Song(
            videoId = uriStr,
            title = title,
            artist = artist,
            thumbnailUrl = scanned?.artworkUrl,
            durationText = durationText,
            albumName = albumName ?: scanned?.albumName,
            localUri = uriStr,
            localDateAddedSeconds = scanned?.dateAddedSeconds,
            localDateModifiedSeconds = scanned?.dateModifiedSeconds,
        )
    }

    private fun formatDuration(ms: Long): String {
        val totalSecs = ms / 1000
        val minutes = totalSecs / 60
        val secs = totalSecs % 60
        return String.format(Locale.ROOT, "%d:%02d", minutes, secs)
    }

    private fun buildSongFromFile(context: Context, file: File): Song? {
        val path = file.absolutePath
        val name = file.name
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext !in localMusicExtensions) return null

        var title = name.substringBeforeLast('.')
        var artist = "Unknown Artist"
        var album: String? = null
        var durationText: String? = null

        runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val metaDur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()

            if (!metaTitle.isNullOrBlank()) title = metaTitle
            if (!metaArtist.isNullOrBlank()) artist = metaArtist
            album = metaAlbum.cleanTag()
            if (metaDur != null && metaDur > 0) {
                durationText = formatDuration(metaDur)
            }
            retriever.release()
        }

        val parent = file.parentFile
        val folderArt = if (parent != null && parent.exists()) {
            File(parent, "cover.jpg").takeIf { it.exists() }?.let { Uri.fromFile(it).toString() }
                ?: File(parent, "folder.jpg").takeIf { it.exists() }?.let { Uri.fromFile(it).toString() }
                ?: File(parent, "Cover.jpg").takeIf { it.exists() }?.let { Uri.fromFile(it).toString() }
        } else null

        val modified = file.lastModified().takeIf { it > 0 }?.div(1000)
        val fileUri = Uri.fromFile(file).toString()

        return Song(
            videoId = fileUri,
            title = title,
            artist = artist,
            thumbnailUrl = folderArt,
            durationText = durationText,
            albumName = album,
            localUri = fileUri,
            localPath = path,
            localDateAddedSeconds = modified,
            localDateModifiedSeconds = modified,
        )
    }

    private fun buildSongFromDocument(context: Context, doc: DocumentFile): Song? {
        val name = doc.name ?: return null
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext !in localMusicExtensions) return null

        var title = name.substringBeforeLast('.')
        var artist = "Unknown Artist"
        var album: String? = null
        var durationText: String? = null

        runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, doc.uri)
            val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val metaDur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()

            if (!metaTitle.isNullOrBlank()) title = metaTitle
            if (!metaArtist.isNullOrBlank()) artist = metaArtist
            album = metaAlbum.cleanTag()
            if (metaDur != null && metaDur > 0) {
                durationText = formatDuration(metaDur)
            }
            retriever.release()
        }

        val modified = doc.lastModified().takeIf { it > 0 }?.div(1000)
        val uriStr = doc.uri.toString()

        return Song(
            videoId = uriStr,
            title = title,
            artist = artist,
            thumbnailUrl = null,
            durationText = durationText,
            albumName = album,
            localUri = uriStr,
            localPath = null,
            localDateAddedSeconds = modified,
            localDateModifiedSeconds = modified,
        )
    }
}
