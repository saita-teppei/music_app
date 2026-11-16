package com.saitateppei.opencloudflac.data

import com.saitateppei.opencloudflac.data.db.PlaylistDao
import com.saitateppei.opencloudflac.data.db.PlaylistEntity
import com.saitateppei.opencloudflac.data.db.PlaylistItemEntity

/** プレイリスト(6.2 PlaylistRepository)。項目は曲の行(サーバー+パス)を参照する。 */
class PlaylistRepository(private val dao: PlaylistDao) {
    fun observePlaylists() = dao.observePlaylists()
    fun observe(id: Long) = dao.observe(id)
    fun observeItems(id: Long) = dao.observeItems(id)
    suspend fun all() = dao.all()
    suspend fun tracks(id: Long) = dao.tracks(id)

    suspend fun create(name: String): Long = dao.insert(PlaylistEntity(name = name.trim().ifEmpty { "新しいプレイリスト" }))

    suspend fun rename(id: Long, name: String) {
        val p = dao.get(id) ?: return
        dao.update(p.copy(name = name.trim().ifEmpty { p.name }, updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun add(id: Long, trackIds: List<Long>) {
        val start = dao.items(id).size
        dao.insertItems(trackIds.mapIndexed { i, t -> PlaylistItemEntity(playlistId = id, position = start + i, trackId = t) })
        dao.touch(id)
    }

    suspend fun remove(id: Long, itemId: Long) {
        dao.deleteItem(itemId)
        reorder(id, dao.items(id).map { it.trackId })
    }

    /** 項目を [from] から [to] へ移動する */
    suspend fun move(id: Long, from: Int, to: Int) {
        val ids = dao.items(id).map { it.trackId }.toMutableList()
        if (from !in ids.indices || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        reorder(id, ids)
    }

    suspend fun reorder(id: Long, trackIds: List<Long>) = dao.replaceItems(id, trackIds)
}
