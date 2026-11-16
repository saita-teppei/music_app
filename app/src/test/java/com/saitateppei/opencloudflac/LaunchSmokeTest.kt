package com.saitateppei.opencloudflac

import android.content.ComponentName
import android.content.Intent
import androidx.media3.session.MediaLibraryService
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.saitateppei.opencloudflac.playback.PlaybackService
import com.saitateppei.opencloudflac.ui.MainActivity
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/** 起動直後と再生サービスの起動でクラッシュしないかを確認する */
@RunWith(RobolectricTestRunner::class)
class LaunchSmokeTest {
    private var service: PlaybackService? = null

    @org.junit.After fun tearDown() { service?.onDestroy() }

    @Before fun bindPlaybackService() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val component = ComponentName(app, PlaybackService::class.java)
        val intent = Intent(MediaLibraryService.SERVICE_INTERFACE).setComponent(component)
        val service = Robolectric.buildService(PlaybackService::class.java, intent).create().get()
        this.service = service
        shadowOf(app).setComponentNameAndServiceForBindService(component, service.onBind(intent))
    }

    @Test fun launchesWithoutServer() {
        ActivityScenario.launch(MainActivity::class.java).use {
            repeat(20) { ShadowLooper.idleMainLooper(); Thread.sleep(50) }
        }
    }
}
