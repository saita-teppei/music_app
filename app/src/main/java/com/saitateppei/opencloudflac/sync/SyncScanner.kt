package com.saitateppei.opencloudflac.sync

import com.saitateppei.opencloudflac.data.webdav.DavEntry

/**
 * 同期フォルダの走査ロジック。通信とDBは関数で受け取り、単体テストできるようにする。
 * OpenCloudはフォルダのETagが配下の変更で変わるため、ETagが変わったフォルダだけ降りる(6.1)。
 */
class SyncScanner(
    private val list: (path: String) -> List<DavEntry>,
    private val cachedEtag: suspend (path: String) -> String?,
    private val putEtag: suspend (path: String, etag: String) -> Unit,
    private val onFile: suspend (DavEntry) -> Unit,
) {
    var requests = 0
        private set

    /** @return 走査したFLACの数 */
    suspend fun scan(root: String): Int {
        var files = 0
        val stack = ArrayDeque<String>().apply { add(root) }
        val pendingEtags = mutableListOf<Pair<String, String>>()
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            requests++
            val entries = list(dir)
            val self = entries.firstOrNull { it.isDirectory && samePath(it.path, dir) }
            val children = entries.filter { it !== self }
            if (self?.etag != null && cachedEtag(dir) == self.etag) continue // 変更なし
            for (c in children) {
                if (c.isDirectory) {
                    if (c.etag == null || cachedEtag(c.path) != c.etag) stack.add(c.path)
                } else if (c.isFlac) {
                    files++
                    onFile(c)
                }
            }
            if (self?.etag != null) pendingEtags += dir to self.etag
        }
        // 全体の走査が終わってからETagを記録する(途中で失敗したら次回やり直せるように)
        for ((p, e) in pendingEtags) putEtag(p, e)
        return files
    }

    private fun norm(p: String) = p.trimEnd('/').ifEmpty { "/" }
    private fun samePath(a: String, b: String) = norm(a) == norm(b)
}
