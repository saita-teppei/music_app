package com.saitateppei.opencloudflac.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.saitateppei.opencloudflac.data.db.PlaylistRow
import com.saitateppei.opencloudflac.data.db.TrackEntity
import com.saitateppei.opencloudflac.playback.PlayerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** ジャケット。外部の画像ライブラリは使わず BitmapFactory で読む。 */
@Composable
fun Artwork(file: File?, size: Dp, modifier: Modifier = Modifier) {
    val bmp by produceState<ImageBitmap?>(null, file?.path) {
        value = file?.takeIf { it.exists() }?.let { f ->
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }
        }
    }
    Box(
        modifier.size(size).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(b, null, Modifier.size(size), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun TrackRow(
    track: TrackEntity,
    cover: File?,
    playing: Boolean,
    queued: Boolean,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDeleteLocal: () -> Unit,
    onRedownload: () -> Unit,
    extraActions: @Composable (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val needsAttention = track.playbackError || track.hasUpdate
    ListItem(
        modifier = Modifier.clickable(enabled = track.isDownloaded, onClick = onClick),
        leadingContent = { Artwork(cover, 44.dp) },
        headlineContent = {
            Text(
                track.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = when {
                    playing -> MaterialTheme.colorScheme.primary
                    !track.isDownloaded -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        },
        supportingContent = {
            val status = when {
                queued -> " · ダウンロード待ち"
                !track.isDownloaded -> " · 未ダウンロード"
                track.playbackError -> " · 再生できませんでした"
                track.hasUpdate -> " · サーバーで更新あり"
                else -> ""
            }
            Text("${track.displayArtist} · ${track.displayAlbum}$status", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (needsAttention) Icon(Icons.Default.Warning, "要確認", tint = MaterialTheme.colorScheme.error)
                extraActions?.invoke()
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "メニュー") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("プレイリストに追加") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                            onClick = { menu = false; onAddToPlaylist() },
                        )
                        if (!track.isDownloaded || needsAttention) DropdownMenuItem(
                            text = { Text(if (track.isDownloaded) "再ダウンロード" else "ダウンロード") },
                            leadingIcon = { Icon(Icons.Default.Download, null) },
                            onClick = { menu = false; onRedownload() },
                        )
                        if (track.isDownloaded) DropdownMenuItem(
                            text = { Text("ローカル保存を削除") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menu = false; onDeleteLocal() },
                        )
                    }
                }
            }
        },
    )
}

@Composable
fun AddToPlaylistDialog(
    playlists: List<PlaylistRow>,
    onPick: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(playlists.isEmpty()) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("プレイリストに追加") },
        text = {
            if (creating) {
                OutlinedTextField(name, { name = it }, label = { Text("新しいプレイリスト名") }, singleLine = true)
            } else {
                LazyColumn {
                    items(playlists, key = { it.id }) { p ->
                        ListItem(
                            headlineContent = { Text(p.name) },
                            supportingContent = { Text("${p.itemCount}曲") },
                            modifier = Modifier.clickable { onPick(p.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (creating) TextButton(onClick = { onCreate(name.ifBlank { "新しいプレイリスト" }) }) { Text("作成して追加") }
            else TextButton(onClick = { creating = true }) { Text("新規作成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, onOk: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onOk(text) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, onOk: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onOk) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
fun MiniPlayer(state: PlayerUiState, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit) {
    if (!state.hasItem) return
    Surface(tonalElevation = 3.dp) {
        Column {
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(state.artworkUri?.path?.let { File(it) }, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(state.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onToggle) {
                    Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "一時停止" else "再生")
                }
                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "次の曲") }
            }
        }
    }
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** メッセージが来たらスナックバーに出す */
@Composable
fun MessageEffect(message: String?, show: suspend (String) -> Unit, consume: () -> Unit) {
    LaunchedEffect(message) {
        if (message != null) { show(message); consume() }
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / (1L shl 10).toDouble())
    else -> "$b B"
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
