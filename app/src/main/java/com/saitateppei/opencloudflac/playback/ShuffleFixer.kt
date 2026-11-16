package com.saitateppei.opencloudflac.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder

/**
 * シャッフルの順番を「今の曲 → 残りを無作為」に作り直す。
 *
 * ExoPlayer 既定のシャッフル順は完全に無作為なので、再生開始の曲が順番の途中に来ると、
 * リピートなしでは残りの数曲で再生が終わってしまう(全曲が流れない)。
 * キューの内容かシャッフル設定が変わったときだけ作り直す(自分の変更で無限に呼ばれないように)。
 */
@OptIn(UnstableApi::class)
class ShuffleFixer {
    private var appliedFor: List<String>? = null

    /** 新しいキューが設定されるとき(同じ曲の並びでも)に呼ぶ */
    fun reset() { appliedFor = null }

    fun apply(player: ExoPlayer) {
        if (!player.shuffleModeEnabled) { appliedFor = null; return }
        val n = player.mediaItemCount
        if (n == 0) { appliedFor = null; return }
        val ids = (0 until n).map { player.getMediaItemAt(it).mediaId }
        if (ids == appliedFor) return
        appliedFor = ids
        val current = player.currentMediaItemIndex.coerceIn(0, n - 1)
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order(n, current), System.nanoTime()))
    }

    companion object {
        /** [current] を先頭に、残りを無作為に並べた順番 */
        fun order(n: Int, current: Int, random: kotlin.random.Random = kotlin.random.Random.Default): IntArray =
            (intArrayOf(current) + (0 until n).filter { it != current }.shuffled(random).toIntArray())
    }
}
