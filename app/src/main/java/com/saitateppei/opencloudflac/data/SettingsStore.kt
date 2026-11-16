package com.saitateppei.opencloudflac.data

import com.saitateppei.opencloudflac.data.db.ServerDao
import com.saitateppei.opencloudflac.data.db.ServerEntity
import com.saitateppei.opencloudflac.data.webdav.ServerConfig
import com.saitateppei.opencloudflac.data.webdav.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** サーバー設定と暗号化トークン(6.2 SettingsStore) */
class SettingsStore(
    private val serverDao: ServerDao,
    private val tokens: SecureTokenStore,
    private val dav: WebDavClient,
) {
    fun observeServer(): Flow<ServerEntity?> = serverDao.observeFirst()

    suspend fun server(): ServerEntity? = serverDao.first()

    /** 認証情報まで揃った接続設定。トークンが読めなければ null(再設定が必要)。 */
    suspend fun config(): ServerConfig? {
        val s = serverDao.first() ?: return null
        val token = tokens.get(s.tokenRef) ?: return null
        return ServerConfig(s.id, s.baseUrl, s.username, token)
    }

    suspend fun save(baseUrl: String, username: String, token: String?) {
        val url = normalizeUrl(baseUrl)
        val existing = serverDao.first()
        if (existing == null) {
            val ref = "server_token_${System.currentTimeMillis()}"
            tokens.put(ref, token.orEmpty())
            serverDao.insert(ServerEntity(baseUrl = url, username = username.trim(), tokenRef = ref))
        } else {
            if (!token.isNullOrEmpty()) tokens.put(existing.tokenRef, token)
            serverDao.update(existing.copy(baseUrl = url, username = username.trim()))
        }
    }

    /** 接続テスト: ルートに Depth: 0 の PROPFIND を送る。トークン未入力なら保存済みを使う。 */
    suspend fun testConnection(baseUrl: String, username: String, token: String?): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = normalizeUrl(baseUrl)
                val t = token?.takeIf { it.isNotEmpty() }
                    ?: serverDao.first()?.let { tokens.get(it.tokenRef) }
                    ?: error("トークンを入力してください")
                val entry = dav.stat(ServerConfig(0, url, username.trim(), t), "/")
                    ?: error("応答を解釈できませんでした")
                if (!entry.isDirectory) error("ベースURLがフォルダを指していません")
                "接続できました"
            }
        }

    companion object {
        fun normalizeUrl(raw: String): String {
            val t = raw.trim().trimEnd('/')
            val withScheme = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
            require(withScheme.toHttpUrlOrNull() != null) { "URLの形式が正しくありません" }
            return withScheme
        }
    }
}
