package com.saitateppei.opencloudflac

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.saitateppei.opencloudflac.playback.ShuffleFixer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ShuffleFixerTest {
    /** 開始した曲から、リピートなしで全曲が1回ずつ流れること */
    private fun playOrder(p: Player): List<Int> {
        val out = mutableListOf(p.currentMediaItemIndex)
        var i = p.currentMediaItemIndex
        while (true) {
            i = p.currentTimeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            if (i == C.INDEX_UNSET) break
            out += i
        }
        return out
    }

    @Test fun shuffleStartsAtCurrentAndCoversAll() {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        val fixer = ShuffleFixer()
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = fixer.apply(player)
        })
        player.shuffleModeEnabled = true
        player.setMediaItems((0 until 20).map { MediaItem.Builder().setMediaId("m$it").setUri("file:///x$it.flac").build() }, 7, 0)
        shadowOf(Looper.getMainLooper()).idle()

        val order = playOrder(player)
        assertEquals(7, order.first())
        assertEquals((0 until 20).toSet(), order.toSet())
        assertEquals(20, order.size)

        // 同じ並びを別の曲から設定し直しても、その曲から始まる
        fixer.reset()
        player.setMediaItems((0 until 20).map { MediaItem.Builder().setMediaId("m$it").setUri("file:///x$it.flac").build() }, 3, 0)
        shadowOf(Looper.getMainLooper()).idle()
        val order2 = playOrder(player)
        assertEquals(3, order2.first())
        assertEquals(20, order2.toSet().size)
        player.release()
    }
}
