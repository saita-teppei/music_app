package com.saitateppei.opencloudflac

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.saitateppei.opencloudflac.sync.SyncActionReceiver
import com.saitateppei.opencloudflac.ui.MainActivity

object Notifications {
    const val CH_DOWNLOAD = "downloads"
    const val CH_SYNC = "sync"
    const val CH_PROBLEM = "problems"

    const val ID_DOWNLOAD = 1001
    const val ID_SYNC = 1002
    const val ID_PROBLEM = 1003

    const val EXTRA_ROUTE = "route"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_DOWNLOAD, "ダウンロード", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_SYNC, "新しい曲の提案", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_PROBLEM, "エラー", NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
    }

    fun openAppIntent(ctx: Context, route: String): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, route.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notifyProblem(ctx: Context, title: String, text: String) {
        if (!canNotify(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_PROBLEM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx, "downloads"))
            .build()
        post(ctx, ID_PROBLEM, n)
    }

    /** [canNotify] で確認済み。許可が直前に取り消された場合も落ちないようにする。 */
    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, id: Int, n: android.app.Notification) {
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) { }
    }

    /** F11: 新しい曲・更新された曲の提案。「すべてダウンロード」で承認するとキューに入る。 */
    fun notifyProposals(ctx: Context, count: Int) {
        if (!canNotify(ctx) || count <= 0) return
        val accept = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, SyncActionReceiver::class.java).setAction(SyncActionReceiver.ACTION_ACCEPT_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CH_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("新しい曲が見つかりました")
            .setContentText("${count}曲をダウンロードできます")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx, "sync"))
            .addAction(0, "すべてダウンロード", accept)
            .build()
        post(ctx, ID_SYNC, n)
    }

    fun cancelProposals(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_SYNC)
}
