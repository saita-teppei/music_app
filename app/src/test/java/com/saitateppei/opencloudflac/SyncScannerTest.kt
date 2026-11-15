package com.saitateppei.opencloudflac

import com.saitateppei.opencloudflac.data.webdav.DavEntry
import com.saitateppei.opencloudflac.sync.SyncScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncScannerTest {
    private fun dir(p: String, e: String) = DavEntry(p, p.substringAfterLast('/'), true, 0, e, null)
    private fun file(p: String) = DavEntry(p, p.substringAfterLast('/'), false, 1, "f", null)

    private var tree = mapOf(
        "/M" to listOf(dir("/M", "r1"), dir("/M/A", "a1"), dir("/M/B", "b1"), file("/M/x.flac")),
        "/M/A" to listOf(dir("/M/A", "a1"), file("/M/A/1.flac"), file("/M/A/c.jpg")),
        "/M/B" to listOf(dir("/M/B", "b1"), file("/M/B/2.flac")),
    )

    @Test fun skipsUnchangedFolders() = runBlocking {
        val cache = mutableMapOf<String, String>()
        val seen = mutableListOf<String>()
        fun scanner() = SyncScanner({ tree.getValue(it) }, { cache[it] }, { p, e -> cache[p] = e }, { seen += it.path })

        val s1 = scanner()
        assertEquals(3, s1.scan("/M"))
        assertEquals(3, s1.requests)

        // 変更なし: ルートだけ確認して終わる
        seen.clear()
        val s2 = scanner()
        assertEquals(0, s2.scan("/M"))
        assertEquals(1, s2.requests)

        // Bだけ変わった: ルートとBだけ降りる
        tree = tree + mapOf(
            "/M" to listOf(dir("/M", "r2"), dir("/M/A", "a1"), dir("/M/B", "b2"), file("/M/x.flac")),
            "/M/B" to listOf(dir("/M/B", "b2"), file("/M/B/2.flac"), file("/M/B/3.flac")),
        )
        seen.clear()
        val s3 = scanner()
        s3.scan("/M")
        assertEquals(2, s3.requests)
        assertEquals(listOf("/M/x.flac", "/M/B/2.flac", "/M/B/3.flac"), seen)
    }
}
