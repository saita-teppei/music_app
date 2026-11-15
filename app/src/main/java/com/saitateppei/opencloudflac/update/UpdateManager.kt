package com.saitateppei.opencloudflac.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import com.saitateppei.opencloudflac.BuildConfig
import com.saitateppei.opencloudflac.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class ReleaseInfo(
    val tag: String,
    val version: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    val sha256: String?,
    val sha256Url: String?,
    val notes: String,
)

sealed interface UpdateCheck {
    data class UpToDate(val current: String) : UpdateCheck
    data class Available(val release: ReleaseInfo) : UpdateCheck
    data class Failed(val message: String) : UpdateCheck
}

/**
 * OTA更新(F10)。GitHub Releases の最新版を確認し、APKを取得してSHA-256を照合してから
 * OSのインストーラー(PackageInstaller)を起動する。毎回OSの確認画面でユーザーが承認する。
 * 他の機能とは独立させ、失敗しても再生やダウンロードには波及させない。
 */
class UpdateManager(private val context: Context, http: OkHttpClient, private val log: AppLog) {
    private val client = http.newBuilder().build()
    private val dir = File(context.filesDir, "updates")

    suspend fun checkForUpdate(): UpdateCheck = withContext(Dispatchers.IO) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return@withContext UpdateCheck.Failed("更新元のリポジトリが設定されていません")
        try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "OpenCloudFlacPlayer")
                .build()
            client.newCall(req).execute().use { r ->
                if (r.code == 404) return@withContext UpdateCheck.Failed("リリースが見つかりません")
                if (!r.isSuccessful) return@withContext UpdateCheck.Failed("更新確認に失敗しました(${r.code})")
                val info = ReleaseParser.parse(r.body!!.string())
                    ?: return@withContext UpdateCheck.Failed("リリースにAPKが添付されていません")
                if (VersionCompare.isNewer(info.version, BuildConfig.VERSION_NAME)) UpdateCheck.Available(info)
                else UpdateCheck.UpToDate(BuildConfig.VERSION_NAME)
            }
        } catch (e: Exception) {
            UpdateCheck.Failed("更新確認に失敗しました: ${e.message}")
        }
    }

    /** APKを取得し、SHA-256が一致したときだけファイルを返す */
    suspend fun downloadUpdate(info: ReleaseInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val expected = (info.sha256 ?: info.sha256Url?.let { fetchText(it) }?.let { ReleaseParser.findSha256(it) })
            ?.lowercase() ?: error("リリースにSHA-256が記載されていないため更新を中止しました")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(info.apkUrl).header("User-Agent", "OpenCloudFlacPlayer").build()).execute().use { r ->
            if (!r.isSuccessful) error("APKの取得に失敗しました(${r.code})")
            val body = r.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize
            var done = 0L
            FileOutputStream(out).use { o ->
                val input = body.byteStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    o.write(buf, 0, n); digest.update(buf, 0, n); done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            out.delete()
            log.error("更新APKのSHA-256不一致")
            error("SHA-256が一致しません。更新を中止しました")
        }
        log.info("更新APK取得: ${info.tag}")
        out
    }

    /** 「不明なアプリのインストール」がこのアプリに許可されているか */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** PackageInstaller のセッションを作ってコミットする。確認画面は [InstallResultReceiver] が開く。 */
    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { s ->
            s.openWrite("update.apk", 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o) }; s.fsync(o) }
            val pi = PendingIntent.getBroadcast(
                context, sessionId,
                Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            s.commit(pi.intentSender)
        }
    }

    private fun fetchText(url: String): String? = try {
        client.newCall(Request.Builder().url(url).header("User-Agent", "OpenCloudFlacPlayer").build()).execute()
            .use { if (it.isSuccessful) it.body?.string() else null }
    } catch (_: Exception) { null }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "不明なエラー"
                (context.applicationContext as? com.saitateppei.opencloudflac.App)?.locator?.log?.error("更新のインストール失敗: $msg")
            }
        }
    }
}

object VersionCompare {
    /** "v1.2.10" と "1.2.9" のような版を数値で比べる */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    fun compare(a: String, b: String): Int {
        val pa = parts(a); val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        .split('.').map { it.toIntOrNull() ?: 0 }
}

object ReleaseParser {
    private val SHA = Regex("""(?i)\b([a-f0-9]{64})\b""")

    fun parse(json: String): ReleaseInfo? {
        val o = JSONObject(json)
        val tag = o.optString("tag_name")
        val assets = o.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        var shaUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            when {
                name.endsWith(".apk.sha256") || name.endsWith(".sha256") -> shaUrl = a.optString("browser_download_url")
                name.endsWith(".apk") && apk == null -> apk = a
            }
        }
        val a = apk ?: return null
        val body = o.optString("body")
        return ReleaseInfo(
            tag = tag,
            version = tag.removePrefix("v"),
            apkUrl = a.optString("browser_download_url"),
            apkName = a.optString("name"),
            apkSize = a.optLong("size"),
            sha256 = findSha256(body),
            sha256Url = shaUrl,
            notes = body,
        )
    }

    fun findSha256(text: String): String? = SHA.find(text)?.groupValues?.get(1)
}
