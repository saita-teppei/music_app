package com.saitateppei.opencloudflac.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.saitateppei.opencloudflac.App
import com.saitateppei.opencloudflac.Notifications
import com.saitateppei.opencloudflac.R
import com.saitateppei.opencloudflac.data.db.DownloadTaskEntity
import com.saitateppei.opencloudflac.data.db.TrackEntity
import com.saitateppei.opencloudflac.data.webdav.DavException
import com.saitateppei.opencloudflac.data.webdav.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * キューを先頭から順に取得する。一時ファイル(.part)に書き込み、完了・サイズ確認後に正式名へリネームし、
 * タグを読む。中断しても .part と bytes_done から Range で再開する(5.3, 6.4)。
 */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val loc = (ctx.applicationContext as App).locator
    private val dao = loc.db.downloadDao()
    private val trackDao = loc.db.trackDao()
    private val log = loc.log

    private sealed interface Outcome {
        data object Done : Outcome
        data object Paused : Outcome
        data class Transient(val msg: String) : Outcome
        data class Fatal(val msg: String) : Outcome
        data class Auth(val msg: String) : Outcome
        data object NoSpace : Outcome
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        dao.resetRunning()
        val cfg = loc.settings.config()
        if (cfg == null) {
            log.warn("ダウンロード: サーバー設定またはトークンがありません")
            return@withContext Result.success()
        }
        trySetForeground("ダウンロードを準備しています")

        while (true) {
            val task = dao.nextQueued() ?: break
            val track = trackDao.get(task.trackId)
            if (track == null) { dao.delete(task.trackId); continue }
            dao.setState(track.id, DownloadTaskEntity.STATE_RUNNING)
            log.info("ダウンロード開始: ${track.remotePath}")

            when (val o = downloadOne(cfg, track, task)) {
                Outcome.Done -> log.info("ダウンロード完了: ${track.remotePath}")
                Outcome.Paused -> log.info("ダウンロード中断: ${track.remotePath}")
                is Outcome.Fatal -> {
                    dao.setState(track.id, DownloadTaskEntity.STATE_FAILED, o.msg)
                    log.error("ダウンロード失敗: ${track.remotePath} (${o.msg})")
                }
                is Outcome.Auth -> {
                    // トークン失効: キューは保持して停止し、設定へ誘導する
                    dao.setState(track.id, DownloadTaskEntity.STATE_QUEUED, o.msg)
                    log.error("ダウンロード停止(認証): ${o.msg}")
                    Notifications.notifyProblem(applicationContext, "ダウンロードを停止しました", o.msg)
                    return@withContext Result.success()
                }
                Outcome.NoSpace -> {
                    dao.setState(track.id, DownloadTaskEntity.STATE_PAUSED, "ストレージの空き容量が不足しています")
                    dao.pauseAll()
                    log.error("ダウンロード停止: 空き容量不足")
                    Notifications.notifyProblem(applicationContext, "ダウンロードを停止しました", "ストレージの空き容量が不足しています")
                    return@withContext Result.success()
                }
                is Outcome.Transient -> {
                    val cur = dao.get(track.id) ?: task
                    val attempts = cur.attempts + 1
                    if (attempts >= MAX_ATTEMPTS) {
                        dao.update(cur.copy(state = DownloadTaskEntity.STATE_FAILED, lastError = o.msg, attempts = attempts,
                            bytesDone = loc.downloads.partFile(track.id).length()))
                        log.error("ダウンロード失敗(再試行上限): ${track.remotePath} (${o.msg})")
                        Notifications.notifyProblem(applicationContext, "ダウンロードに失敗しました", track.displayTitle)
                    } else {
                        dao.update(cur.copy(state = DownloadTaskEntity.STATE_QUEUED, lastError = o.msg, attempts = attempts,
                            bytesDone = loc.downloads.partFile(track.id).length()))
                        log.warn("ダウンロード再試行予定(${attempts}/$MAX_ATTEMPTS): ${track.remotePath} (${o.msg})")
                        // 指数バックオフで後から再開する
                        return@withContext Result.retry()
                    }
                }
            }
        }
        Result.success()
    }

    private suspend fun downloadOne(cfg: ServerConfig, track: TrackEntity, task: DownloadTaskEntity): Outcome {
        val musicDir = loc.library.musicDir
        val part = loc.downloads.partFile(track.id)
        val finalName = "${track.id}.flac"
        var offset = if (part.exists()) part.length() else 0L
        // 書きかけのETagが分からなければ、混ざらないよう最初から取り直す
        if (offset > 0 && task.partEtag == null) { part.delete(); offset = 0 }

        // ストレージ不足はダウンロード前に確認する
        val needed = (track.sizeBytes - offset).coerceAtLeast(0) + SPACE_MARGIN
        if (StatFs(musicDir.absolutePath).availableBytes < needed) return Outcome.NoSpace

        val response = try {
            loc.dav.open(cfg, track.remotePath, offset, if (offset > 0) task.partEtag else null)
        } catch (e: DavException.Unauthorized) {
            return Outcome.Auth(e.message ?: "認証エラー")
        } catch (e: DavException.NotFound) {
            return Outcome.Fatal(e.message ?: "404")
        } catch (e: DavException.NotWebDav) {
            return Outcome.Fatal(e.message ?: "WebDAVではありません")
        } catch (e: DavException.Http) {
            return if (e.code >= 500) Outcome.Transient(e.message ?: "") else Outcome.Fatal(e.message ?: "")
        } catch (e: IOException) {
            return Outcome.Transient(e.message ?: "通信エラー")
        }

        response.use { r ->
            if (r.code == 416) {
                // 既に全体を持っているか、サーバー側が縮んだ。最初からやり直す。
                part.delete()
                return Outcome.Transient("Range不一致のため最初から取得し直します")
            }
            val append = r.code == 206
            if (!append) offset = 0 // 200: ETagが変わった、またはRange非対応 → 一時ファイルを破棄して最初から
            val total = if (append) {
                r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
            } else r.body?.contentLength()?.takeIf { it >= 0 }
            val newEtag = if (append) task.partEtag else r.header("ETag")
            if (!append) dao.startPart(track.id, newEtag)
            val body = r.body ?: return Outcome.Transient("空の応答")

            if (total != null && StatFs(musicDir.absolutePath).availableBytes < (total - offset) + SPACE_MARGIN) {
                return Outcome.NoSpace
            }

            var done = offset
            var lastDb = done
            var lastNotify = 0L
            try {
                FileOutputStream(part, append).use { out ->
                    val input = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastDb >= PROGRESS_STEP) {
                            lastDb = done
                            dao.setProgress(track.id, done)
                            if (dao.stateOf(track.id) != DownloadTaskEntity.STATE_RUNNING) {
                                return Outcome.Paused // 中断またはキャンセルされた
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastNotify > 1000) {
                                lastNotify = now
                                trySetForeground(track.displayTitle, done, total)
                            }
                        }
                    }
                    out.fd.sync()
                }
            } catch (e: IOException) {
                dao.setProgress(track.id, part.length())
                return Outcome.Transient(e.message ?: "通信が切れました")
            }

            // 整合確認: 長さが合わないファイルは正式名にしない(壊れたファイルを残さない)
            val expected = total ?: track.sizeBytes.takeIf { it > 0 }
            if (expected != null && part.length() != expected) {
                part.delete()
                return Outcome.Transient("サイズ不一致(${part.length()}/$expected)")
            }

            val dest = File(musicDir, finalName)
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) return Outcome.Transient("ファイルを保存できませんでした")

            val coverName = "${track.id}.jpg"
            val coverFile = File(loc.library.coverDir, coverName)
            val (tags, hasCover) = TagReader.read(dest, coverFile)
            trackDao.update(
                track.copy(
                    localFile = finalName,
                    remoteEtag = newEtag,
                    latestEtag = newEtag,
                    sizeBytes = dest.length(),
                    title = tags.title,
                    artist = tags.artist,
                    album = tags.album,
                    albumArtist = tags.albumArtist,
                    trackNumber = tags.trackNumber,
                    discNumber = tags.discNumber,
                    durationMs = tags.durationMs,
                    coverFile = if (hasCover) coverName else null,
                    downloadedAt = System.currentTimeMillis(),
                    playbackError = false,
                    dismissed = false,
                )
            )
            dao.delete(track.id)
            return Outcome.Done
        }
    }

    private suspend fun trySetForeground(text: String, done: Long = 0, total: Long? = null) {
        try {
            setForeground(foregroundInfo(text, done, total))
        } catch (_: Exception) {
            // バックグラウンドからの前面化が拒否された場合もダウンロード自体は続ける
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("ダウンロード中")

    private fun foregroundInfo(text: String, done: Long = 0, total: Long? = null): ForegroundInfo {
        val b = NotificationCompat.Builder(applicationContext, Notifications.CH_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ダウンロード中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(Notifications.openAppIntent(applicationContext, "downloads"))
        if (total != null && total > 0) b.setProgress(1000, (done * 1000 / total).toInt(), false)
        else b.setProgress(0, 0, true)
        val n = b.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifications.ID_DOWNLOAD, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(Notifications.ID_DOWNLOAD, n)
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        private const val PROGRESS_STEP = 512L * 1024
        private const val SPACE_MARGIN = 50L * 1024 * 1024
    }
}
