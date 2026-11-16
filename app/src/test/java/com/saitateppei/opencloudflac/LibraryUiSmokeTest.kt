package com.saitateppei.opencloudflac

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.media3.session.MediaLibraryService
import androidx.test.core.app.ApplicationProvider
import com.saitateppei.opencloudflac.data.db.PlaylistEntity
import com.saitateppei.opencloudflac.data.db.ServerEntity
import com.saitateppei.opencloudflac.data.db.TrackEntity
import com.saitateppei.opencloudflac.playback.PlaybackService
import com.saitateppei.opencloudflac.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/** サーバーと曲がある状態で、ライブラリの各タブと詳細画面を開いてもクラッシュしないか */
@RunWith(RobolectricTestRunner::class)
class LibraryUiSmokeTest {
    val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() = seed()
        override fun after() { service?.onDestroy() }
    }).around(rule)

    private var service: PlaybackService? = null

    private fun seed(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<App>()
        val component = ComponentName(app, PlaybackService::class.java)
        val intent = Intent(MediaLibraryService.SERVICE_INTERFACE).setComponent(component)
        val svc = Robolectric.buildService(PlaybackService::class.java, intent).create().get()
        service = svc
        shadowOf(app).setComponentNameAndServiceForBindService(component, svc.onBind(intent))

        val db = app.locator.db
        val sid = db.serverDao().insert(ServerEntity(baseUrl = "https://h.ts.net/dav", username = "u", tokenRef = "x"))
        val music = File(app.filesDir, "music").apply { mkdirs() }
        val ids = (1..3).map { i ->
            File(music, "$i.flac").writeBytes(ByteArray(10))
            db.trackDao().insert(TrackEntity(serverId = sid, remotePath = "/M/$i.flac", localFile = "$i.flac",
                title = if (i == 3) null else "曲$i", artist = if (i == 2) null else "歌手", album = "盤", trackNumber = i))
        }
        db.trackDao().insert(TrackEntity(serverId = sid, remotePath = "/M/未.flac", syncFolderId = null))
        val pid = db.playlistDao().insert(PlaylistEntity(name = "PL"))
        db.playlistDao().replaceItems(pid, ids)
    }

    @Test fun tabsAndDetails() {
        rule.waitForIdle()
        clickText("曲1")   // 再生
        rule.waitForIdle()
        clickText("アーティスト")
        rule.waitForIdle()
        clickText("歌手")
        rule.waitForIdle()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        clickText("アルバム")
        rule.waitForIdle()
        clickText("盤")
        rule.waitForIdle()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        clickText("プレイリスト")
        rule.waitForIdle()
        clickText("PL")
        rule.waitForIdle()
        clickDesc("メニュー")
        rule.waitForIdle()
        clickText("下へ移動")
        rule.waitForIdle()
        back()
        for (screen in listOf("ダウンロード一覧", "同期と新しい曲", "設定")) {
            clickDesc("メニュー")
            rule.waitForIdle()
            rule.onAllNodesWithText(screen).onFirst().performClick()
            rule.waitForIdle()
            back()
        }
        clickDesc("サーバーを閲覧") // 通信失敗が画面に出るだけ
        rule.waitForIdle()
        back()
        clickText("曲2")
        rule.waitForIdle()
        clickDesc("次の曲")
        rule.waitForIdle()
    }

    private fun clickText(t: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(t).onFirst().performClick(); rule.waitForIdle()
    }

    private fun clickDesc(t: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription(t).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithContentDescription(t).onFirst().performClick(); rule.waitForIdle()
    }

    private fun back() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }
}
