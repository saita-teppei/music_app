package com.saitateppei.opencloudflac.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.saitateppei.opencloudflac.data.db.TrackEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerUiState(
    val connected: Boolean = false,
    val hasItem: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUri: android.net.Uri? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val currentMediaId: String? = null,
)

/** 画面から再生サービスを操作する MediaController の薄いラッパー */
class PlayerConnection(context: Context) {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private var controller: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            // 接続に失敗してもアプリ全体を落とさない(再生操作が効かないだけにする)
            controller = try { future.get() } catch (e: Exception) {
                android.util.Log.e("PlayerConnection", "再生サービスに接続できません", e); null
            }?.also { it.addListener(listener) }
            refresh()
        }, MoreExecutors.directExecutor())
    }

    fun refresh() {
        val c = controller ?: return
        val md: MediaMetadata = c.mediaMetadata
        _state.value = PlayerUiState(
            connected = true,
            hasItem = c.currentMediaItem != null,
            isPlaying = c.isPlaying,
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            album = md.albumTitle?.toString().orEmpty(),
            artworkUri = md.artworkUri,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            currentMediaId = c.currentMediaItem?.mediaId,
        )
    }

    /** [parent] は MediaTree の親ID。保存済みの曲だけを並べて再生する。 */
    fun play(parent: String, tracks: List<TrackEntity>, startIndex: Int, shuffle: Boolean = false) {
        val c = controller ?: return
        val playable = tracks.filter { it.isDownloaded }
        if (playable.isEmpty()) return
        val startId = tracks.getOrNull(startIndex)?.id
        val start = playable.indexOfFirst { it.id == startId }.coerceAtLeast(0)
        val items = playable.map { MediaItem.Builder().setMediaId(MediaTree.trackMediaId(parent, it.id)).build() }
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(items, if (shuffle) 0 else start, 0)
        c.prepare()
        c.play()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()
    fun seekTo(ms: Long) = controller?.seekTo(ms)
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }
}
