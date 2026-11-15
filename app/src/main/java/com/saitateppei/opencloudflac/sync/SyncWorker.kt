package com.saitateppei.opencloudflac.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saitateppei.opencloudflac.App
import com.saitateppei.opencloudflac.Notifications
import com.saitateppei.opencloudflac.data.db.SyncDirCacheEntity
import com.saitateppei.opencloudflac.data.db.SyncFolderEntity
import com.saitateppei.opencloudflac.data.webdav.DavException
import com.saitateppei.opencloudflac.locator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 登録フォルダを走査して新しい曲・更新された曲を検出し、通知で提案する(F11) */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val loc = (ctx.applicationContext as App).locator

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val cfg = loc.settings.config() ?: return@withContext Result.success()
        val dao = loc.db.syncDao()
        val folders = dao.folders().filter { it.serverId == cfg.serverId }
        if (folders.isEmpty()) return@withContext Result.success()

        val before = loc.db.trackDao().proposalCount()
        val autoQueued = mutableListOf<Long>()
        for (f in folders) {
            val now = System.currentTimeMillis()
            if (!force && f.lastScanAt != null && now - f.lastScanAt < SyncController.MIN_INTERVAL_MS) continue
            val scanner = SyncScanner(
                list = { p -> loc.dav.list(cfg, p, 1) },
                cachedEtag = { p -> dao.cachedEtag(f.id, p) },
                putEtag = { p, e -> dao.putCache(SyncDirCacheEntity(f.id, p, e)) },
                onFile = { e ->
                    val t = loc.library.upsertFromRemote(cfg.serverId, e, f.id)
                    val isNew = !t.isDownloaded
                    if (f.mode == SyncFolderEntity.MODE_AUTO && !t.dismissed && (isNew || t.hasUpdate)) autoQueued += t.id
                },
            )
            try {
                val n = scanner.scan(f.remotePath)
                dao.updateFolder(f.copy(lastScanAt = now))
                loc.log.info("同期走査: ${f.remotePath} (FLAC ${n}件, 要求${scanner.requests}回)")
            } catch (e: DavException.Unreachable) {
                // tailnet未接続などは静かにスキップして次回に回す
                loc.log.info("同期スキップ(到達不可): ${f.remotePath}")
                return@withContext Result.success()
            } catch (e: DavException.Unauthorized) {
                loc.log.error("同期失敗(認証): ${e.message}")
                return@withContext Result.success()
            } catch (e: Exception) {
                loc.log.warn("同期失敗: ${f.remotePath} (${e.message})")
            }
        }
        if (autoQueued.isNotEmpty()) {
            val n = loc.downloads.enqueueTracks(autoQueued)
            if (n > 0) loc.log.info("自動ダウンロードに追加: ${n}曲")
        }
        val after = loc.db.trackDao().proposalCount()
        if (after > before) Notifications.notifyProposals(applicationContext, after)
        Result.success()
    }

    companion object {
        const val KEY_FORCE = "force"
    }
}

/** 通知の「すべてダウンロード」 */
class SyncActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ACCEPT_ALL) return
        val pending = goAsync()
        val loc = context.locator
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ids = loc.db.trackDao().proposalIds()
                loc.downloads.enqueueTracks(ids)
                Notifications.cancelProposals(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT_ALL = "com.saitateppei.opencloudflac.ACCEPT_ALL_PROPOSALS"
    }
}
