package com.saitateppei.opencloudflac

import com.saitateppei.opencloudflac.update.ReleaseParser
import com.saitateppei.opencloudflac.update.VersionCompare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
    @Test fun versions() {
        assertTrue(VersionCompare.isNewer("v1.2.10", "1.2.9"))
        assertTrue(VersionCompare.isNewer("1.3", "1.2.9"))
        assertFalse(VersionCompare.isNewer("v1.2.0", "1.2"))
        assertFalse(VersionCompare.isNewer("1.0.0", "1.0.1"))
    }

    @Test fun parsesRelease() {
        val sha = "a".repeat(64)
        val json = """{"tag_name":"v0.2.0","body":"変更点\nSHA-256: $sha","assets":[
            {"name":"app-release.apk","browser_download_url":"https://x/app.apk","size":100},
            {"name":"app-release.apk.sha256","browser_download_url":"https://x/app.apk.sha256","size":64}]}"""
        val r = ReleaseParser.parse(json)!!
        assertEquals("0.2.0", r.version)
        assertEquals("https://x/app.apk", r.apkUrl)
        assertEquals(sha, r.sha256)
        assertEquals("https://x/app.apk.sha256", r.sha256Url)
    }

    @Test fun noApk() {
        assertNull(ReleaseParser.parse("""{"tag_name":"v1","assets":[]}"""))
    }
}
