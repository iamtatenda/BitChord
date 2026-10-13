package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.data.OnlineArtworkRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.remote.RemoteArtworkStore

/**
 * A drawable art URL for [song]: its own thumbnail when it has one, else the
 * embedded picture pulled out of the remote file, else auto-discovered from iTunes
 * using track metadata (artist + title/album).
 */
@Composable
fun rememberRemoteArtworkUrl(song: Song?): String? {
    val context = LocalContext.current
    val key = song?.localUri ?: song?.localPath ?: song?.videoId
    var resolved by remember(key) { mutableStateOf(song?.thumbnailUrl) }
    LaunchedEffect(key) {
        if (song != null && (resolved == null || resolved.isNullOrBlank())) {
            if (song.videoId.startsWith("webdav:") || song.videoId.startsWith("smb:")) {
                resolved = RemoteArtworkStore.resolveArt(context, song)
            } else if (song.artist.isNotBlank() && song.title.isNotBlank()) {
                val found = OnlineArtworkRepository.resolveSongArt(context, song.artist, song.title, song.albumName)
                if (found != null) resolved = found
            }
        }
    }
    return resolved
}
