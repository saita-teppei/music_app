package com.saitateppei.opencloudflac.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.saitateppei.opencloudflac.locator
import com.saitateppei.opencloudflac.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

/**
 * 再生サービス(6.2 PlaybackService)。Media3 の MediaLibraryService として実装し、
 * 通知・ロック画面・Android Auto・Wear OS(スマホのメディアセッションを操作)を同じセッションで扱う。
 */
class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var tree: MediaTree
    private val shuffleFixer = ShuffleFixer()

    override fun onCreate() {
        super.onCreate()
        tree = MediaTree(locator.library, locator.playlists)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = handleError(error)
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_TIMELINE_CHANGED)) {
                    shuffleFixer.apply(player)
                }
            }
        })
        val activity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(activity)
            .build()
    }

    /** ファイル破損などで再生できない曲はスキップし、ライブラリで再ダウンロードを提示する(6.4) */
    private fun handleError(error: PlaybackException) {
        val item = player.currentMediaItem
        val id = item?.mediaId?.let { MediaTree.parseTrackId(it)?.second }
        locator.log.error("再生失敗: ${item?.mediaMetadata?.title} (${error.errorCodeName})")
        if (id != null) scope.launch { locator.library.markPlaybackError(id) }
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        player.release()
        super.onDestroy()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private inner class Callback : MediaLibrarySession.Callback {

        /** 接続を許可するクライアントを、自アプリ・システム・Android Auto・Wear OS に限定する(8章) */
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val pkg = controller.packageName
            val allowed = pkg == packageName || pkg in ALLOWED_PACKAGES || controller.isTrusted
            if (!allowed) {
                locator.log.warn("メディアセッションへの接続を拒否: $pkg")
                return MediaSession.ConnectionResult.reject()
            }
            return super.onConnect(session, controller)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future { LibraryResult.ofItem(tree.root(), params) }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = tree.children(parentId)
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        /** コントローラーから来る項目はURIを持たないので、メディアIDから必ず解決し直す */
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            // 音声検索(Android Auto): メディアIDが無く検索語だけが来る
            val query = mediaItems.singleOrNull()?.takeIf { it.mediaId.isEmpty() }?.requestMetadata?.searchQuery
            if (query != null) return@future tree.search(query).toMutableList()
            mediaItems.mapNotNull { tree.item(it.mediaId) }.toMutableList()
        }

        /** Autoで1曲だけ選ばれたら、そのアルバムやプレイリスト全体をキューに入れる */
        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            shuffleFixer.reset()
            val query = mediaItems.singleOrNull()?.takeIf { it.mediaId.isEmpty() }?.requestMetadata?.searchQuery
            if (query != null) return@future MediaItemsWithStartPosition(tree.search(query), 0, 0)
            if (mediaItems.size == 1) {
                tree.expand(mediaItems[0].mediaId)?.let { (list, idx) ->
                    return@future MediaItemsWithStartPosition(list, idx, startPositionMs)
                }
            }
            val resolved = mediaItems.mapNotNull { tree.item(it.mediaId) }
            MediaItemsWithStartPosition(resolved, startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)), startPositionMs)
        }
    }

    companion object {
        private val ALLOWED_PACKAGES = setOf(
            "com.android.systemui",                 // 通知・ロック画面のメディア操作
            "com.android.bluetooth",                // Bluetoothのボタン操作
            "com.google.android.projection.gearhead", // Android Auto
            "com.google.android.wearable.app",      // Wear OS(旧コンパニオン)
            "com.google.android.apps.wear.companion", // Wear OS コンパニオン
            "com.google.android.gms",               // Wear OS のメディア中継
        )
    }
}
