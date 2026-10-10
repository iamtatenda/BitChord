package com.music.bitchord.data.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow

class LocalMediaRepository(
    private val localMediaDao: LocalMediaDao,
    private val context: Context
) {
    // Single source of truth for the UI (Paged)
    fun getLocalTracksPaged(): Flow<PagingData<LocalTrackEntity>> {
        return Pager(
            config = PagingConfig(pageSize = 50, enablePlaceholders = true),
            pagingSourceFactory = { localMediaDao.getAllTracksPaged() }
        ).flow
    }

    fun getLocalAlbumsPaged(): Flow<PagingData<LocalAlbumSummary>> {
        return Pager(
            config = PagingConfig(pageSize = 50, enablePlaceholders = true),
            pagingSourceFactory = { localMediaDao.getAllAlbumsPaged() }
        ).flow
    }

    fun getLocalArtists(): Flow<List<String>> = localMediaDao.getAllArtists()

    // Invoked by the UI when the user clicks "Scan Library"
    fun triggerLibraryScan() {
        val workRequest = OneTimeWorkRequest.Builder(LocalLibraryScanWorker::class.java)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "LocalLibraryScan",
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }
    
    // Invoked when user adds a folder via SAF picker
    suspend fun addScanDirectory(treeUri: Uri, displayName: String) {
        val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
        localMediaDao.insertDirectory(
            ScanDirectoryEntity(treeUri.toString(), displayName, System.currentTimeMillis())
        )
        // Automatically scan the new directory
        triggerLibraryScan()
    }
}
