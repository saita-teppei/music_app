package com.saitateppei.opencloudflac.data

import com.saitateppei.opencloudflac.data.db.LogDao
import com.saitateppei.opencloudflac.data.db.LogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * アプリ内ログ(9.1)。直近 [MAX] 件を残し、古いものから削除する。
 * 認証ヘッダー・トークン・URLのクエリ文字列は残さない([sanitize])。
 */
class AppLog(private val dao: LogDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun info(message: String) = write("INFO", message)
    fun warn(message: String) = write("WARN", message)
    fun error(message: String) = write("ERROR", message)

    private fun write(level: String, message: String) {
        scope.launch {
            dao.insert(LogEntity(level = level, message = sanitize(message)))
            dao.trim(MAX)
        }
    }

    companion object {
        const val MAX = 500
        private val QUERY = Regex("""(https?://[^\s?#]+)\?[^\s#]*""")
        private val AUTH = Regex("""(?i)(authorization|token|password)\s*[:=]\s*\S+""")
        fun sanitize(s: String): String = s.replace(QUERY, "$1").replace(AUTH, "$1=***")
    }
}
