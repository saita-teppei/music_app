package com.saitateppei.opencloudflac.data.webdav

import org.w3c.dom.Element
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

/** PROPFINDの1件。[path] はベースURLからの相対パス("/"始まり、デコード済み)。 */
data class DavEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val etag: String?,
    val lastModified: String?,
) {
    val isFlac: Boolean get() = !isDirectory && name.endsWith(".flac", ignoreCase = true)
}

/**
 * 207 Multi-Status を読む。標準の javax.xml を使い、外部ライブラリに依存しない。
 * DTD・外部エンティティは無効化する(XXE対策)。
 */
object PropfindParser {
    private const val DAV = "DAV:"

    fun parse(input: InputStream, basePath: String): List<DavEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            trySet("http://apache.org/xml/features/disallow-doctype-decl", true)
            trySet("http://xml.org/sax/features/external-general-entities", false)
            trySet("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val doc = factory.newDocumentBuilder().parse(input)
        val responses = doc.getElementsByTagNameNS(DAV, "response")
        val base = normalizeBase(basePath)
        val result = ArrayList<DavEntry>(responses.length)
        for (i in 0 until responses.length) {
            val r = responses.item(i) as Element
            val href = r.child("href")?.textContent?.trim() ?: continue
            // 200 OK の propstat だけを見る
            var prop: Element? = null
            val propstats = r.getElementsByTagNameNS(DAV, "propstat")
            for (j in 0 until propstats.length) {
                val ps = propstats.item(j) as Element
                val status = ps.child("status")?.textContent ?: ""
                if (status.contains(" 200")) {
                    prop = ps.child("prop"); break
                }
            }
            val p = prop ?: continue
            val isDir = p.child("resourcetype")?.let { rt ->
                rt.getElementsByTagNameNS(DAV, "collection").length > 0
            } ?: false
            val path = relativePath(href, base)
            val name = path.trimEnd('/').substringAfterLast('/')
            result += DavEntry(
                path = if (isDir) path.trimEnd('/').ifEmpty { "/" } else path,
                name = name,
                isDirectory = isDir,
                size = p.child("getcontentlength")?.textContent?.trim()?.toLongOrNull() ?: 0L,
                etag = p.child("getetag")?.textContent?.trim()?.takeIf { it.isNotEmpty() },
                lastModified = p.child("getlastmodified")?.textContent?.trim(),
            )
        }
        return result
    }

    /** "/remote.php/webdav/" → "/remote.php/webdav" */
    fun normalizeBase(basePath: String): String = decode(basePath).trimEnd('/')

    fun relativePath(href: String, normalizedBase: String): String {
        val rawPath = if (href.startsWith("http://") || href.startsWith("https://")) {
            URI(href).rawPath ?: "/"
        } else href
        val decoded = decode(rawPath)
        val rel = if (normalizedBase.isNotEmpty() && decoded.startsWith(normalizedBase)) {
            decoded.substring(normalizedBase.length)
        } else decoded
        return if (rel.startsWith("/")) rel else "/$rel"
    }

    private fun decode(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    private fun Element.child(local: String): Element? {
        val nodes = getElementsByTagNameNS(DAV, local)
        return if (nodes.length > 0) nodes.item(0) as Element else null
    }

    private fun DocumentBuilderFactory.trySet(feature: String, value: Boolean) {
        try { setFeature(feature, value) } catch (_: Exception) { }
    }
}
