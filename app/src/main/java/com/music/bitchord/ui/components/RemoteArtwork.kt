package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.OnlineArtworkRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.remote.RemoteArtworkStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A drawable art URL for [song]: its own thumbnail when it has one, else the
 * embedded picture pulled out of the remote file, else auto-discovered from iTunes
 * using track metadata (artist + title/album).
 *
 * For local songs the thumbnail may be a MediaStore content:// URI — Coil can load
 * those, but files without embedded album art still produce a blank image. We
 * therefore always attempt an iTunes lookup in the background for any local song
 * (content:// thumbnail or no thumbnail at all), preferring the higher-resolution
 * iTunes result over the MediaStore one once it arrives. Online tracks whose
 * thumbnailUrl is already an HTTPS URL are left completely untouched.
 */
@Composable
fun rememberRemoteArtworkUrl(song: Song?): String? {
    val context = LocalContext.current
    val key = song?.localUri ?: song?.localPath ?: song?.videoId
    var resolved by remember(key) { mutableStateOf(song?.thumbnailUrl) }
    LaunchedEffect(key) {
        if (song == null) return@LaunchedEffect
        when {
            // WebDAV / SMB remote: extract from the stream itself.
            song.videoId.startsWith("webdav:") || song.videoId.startsWith("smb:") -> {
                if (resolved.isNullOrBlank()) {
                    resolved = RemoteArtworkStore.resolveArt(context, song)
                }
            }
            // Local file (content:// MediaStore URI, file path, audio: URI, or local: videoId):
            // always attempt online lookup to resolve missing or low-res embedded art.
            song.artist.isNotBlank() && song.title.isNotBlank() &&
                (resolved.isNullOrBlank() ||
                    resolved!!.startsWith("audio:") ||
                    resolved!!.startsWith("content://") ||
                    resolved!!.startsWith("file://") ||
                    song.videoId.startsWith("local:") ||
                    song.localUri != null ||
                    song.localPath != null) -> {
                val found = OnlineArtworkRepository.resolveSongArt(
                    context, song.artist, song.title, song.albumName,
                )
                // Only replace if we found something — never downgrade.
                if (found != null) {
                    resolved = found
                    withContext(Dispatchers.IO) {
                        // Persist the URL into the library cache so it survives
                        // navigation (back-press recomposes with the old StateFlow
                        // value otherwise, making the artwork "forget" itself).
                        val updates = buildMap {
                            put(song.videoId, found)
                            song.localUri?.let { put(it, found) }
                            song.localPath?.let { put(it, found) }
                        }
                        LocalMediaRepository.updateSongsArtwork(context, updates)
                        // Also save as cover.jpg for Poweramp / Namida / Samsung Music if user opted in.
                        if (com.music.bitchord.data.settings.AppSettings.saveAlbumCoverToFolder.value) {
                            OnlineArtworkRepository.saveCoverToAlbumFolder(context, song, found)
                        }
                    }
                }
            }
            // Online track with an HTTPS thumbnail: nothing to do.
            else -> Unit
        }
    }
    return resolved
}

