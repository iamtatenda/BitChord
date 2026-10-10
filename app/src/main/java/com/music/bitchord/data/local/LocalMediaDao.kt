package com.music.bitchord.data.local

import android.content.ContentValues
import android.database.Cursor
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class LocalMediaDao(private val dbHelper: LocalMediaDatabaseHelper) {

    fun getAllTracksPaged(): PagingSource<Int, LocalTrackEntity> {
        return object : PagingSource<Int, LocalTrackEntity>() {
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, LocalTrackEntity> = withContext(Dispatchers.IO) {
                try {
                    val position = params.key ?: 0
                    val limit = params.loadSize
                    val db = dbHelper.readableDatabase
                    val cursor = db.rawQuery("SELECT * FROM local_tracks WHERE isOrphaned = 0 ORDER BY title ASC LIMIT ? OFFSET ?", arrayOf(limit.toString(), position.toString()))
                    val data = mutableListOf<LocalTrackEntity>()
                    cursor.use {
                        while (it.moveToNext()) {
                            data.add(mapCursorToTrack(it))
                        }
                    }
                    val nextKey = if (data.isEmpty()) null else position + data.size
                    val prevKey = if (position == 0) null else maxOf(0, position - limit)
                    LoadResult.Page(data, prevKey, nextKey)
                } catch (e: Exception) {
                    LoadResult.Error(e)
                }
            }
            override fun getRefreshKey(state: PagingState<Int, LocalTrackEntity>): Int? = state.anchorPosition
        }
    }

    suspend fun insertTracks(tracks: List<LocalTrackEntity>) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (track in tracks) {
                val cv = ContentValues().apply {
                    put("id", track.id)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("albumArtist", track.albumArtist)
                    put("album", track.album)
                    put("genre", track.genre)
                    put("composer", track.composer)
                    put("durationMs", track.durationMs)
                    put("trackNumber", track.trackNumber)
                    put("discNumber", track.discNumber)
                    put("year", track.year)
                    put("filePath", track.filePath)
                    put("folderUri", track.folderUri)
                    put("sizeBytes", track.sizeBytes)
                    put("dateModified", track.dateModified)
                    put("albumArtPath", track.albumArtPath)
                    put("isOrphaned", if (track.isOrphaned) 1 else 0)
                }
                db.insertWithOnConflict("local_tracks", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
            dbHelper.notifyChange()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun markAllAsOrphaned() = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.execSQL("UPDATE local_tracks SET isOrphaned = 1")
    }

    suspend fun markAsActive(paths: List<String>) = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext
        val db = dbHelper.writableDatabase
        val placeholders = paths.joinToString(",") { "?" }
        db.execSQL("UPDATE local_tracks SET isOrphaned = 0 WHERE filePath IN ($placeholders)", paths.toTypedArray())
    }

    suspend fun deleteOrphanedTracks() = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.execSQL("DELETE FROM local_tracks WHERE isOrphaned = 1")
        dbHelper.notifyChange()
    }

    suspend fun getModificationStates(): List<TrackModificationState> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT filePath, dateModified FROM local_tracks", null)
        val data = mutableListOf<TrackModificationState>()
        cursor.use {
            val pathIdx = it.getColumnIndex("filePath")
            val dateIdx = it.getColumnIndex("dateModified")
            while (it.moveToNext()) {
                data.add(TrackModificationState(it.getString(pathIdx), it.getLong(dateIdx)))
            }
        }
        data
    }

    fun getAllAlbumsPaged(): PagingSource<Int, LocalAlbumSummary> {
        return object : PagingSource<Int, LocalAlbumSummary>() {
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, LocalAlbumSummary> = withContext(Dispatchers.IO) {
                try {
                    val position = params.key ?: 0
                    val limit = params.loadSize
                    val db = dbHelper.readableDatabase
                    val cursor = db.rawQuery("SELECT DISTINCT album, artist, albumArtPath FROM local_tracks WHERE isOrphaned = 0 ORDER BY album ASC LIMIT ? OFFSET ?", arrayOf(limit.toString(), position.toString()))
                    val data = mutableListOf<LocalAlbumSummary>()
                    cursor.use {
                        val albumIdx = it.getColumnIndex("album")
                        val artistIdx = it.getColumnIndex("artist")
                        val artIdx = it.getColumnIndex("albumArtPath")
                        while (it.moveToNext()) {
                            data.add(LocalAlbumSummary(it.getString(albumIdx), it.getString(artistIdx), if (it.isNull(artIdx)) null else it.getString(artIdx)))
                        }
                    }
                    val nextKey = if (data.isEmpty()) null else position + data.size
                    val prevKey = if (position == 0) null else maxOf(0, position - limit)
                    LoadResult.Page(data, prevKey, nextKey)
                } catch (e: Exception) {
                    LoadResult.Error(e)
                }
            }
            override fun getRefreshKey(state: PagingState<Int, LocalAlbumSummary>): Int? = state.anchorPosition
        }
    }

    fun getAllArtists(): Flow<List<String>> = dbHelper.dbChanges.map {
        withContext(Dispatchers.IO) {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery("SELECT DISTINCT artist FROM local_tracks WHERE isOrphaned = 0 ORDER BY artist ASC", null)
            val data = mutableListOf<String>()
            cursor.use {
                while (it.moveToNext()) {
                    data.add(it.getString(0))
                }
            }
            data
        }
    }

    suspend fun getAllDirectories(): List<ScanDirectoryEntity> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM local_scan_directories", null)
        val data = mutableListOf<ScanDirectoryEntity>()
        cursor.use {
            val uriIdx = it.getColumnIndex("treeUri")
            val nameIdx = it.getColumnIndex("displayName")
            val scanIdx = it.getColumnIndex("lastScanned")
            while (it.moveToNext()) {
                data.add(ScanDirectoryEntity(it.getString(uriIdx), it.getString(nameIdx), it.getLong(scanIdx)))
            }
        }
        data
    }

    suspend fun insertDirectory(dir: ScanDirectoryEntity) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("treeUri", dir.treeUri)
            put("displayName", dir.displayName)
            put("lastScanned", dir.lastScanned)
        }
        db.insertWithOnConflict("local_scan_directories", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun mapCursorToTrack(it: Cursor): LocalTrackEntity {
        return LocalTrackEntity(
            id = it.getString(it.getColumnIndexOrThrow("id")),
            title = it.getString(it.getColumnIndexOrThrow("title")),
            artist = it.getString(it.getColumnIndexOrThrow("artist")),
            albumArtist = if (it.isNull(it.getColumnIndexOrThrow("albumArtist"))) null else it.getString(it.getColumnIndexOrThrow("albumArtist")),
            album = it.getString(it.getColumnIndexOrThrow("album")),
            genre = if (it.isNull(it.getColumnIndexOrThrow("genre"))) null else it.getString(it.getColumnIndexOrThrow("genre")),
            composer = if (it.isNull(it.getColumnIndexOrThrow("composer"))) null else it.getString(it.getColumnIndexOrThrow("composer")),
            durationMs = it.getLong(it.getColumnIndexOrThrow("durationMs")),
            trackNumber = if (it.isNull(it.getColumnIndexOrThrow("trackNumber"))) null else it.getInt(it.getColumnIndexOrThrow("trackNumber")),
            discNumber = if (it.isNull(it.getColumnIndexOrThrow("discNumber"))) null else it.getInt(it.getColumnIndexOrThrow("discNumber")),
            year = if (it.isNull(it.getColumnIndexOrThrow("year"))) null else it.getInt(it.getColumnIndexOrThrow("year")),
            filePath = it.getString(it.getColumnIndexOrThrow("filePath")),
            folderUri = it.getString(it.getColumnIndexOrThrow("folderUri")),
            sizeBytes = it.getLong(it.getColumnIndexOrThrow("sizeBytes")),
            dateModified = it.getLong(it.getColumnIndexOrThrow("dateModified")),
            albumArtPath = if (it.isNull(it.getColumnIndexOrThrow("albumArtPath"))) null else it.getString(it.getColumnIndexOrThrow("albumArtPath")),
            isOrphaned = it.getInt(it.getColumnIndexOrThrow("isOrphaned")) == 1
        )
    }
}
