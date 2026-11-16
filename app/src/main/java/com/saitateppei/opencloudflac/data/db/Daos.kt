package com.saitateppei.opencloudflac.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class ArtistRow(val name: String, val trackCount: Int)

data class AlbumRow(
    val album: String,
    val artist: String,
    val trackCount: Int,
    val coverFile: String?,
)

data class DownloadRow(
    val trackId: Long,
    val state: String,
    val bytesDone: Long,
    val lastError: String?,
    val sizeBytes: Long,
    val remotePath: String,
    val title: String?,
)

data class PlaylistRow(val id: Long, val name: String, val itemCount: Int)

data class PlaylistTrackRow(
    val itemId: Long,
    val position: Int,
    @androidx.room.Embedded(prefix = "t_") val track: TrackEntity,
)

/** アルバムアーティストが無ければアーティストを使う(5.2) */
private const val ARTIST_KEY = "COALESCE(NULLIF(album_artist, ''), NULLIF(artist, ''), '')"
private const val ALBUM_KEY = "COALESCE(NULLIF(album, ''), '')"
private const val ORDER_IN_ALBUM = "COALESCE(disc_number, 0), COALESCE(track_number, 0), remote_path"

@Dao
interface ServerDao {
    @Query("SELECT * FROM server ORDER BY id LIMIT 1")
    suspend fun first(): ServerEntity?

    @Query("SELECT * FROM server ORDER BY id LIMIT 1")
    fun observeFirst(): Flow<ServerEntity?>

    @Insert
    suspend fun insert(server: ServerEntity): Long

    @Update
    suspend fun update(server: ServerEntity)
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM track WHERE id = :id")
    suspend fun get(id: Long): TrackEntity?

    @Query("SELECT * FROM track WHERE id IN (:ids)")
    suspend fun getMany(ids: List<Long>): List<TrackEntity>

    @Query("SELECT * FROM track WHERE server_id = :serverId AND remote_path = :path")
    suspend fun findByPath(serverId: Long, path: String): TrackEntity?

    @Query("SELECT * FROM track WHERE server_id = :serverId AND remote_path IN (:paths)")
    suspend fun findByPaths(serverId: Long, paths: List<String>): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(track: TrackEntity): Long

    @Update
    suspend fun update(track: TrackEntity)

