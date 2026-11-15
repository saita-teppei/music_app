package com.saitateppei.opencloudflac.data

import android.content.Context
import com.saitateppei.opencloudflac.data.db.AppDatabase
import com.saitateppei.opencloudflac.data.db.TrackEntity
import com.saitateppei.opencloudflac.data.webdav.DavEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 曲とローカルファイルの管理(6.2 LibraryRepository) */
class LibraryRepository(context: Context, private val db: AppDatabase, private val log: AppLog) {
    private val tracks = db.trackDao()

    val musicDir: File = File(context.filesDir, "music").apply { mkdirs() }
    val coverDir: File = File(context.filesDir, "covers").apply { mkdirs() }

    fun observeDownloaded() = tracks.observeDownloaded()
    fun search(q: String) = tracks.search(q)
    fun observeArtists() = tracks.observeArtists()
    fun observeAlbums() = tracks.observeAlbums()
    fun observeByArtist(artist: String) = tracks.observeByArtist(artist)
    fun observeByAlbum(album: String, artist: String) = tracks.observeByAlbum(album, artist)
    fun observeStorageBytes() = tracks.observeStorageBytes()
    fun observeDownloadedCount() = tracks.observeDownloadedCount()

    fun localFile(t: TrackEntity): File? = t.localFile?.let { File(musicDir, it) }
    fun coverFile(t: TrackEntity): File? = t.coverFile?.let { File(coverDir, it) }
    fun coverFile(name: String?): File? = name?.let { File(coverDir, it) }

    /** パスで曲を取得し、無ければ作る。一覧で見えたETagは latest_etag として記録する(F9)。 */
    suspend fun upsertFromRemote(serverId: Long, e: DavEntry, syncFolderId: Long? = null): TrackEntity {
        val existing = tracks.findByPath(serverId, e.path)
        if (existing == null) {
            val t = TrackEntity(
                serverId = serverId,
                remotePath = e.path,
                sizeBytes = e.size,
                latestEtag = e.etag,
                syncFolderId = syncFolderId,
            )
            return t.copy(id = tracks.insert(t))
        }
        val updated = existing.copy(
            latestEtag = e.etag ?: existing.latestEtag,
            sizeBytes = if (existing.localFile == null) e.size else existing.sizeBytes,
            syncFolderId = existing.syncFolderId ?: syncFolderId,
        )
        if (updated != existing) tracks.update(updated)
        return updated
    }

    /** 一覧取得時に既知の曲のETagだけ更新する(閲覧画面で更新を検知) */
    suspend fun observeRemoteEtags(serverId: Long, entries: List<DavEntry>) {
        val files = entries.filter { it.isFlac }
        if (files.isEmpty()) return
        val known = tracks.findByPaths(serverId, files.map { it.path }).associateBy { it.remotePath }
        for (e in files) {
            val t = known[e.path] ?: continue
            if (e.etag != null && e.etag != t.latestEtag) tracks.setLatestEtag(t.id, e.etag)
        }
    }

    suspend fun knownByPath(serverId: Long, paths: List<String>): Map<String, TrackEntity> =
        if (paths.isEmpty()) emptyMap()
        else paths.chunked(500).flatMap { tracks.findByPaths(serverId, it) }.associateBy { it.remotePath }

    /** F8: ローカル保存だけ削除する。曲の行とタグは残すので、プレイリスト項目は残る。 */
    suspend fun deleteLocal(trackIds: Collection<Long>) = withContext(Dispatchers.IO) {
        for (t in tracks.getMany(trackIds.toList())) {
            localFile(t)?.delete()
            coverFile(t)?.delete()
            tracks.update(t.copy(localFile = null, coverFile = null, downloadedAt = null, playbackError = false))
            log.info("ローカル削除: ${t.remotePath}")
        }
    }

    suspend fun markPlaybackError(trackId: Long) = tracks.setPlaybackError(trackId, true)

    suspend fun get(id: Long) = tracks.get(id)
    suspend fun getMany(ids: List<Long>) = tracks.getMany(ids)
    suspend fun downloaded() = tracks.downloaded()
    suspend fun byArtist(a: String) = tracks.byArtist(a)
    suspend fun byAlbum(album: String, artist: String) = tracks.byAlbum(album, artist)
    suspend fun albumsOfArtist(a: String) = tracks.albumsOfArtist(a)
}
