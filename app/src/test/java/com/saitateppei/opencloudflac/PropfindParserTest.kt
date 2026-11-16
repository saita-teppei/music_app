package com.saitateppei.opencloudflac

import com.saitateppei.opencloudflac.data.webdav.PropfindParser
import com.saitateppei.opencloudflac.data.webdav.WebDavClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PropfindParserTest {
    private val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
  <d:response>
    <d:href>/remote.php/webdav/Music/</d:href>
    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><d:getetag>"dir1"</d:getetag></d:prop>
      <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  </d:response>
  <d:response>
    <d:href>/remote.php/webdav/Music/%E6%9B%B2%20A.flac</d:href>
    <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>12345</d:getcontentlength><d:getetag>"abc"</d:getetag></d:prop>
      <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
    <d:propstat><d:prop><d:displayname/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
  </d:response>
  <d:response>
    <d:href>https://cloud.example.ts.net/remote.php/webdav/Music/Sub%20Dir/</d:href>
    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  </d:response>
  <d:response>
    <d:href>/remote.php/webdav/Music/cover.jpg</d:href>
    <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>10</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  </d:response>
</d:multistatus>"""

    @Test fun parsesEntries() {
        val list = PropfindParser.parse(xml.byteInputStream(), "/remote.php/webdav/")
        assertEquals(4, list.size)
        assertEquals("/Music", list[0].path)
        assertTrue(list[0].isDirectory)
        assertEquals("\"dir1\"", list[0].etag)

        val f = list[1]
        assertEquals("/Music/曲 A.flac", f.path)
        assertEquals("曲 A.flac", f.name)
        assertEquals(12345L, f.size)
        assertEquals("\"abc\"", f.etag)
        assertTrue(f.isFlac)

        assertEquals("/Music/Sub Dir", list[2].path)
        assertTrue(list[2].isDirectory)
        assertNull(list[2].etag)

        assertFalse(list[3].isFlac)
    }

    @Test fun plusSignIsKept() {
        assertEquals("/a+b.flac", PropfindParser.relativePath("/dav/a+b.flac", "/dav"))
    }

    @Test fun spacesBase() {
        val rel = PropfindParser.relativePath("/dav/spaces/abc%24def/Music/x.flac", PropfindParser.normalizeBase("/dav/spaces/abc\$def"))
        assertEquals("/Music/x.flac", rel)
    }

    @Test fun urlEncoding() {
        val u = WebDavClient.urlFor("https://h.ts.net/remote.php/webdav/", "/Music/曲 A#1.flac")
        assertEquals("https://h.ts.net/remote.php/webdav/Music/%E6%9B%B2%20A%231.flac", u.toString())
    }
}
