package com.music.bitchord.data.local

data class LocalTrackEntity(
    val id: String,
    val title: String,
    val artist: String,
    val albumArtist: String?,
    val album: String,
    val genre: String?,
    val composer: String?,
    val durationMs: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val filePath: String,
    val folderUri: String,
    val sizeBytes: Long,
    val dateModified: Long,
    val albumArtPath: String?,
    val isOrphaned: Boolean = false
)

data class ScanDirectoryEntity(
    val treeUri: String,
    val displayName: String,
    val lastScanned: Long
)

data class TrackModificationState(
    val filePath: String,
    val dateModified: Long
)

data class LocalAlbumSummary(
    val album: String,
    val artist: String,
    val albumArtPath: String?
)