    @Query("SELECT * FROM track WHERE local_file IS NOT NULL ORDER BY COALESCE(NULLIF(title,''), remote_path) COLLATE NOCASE")
    fun observeDownloaded(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM track WHERE local_file IS NOT NULL ORDER BY COALESCE(NULLIF(title,''), remote_path) COLLATE NOCASE")
    suspend fun downloaded(): List<TrackEntity>

    @Query(
        """SELECT * FROM track WHERE local_file IS NOT NULL AND (
            title LIKE '%' || :q || '%' OR artist LIKE '%' || :q || '%' OR album LIKE '%' || :q || '%'
            OR album_artist LIKE '%' || :q || '%' OR remote_path LIKE '%' || :q || '%')
           ORDER BY COALESCE(NULLIF(title,''), remote_path) COLLATE NOCASE"""
    )
    fun search(q: String): Flow<List<TrackEntity>>

    @Query(
        """SELECT $ARTIST_KEY AS name, COUNT(*) AS trackCount FROM track
           WHERE local_file IS NOT NULL GROUP BY name ORDER BY name = '', name COLLATE NOCASE"""
    )
    fun observeArtists(): Flow<List<ArtistRow>>

    @Query(
        """SELECT $ALBUM_KEY AS album, $ARTIST_KEY AS artist, COUNT(*) AS trackCount, MAX(cover_file) AS coverFile
           FROM track WHERE local_file IS NOT NULL GROUP BY album, artist
           ORDER BY album = '', album COLLATE NOCASE"""
    )
    fun observeAlbums(): Flow<List<AlbumRow>>

    @Query(
        """SELECT $ALBUM_KEY AS album, $ARTIST_KEY AS artist, COUNT(*) AS trackCount, MAX(cover_file) AS coverFile
           FROM track WHERE local_file IS NOT NULL AND $ARTIST_KEY = :artist GROUP BY album, artist
           ORDER BY album = '', album COLLATE NOCASE"""
    )
    suspend fun albumsOfArtist(artist: String): List<AlbumRow>

    @Query(
        """SELECT * FROM track WHERE local_file IS NOT NULL AND $ARTIST_KEY = :artist
           ORDER BY $ALBUM_KEY COLLATE NOCASE, $ORDER_IN_ALBUM"""
    )
    fun observeByArtist(artist: String): Flow<List<TrackEntity>>

    @Query(
        """SELECT * FROM track WHERE local_file IS NOT NULL AND $ARTIST_KEY = :artist
           ORDER BY $ALBUM_KEY COLLATE NOCASE, $ORDER_IN_ALBUM"""
    )
    suspend fun byArtist(artist: String): List<TrackEntity>

    @Query(
        """SELECT * FROM track WHERE local_file IS NOT NULL AND $ALBUM_KEY = :album AND $ARTIST_KEY = :artist
           ORDER BY $ORDER_IN_ALBUM"""
    )
    fun observeByAlbum(album: String, artist: String): Flow<List<TrackEntity>>

    @Query(
        """SELECT * FROM track WHERE local_file IS NOT NULL AND $ALBUM_KEY = :album AND $ARTIST_KEY = :artist
           ORDER BY $ORDER_IN_ALBUM"""
    )
    suspend fun byAlbum(album: String, artist: String): List<TrackEntity>

    /** 同期の提案: 未ダウンロードの新曲、または更新された曲(見送り済みとキュー中は除く) */
    @Query(
        """SELECT * FROM track WHERE dismissed = 0
             AND id NOT IN (SELECT track_id FROM download_task)
             AND ((local_file IS NULL AND sync_folder_id IS NOT NULL)
                  OR (local_file IS NOT NULL AND latest_etag IS NOT NULL AND remote_etag IS NOT NULL AND latest_etag != remote_etag))
           ORDER BY remote_path"""
    )
    fun observeProposals(): Flow<List<TrackEntity>>

    @Query(
        """SELECT COUNT(*) FROM track WHERE dismissed = 0
             AND id NOT IN (SELECT track_id FROM download_task)
             AND ((local_file IS NULL AND sync_folder_id IS NOT NULL)
                  OR (local_file IS NOT NULL AND latest_etag IS NOT NULL AND remote_etag IS NOT NULL AND latest_etag != remote_etag))"""
    )
    suspend fun proposalCount(): Int

    @Query(
        """SELECT id FROM track WHERE dismissed = 0
             AND id NOT IN (SELECT track_id FROM download_task)
             AND ((local_file IS NULL AND sync_folder_id IS NOT NULL)
                  OR (local_file IS NOT NULL AND latest_etag IS NOT NULL AND remote_etag IS NOT NULL AND latest_etag != remote_etag))"""
    )
    suspend fun proposalIds(): List<Long>

    @Query("UPDATE track SET dismissed = 1 WHERE id IN (:ids)")
    suspend fun dismiss(ids: List<Long>)

    @Query("UPDATE track SET playback_error = :error WHERE id = :id")
    suspend fun setPlaybackError(id: Long, error: Boolean)

    @Query("UPDATE track SET latest_etag = :etag WHERE id = :id")
    suspend fun setLatestEtag(id: Long, etag: String?)

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM track WHERE local_file IS NOT NULL")
    fun observeStorageBytes(): Flow<Long>

    @Query("SELECT COUNT(*) FROM track WHERE local_file IS NOT NULL")
    fun observeDownloadedCount(): Flow<Int>
}

@Dao
interface DownloadTaskDao {
    @Query("SELECT * FROM download_task WHERE track_id = :trackId")
    suspend fun get(trackId: Long): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: DownloadTaskEntity)

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Query("DELETE FROM download_task WHERE track_id = :trackId")
    suspend fun delete(trackId: Long)

    @Query("SELECT * FROM download_task WHERE state = 'queued' ORDER BY created_at, track_id LIMIT 1")
    suspend fun nextQueued(): DownloadTaskEntity?

    @Query("SELECT state FROM download_task WHERE track_id = :trackId")
    suspend fun stateOf(trackId: Long): String?

    @Query("UPDATE download_task SET state = 'queued' WHERE state = 'running'")
    suspend fun resetRunning()

    @Query("UPDATE download_task SET state = :state, last_error = :error WHERE track_id = :trackId")
    suspend fun setState(trackId: Long, state: String, error: String? = null)

    @Query("UPDATE download_task SET bytes_done = :bytes WHERE track_id = :trackId")
    suspend fun setProgress(trackId: Long, bytes: Long)

    @Query("UPDATE download_task SET part_etag = :etag, bytes_done = 0 WHERE track_id = :trackId")
    suspend fun startPart(trackId: Long, etag: String?)

    @Query("UPDATE download_task SET state = 'queued', last_error = NULL, attempts = 0 WHERE state IN ('paused', 'failed')")
    suspend fun resumeAll()

