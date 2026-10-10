package com.music.bitchord.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

class LocalMediaDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "bitchord_local_media.db"
        const val DATABASE_VERSION = 1
        
        @Volatile
        private var instance: LocalMediaDatabaseHelper? = null

        fun getInstance(context: Context): LocalMediaDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: LocalMediaDatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _dbChanges = MutableSharedFlow<Unit>(replay = 1)
    val dbChanges: Flow<Unit> = _dbChanges.asSharedFlow().onStart { emit(Unit) }

    fun notifyChange() {
        _dbChanges.tryEmit(Unit)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE local_tracks (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                albumArtist TEXT,
                album TEXT NOT NULL,
                genre TEXT,
                composer TEXT,
                durationMs INTEGER NOT NULL,
                trackNumber INTEGER,
                discNumber INTEGER,
                year INTEGER,
                filePath TEXT NOT NULL,
                folderUri TEXT NOT NULL,
                sizeBytes INTEGER NOT NULL,
                dateModified INTEGER NOT NULL,
                albumArtPath TEXT,
                isOrphaned INTEGER NOT NULL DEFAULT 0
            )
        """)
        
        db.execSQL("""
            CREATE TABLE local_scan_directories (
                treeUri TEXT PRIMARY KEY,
                displayName TEXT NOT NULL,
                lastScanned INTEGER NOT NULL
            )
        """)
        
        db.execSQL("CREATE INDEX idx_local_tracks_filePath ON local_tracks(filePath)")
        db.execSQL("CREATE INDEX idx_local_tracks_album ON local_tracks(album)")
        db.execSQL("CREATE INDEX idx_local_tracks_artist ON local_tracks(artist)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS local_tracks")
        db.execSQL("DROP TABLE IF EXISTS local_scan_directories")
        onCreate(db)
    }
}
