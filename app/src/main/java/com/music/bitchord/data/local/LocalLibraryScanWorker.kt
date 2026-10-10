package com.music.bitchord.data.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.music.bitchord.playback.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

class LocalLibraryScanWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    data class ScannedFile(
        val isDirectory: Boolean,
        val docId: String,
        val mimeType: String?,
        val size: Long,
        val lastModified: Long,
        val name: String
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dbHelper = LocalMediaDatabaseHelper.getInstance(applicationContext)
        val dao = LocalMediaDao(dbHelper)
        
        val directories = dao.getAllDirectories()
        val existingStates = dao.getModificationStates().associate { it.filePath to it.dateModified }
        val activePaths = mutableListOf<String>()
        val tracksToInsert = mutableListOf<LocalTrackEntity>()

        dao.markAllAsOrphaned()

        for (dir in directories) {
            val rootUri = Uri.parse(dir.treeUri)
            val rootDocId = DocumentsContract.getTreeDocumentId(rootUri)
            walkDirectory(rootUri, rootDocId, existingStates, activePaths, tracksToInsert, dao)
        }

        if (tracksToInsert.isNotEmpty()) {
            dao.insertTracks(tracksToInsert)
        }
        
        activePaths.chunked(900).forEach { chunk ->
            dao.markAsActive(chunk)
        }
        dao.deleteOrphanedTracks()
        
        return@withContext Result.success()
    }

    private suspend fun walkDirectory(
        treeUri: Uri,
        parentDocId: String,
        existing: Map<String, Long>, 
        activePaths: MutableList<String>,
        toInsert: MutableList<LocalTrackEntity>,
        dao: LocalMediaDao
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        
        val filesList = mutableListOf<ScannedFile>()
        
        applicationContext.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                filesList.add(ScannedFile(
                    isDirectory = cursor.getString(mimeIdx) == DocumentsContract.Document.MIME_TYPE_DIR,
                    docId = cursor.getString(idIdx),
                    mimeType = cursor.getString(mimeIdx),
                    lastModified = cursor.getLong(modIdx),
                    size = cursor.getLong(sizeIdx),
                    name = cursor.getString(nameIdx)
                ))
            }
        }

        for (file in filesList) {
            if (file.isDirectory) {
                walkDirectory(treeUri, file.docId, existing, activePaths, toInsert, dao)
            } else if (file.mimeType?.startsWith("audio/") == true) {
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, file.docId).toString()
                activePaths.add(fileUri)
                
                val existingModTime = existing[fileUri]
                if (existingModTime == null || existingModTime < file.lastModified) {
                    val track = extractMetadata(fileUri, treeUri.toString(), file.size, file.lastModified, file.name)
                    if (track != null) {
                        toInsert.add(track)
                        if (toInsert.size >= 100) {
                            dao.insertTracks(toInsert.toList())
                            toInsert.clear()
                        }
                    }
                }
            }
        }
    }
    
    private fun extractMetadata(fileUriStr: String, rootUriStr: String, sizeBytes: Long, lastModified: Long, fileName: String): LocalTrackEntity? {
        var pfd: android.os.ParcelFileDescriptor? = null
        var tempLink: File? = null
        try {
            val fileUri = Uri.parse(fileUriStr)
            pfd = applicationContext.contentResolver.openFileDescriptor(fileUri, "r") ?: return null
            
            val extension = fileName.substringAfterLast('.', "").lowercase()
            tempLink = File(applicationContext.cacheDir, "scan_link_${System.nanoTime()}.$extension")
            android.system.Os.symlink("/proc/self/fd/${pfd.fd}", tempLink.absolutePath)
            
            val audioFile = AudioFileIO.read(tempLink)
            val tag = audioFile.tag ?: return null
            val header = audioFile.audioHeader

            val title = tag.getFirst(FieldKey.TITLE).takeIf { it.isNotBlank() } ?: fileName
            val artist = tag.getFirst(FieldKey.ARTIST).takeIf { it.isNotBlank() } ?: "Unknown Artist"
            val album = tag.getFirst(FieldKey.ALBUM).takeIf { it.isNotBlank() } ?: "Unknown Album"
            val albumArtist = tag.getFirst(FieldKey.ALBUM_ARTIST)
            val genre = tag.getFirst(FieldKey.GENRE)
            val composer = tag.getFirst(FieldKey.COMPOSER)
            val trackNumber = tag.getFirst(FieldKey.TRACK).toIntOrNull()
            val discNumber = tag.getFirst(FieldKey.DISC_NO).toIntOrNull()
            val year = tag.getFirst(FieldKey.YEAR).toIntOrNull()
            
            var albumArtPath: String? = null
            val artwork = tag.firstArtwork
            if (artwork != null && artwork.binaryData != null) {
                val artDir = File(applicationContext.filesDir, "album_art").apply { mkdirs() }
                
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val hashBytes = digest.digest(("$album-$artist").toByteArray(Charsets.UTF_8))
                val hashString = hashBytes.joinToString("") { "%02x".format(it) }
                
                val artFile = File(artDir, "${hashString}.jpg")
                if (!artFile.exists()) artFile.writeBytes(artwork.binaryData)
                albumArtPath = artFile.absolutePath
            }

            return LocalTrackEntity(
                id = fileUriStr.hashCode().toString(),
                title = title,
                artist = artist,
                albumArtist = albumArtist,
                album = album,
                genre = genre,
                composer = composer,
                durationMs = (header.trackLength * 1000).toLong(),
                trackNumber = trackNumber,
                discNumber = discNumber,
                year = year,
                filePath = fileUriStr,
                folderUri = rootUriStr,
                sizeBytes = sizeBytes,
                dateModified = lastModified,
                albumArtPath = albumArtPath,
                isOrphaned = false
            )
        } catch (e: org.jaudiotagger.audio.exceptions.CannotReadException) {
            TrackLog.e("ScanWorker", "CannotReadException parsing $fileUriStr: ${e.message}")
        } catch (e: org.jaudiotagger.tag.TagException) {
            TrackLog.e("ScanWorker", "TagException parsing $fileUriStr: ${e.message}")
        } catch (e: org.jaudiotagger.audio.exceptions.ReadOnlyFileException) {
            TrackLog.e("ScanWorker", "ReadOnlyFileException parsing $fileUriStr: ${e.message}")
        } catch (e: org.jaudiotagger.audio.exceptions.InvalidAudioFrameException) {
            TrackLog.e("ScanWorker", "InvalidAudioFrameException parsing $fileUriStr: ${e.message}")
        } catch (e: Exception) {
            TrackLog.e("ScanWorker", "Unexpected error parsing $fileUriStr: ${e.message}")
        } finally {
            pfd?.close()
            tempLink?.delete()
        }
        return null
    }
}
