package com.saitateppei.opencloudflac.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.saitateppei.opencloudflac.data.AppLog
import com.saitateppei.opencloudflac.data.db.AppDatabase
import com.saitateppei.opencloudflac.data.db.SyncFolderEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** 同期フォルダの登録と走査の起動(F11) */
class SyncController(private val context: Context, private val db: AppDatabase, private val log: AppLog) {
    private val dao = db.syncDao()
    private val wm get() = WorkManager.getInstance(context)

    fun observeFolders() = dao.observeFolders()
    fun observeProposals() = db.trackDao().observeProposals()

    suspend fun addFolder(serverId: Long, path: String, mode: String = SyncFolderEntity.MODE_SUGGEST): Boolean {
        val id = dao.insertFolder(SyncFolderEntity(serverId = serverId, remotePath = path, mode = mode))
        if (id > 0) log.info("同期フォルダ登録: $path")
        return id > 0
    }

    suspend fun setMode(f: SyncFolderEntity, mode: String) = dao.updateFolder(f.copy(mode = mode))
    suspend fun removeFolder(id: Long) = dao.deleteFolder(id)

    suspend fun dismiss(trackIds: List<Long>) = db.trackDao().dismiss(trackIds)

    /** 1日数回(8時間ごと)の定期走査。到達不可なら静かにスキップする。 */
    fun schedulePeriodic() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints())
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** アプリ起動時の走査。直近に走査済みのフォルダは飛ばす。 */
    fun scanOnLaunch() = runOnce(force = false, policy = ExistingWorkPolicy.KEEP)

    /** 手動の「今すぐ同期」。間隔の制限を無視する。 */
    fun syncNow() = runOnce(force = true, policy = ExistingWorkPolicy.REPLACE)

    fun observeRunning(): Flow<Boolean> = wm.getWorkInfosForUniqueWorkFlow(ONCE).map { list ->
        list.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    }

    private fun runOnce(force: Boolean, policy: ExistingWorkPolicy) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(SyncWorker.KEY_FORCE to force))
            .build()
        wm.enqueueUniqueWork(ONCE, policy, req)
    }

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    companion object {
        const val PERIODIC = "sync-periodic"
        const val ONCE = "sync-once"
        const val PERIOD_HOURS = 8L
        /** 自動走査の最短間隔(共有サーバーへの負荷対策) */
        const val MIN_INTERVAL_MS = 2 * 60 * 60 * 1000L
    }
}