    @Query("UPDATE download_task SET state = 'paused' WHERE state IN ('queued', 'running')")
    suspend fun pauseAll()

    @Query("SELECT COUNT(*) FROM download_task WHERE state IN ('queued', 'running')")
    suspend fun activeCount(): Int

    @Query(
        """SELECT d.track_id AS trackId, d.state, d.bytes_done AS bytesDone, d.last_error AS lastError,
                  t.size_bytes AS sizeBytes, t.remote_path AS remotePath, t.title
           FROM download_task d JOIN track t ON t.id = d.track_id
           ORDER BY d.created_at, d.track_id"""
    )
    fun observeAll(): Flow<List<DownloadRow>>

    @Query("SELECT track_id FROM download_task")
    fun observeIds(): Flow<List<Long>>
}

@Dao
interface PlaylistDao {
    @Query(
        """SELECT p.id, p.name, (SELECT COUNT(*) FROM playlist_item i WHERE i.playlist_id = p.id) AS itemCount
           FROM playlist p ORDER BY p.name COLLATE NOCASE"""
    )
    fun observePlaylists(): Flow<List<PlaylistRow>>

    @Query("SELECT * FROM playlist ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlist WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Insert
    suspend fun insert(p: PlaylistEntity): Long

    @Update
    suspend fun update(p: PlaylistEntity)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """SELECT i.id AS itemId, i.position AS position,
             t.id AS t_id, t.server_id AS t_server_id, t.remote_path AS t_remote_path, t.remote_etag AS t_remote_etag,
             t.size_bytes AS t_size_bytes, t.local_file AS t_local_file, t.title AS t_title, t.artist AS t_artist,
             t.album AS t_album, t.duration_ms AS t_duration_ms, t.downloaded_at AS t_downloaded_at,
             t.album_artist AS t_album_artist, t.track_number AS t_track_number, t.disc_number AS t_disc_number,
             t.cover_file AS t_cover_file, t.sync_folder_id AS t_sync_folder_id, t.dismissed AS t_dismissed,
             t.latest_etag AS t_latest_etag, t.playback_error AS t_playback_error
           FROM playlist_item i JOIN track t ON t.id = i.track_id
           WHERE i.playlist_id = :playlistId ORDER BY i.position"""
    )
    fun observeItems(playlistId: Long): Flow<List<PlaylistTrackRow>>

    @Query("SELECT * FROM playlist_item WHERE playlist_id = :playlistId ORDER BY position")
    suspend fun items(playlistId: Long): List<PlaylistItemEntity>

    @Query(
        """SELECT t.* FROM playlist_item i JOIN track t ON t.id = i.track_id
           WHERE i.playlist_id = :playlistId ORDER BY i.position"""
    )
    suspend fun tracks(playlistId: Long): List<TrackEntity>

    @Insert
    suspend fun insertItems(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist_item WHERE id = :itemId")
    suspend fun deleteItem(itemId: Long)

    @Query("DELETE FROM playlist_item WHERE playlist_id = :playlistId")
    suspend fun clearItems(playlistId: Long)

    @Query("UPDATE playlist SET updated_at = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long = System.currentTimeMillis())

    @Transaction
    suspend fun replaceItems(playlistId: Long, trackIds: List<Long>) {
        clearItems(playlistId)
        insertItems(trackIds.mapIndexed { i, t -> PlaylistItemEntity(playlistId = playlistId, position = i, trackId = t) })
        touch(playlistId)
    }
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_folder ORDER BY remote_path")
    fun observeFolders(): Flow<List<SyncFolderEntity>>

    @Query("SELECT * FROM sync_folder ORDER BY remote_path")
    suspend fun folders(): List<SyncFolderEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(f: SyncFolderEntity): Long

    @Update
    suspend fun updateFolder(f: SyncFolderEntity)

    @Query("DELETE FROM sync_folder WHERE id = :id")
    suspend fun deleteFolder(id: Long)

    @Query("SELECT etag FROM sync_dir_cache WHERE sync_folder_id = :folderId AND path = :path")
    suspend fun cachedEtag(folderId: Long, path: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCache(e: SyncDirCacheEntity)
}

@Dao
interface LogDao {
    @Insert
    suspend fun insert(e: LogEntity)

    @Query("DELETE FROM log_entry WHERE id NOT IN (SELECT id FROM log_entry ORDER BY id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("SELECT * FROM log_entry ORDER BY id DESC")
    fun observe(): Flow<List<LogEntity>>

    @Query("DELETE FROM log_entry")
    suspend fun clear()
}
