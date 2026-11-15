package com.saitateppei.opencloudflac.backup

import com.saitateppei.opencloudflac.data.SettingsStore
import com.saitateppei.opencloudflac.data.db.AppDatabase
import com.saitateppei.opencloudflac.data.db.PlaylistEntity
import com.saitateppei.opencloudflac.data.db.TrackEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * プレイリストのエクスポート/インポート(9.3)。曲はOpenCloud上のパスで表すので、
 * 端末を移しても再ダウンロードすれば復元できる。トークンは含めない。
 */
class PlaylistBackup(private val db: AppDatabase, private val settings: SettingsStore) {

    suspend fun exportJson(): String {
        val pls = JSONArray()
        for (p in db.playlistDao().all()) {
            val items = JSONArray()
            for (t in db.playlistDao().tracks(p.id)) {
                items.put(JSONObject().put("path", t.remotePath).putOpt("title", t.title).putOpt("artist", t.artist).putOpt("album", t.album))
            }
            pls.put(JSONObject().put("name", p.name).put("items", items))
        }
        return JSONObject().put("format", FORMAT).put("version", 1).put("playlists", pls).toString(2)
    }

    /** @return 取り込んだプレイリスト数 */
    suspend fun importJson(json: String): Int {
        val server = settings.server() ?: error("先にサーバーを設定してください")
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "このアプリのエクスポートファイルではありません" }
        val pls = root.getJSONArray("playlists")
        val trackDao = db.trackDao()
        var count = 0
        for (i in 0 until pls.length()) {
            val p = pls.getJSONObject(i)
            val items = p.getJSONArray("items")
            val ids = ArrayList<Long>(items.length())
            for (j in 0 until items.length()) {
                val it = items.getJSONObject(j)
                val path = it.getString("path")
                val t = trackDao.findByPath(server.id, path) ?: TrackEntity(
                    serverId = server.id, remotePath = path,
                    title = it.optString("title").ifEmpty { null },
                    artist = it.optString("artist").ifEmpty { null },
                    album = it.optString("album").ifEmpty { null },
                ).let { n -> n.copy(id = trackDao.insert(n)) }
                ids += t.id
            }
            val id = db.playlistDao().insert(PlaylistEntity(name = p.getString("name")))
            db.playlistDao().replaceItems(id, ids)
            count++
        }
        return count
    }

    companion object {
        const val FORMAT = "opencloud-flac-playlists"
    }
}
