package com.saitateppei.opencloudflac.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.saitateppei.opencloudflac.data.AppLog
import com.saitateppei.opencloudflac.data.LibraryRepository
import com.saitateppei.opencloudflac.data.db.DownloadTaskDao
import com.saitateppei.opencloudflac.data.db.DownloadTaskEntity
import com.saitateppei.opencloudflac.data.webdav.DavEntry
import java.io.File
import java.util.concurrent.TimeUnit

/** ダウンロードキューの操作(6.2 DownloadWorker の enqueue/pause/resume/cancel) */
class DownloadController(
    private val context: Context,
    private val dao: DownloadTaskDao,
    private val library: LibraryRepository,
    private val log: AppLog,
) {
    fun observeAll() = dao.observeAll()
    fun observeIds() = dao.observeIds()

    /** サーバー閲覧で選んだファイルをキューへ。最新が保存済みの曲は飛ばす。 */
    suspend fun enqueueEntries(serverId: Long, entries: List<DavEntry>, syncFolderId: Long? = null): Int {
        var n = 0
        for (e in entries.filter { it.isFlac }) {
            val t = library.upsertFromRemote(serverId, e, syncFolderId)
            if (t.isDownloaded && !t.hasUpdate && !t.playbackError) continue
            if (enqueueTrack(t.id)) n++
        }
        kick()
        return n
    }

    suspend fun enqueueTracks(trackIds: List<Long>): Int {
        var n = 0
        for (id in trackIds) if (enqueueTrack(id)) n++
        kick()
        return n
    }

    private suspend fun enqueueTrack(trackId: Long): Boolean {
        val existing = dao.get(trackId)
        if (existing != null && existing.state in listOf(DownloadTaskEntity.STATE_QUEUED, DownloadTaskEntity.STATE_RUNNING)) return false
        dao.upsert(
            existing?.copy(state = DownloadTaskEntity.STATE_QUEUED, lastError = null, attempts = 0)
                ?: DownloadTaskEntity(trackId = trackId)
        )
        return true
    }

    suspend fun pause(trackId: Long) = dao.setState(trackId, DownloadTaskEntity.STATE_PAUSED)

    suspend fun resume(trackId: Long) {
        val t = dao.get(trackId) ?: return
        dao.update(t.copy(state = DownloadTaskEntity.STATE_QUEUED, lastError = null, attempts = 0))
        kick()
    }

    suspend fun pauseAll() = dao.pauseAll()

    suspend fun resumeAll() {
        dao.resumeAll(); kick()
    }

    suspend fun cancel(trackId: Long) {
        dao.delete(trackId)
        partFile(trackId).delete()
    }

    fun partFile(trackId: Long) = File(library.musicDir, "$trackId.flac.part")

    /** キュー処理のワーカーを起動する。実行中なら後ろに連結し、新しい項目も拾う。 */
    fun kick() {
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    companion object {
        const val WORK_NAME = "download-queue"
    }
}
