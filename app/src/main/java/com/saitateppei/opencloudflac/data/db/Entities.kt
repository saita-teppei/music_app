package com.saitateppei.opencloudflac.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 接続先(5.2 server)。トークン本体はDBに置かず、Keystoreで暗号化した値を指すキーだけを持つ。 */
@Entity(tableName = "server")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    val username: String,
    @ColumnInfo(name = "token_ref") val tokenRef: String,
)

@Entity(
    tableName = "sync_folder",
    foreignKeys = [
        ForeignKey(ServerEntity::class, ["id"], ["server_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["server_id", "remote_path"], unique = true)],
)
data class SyncFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "remote_path") val remotePath: String,
    /** [MODE_SUGGEST] または [MODE_AUTO] */
    val mode: String = MODE_SUGGEST,
    @ColumnInfo(name = "last_scan_at") val lastScanAt: Long? = null,
) {
    companion object {
        const val MODE_SUGGEST = "suggest"
        const val MODE_AUTO = "auto"
    }
}

/**
 * 曲。「サーバー + OpenCloud上のパス」で識別する。
 * local_file が NULL なら未ダウンロード(プレイリスト項目や同期の提案として残る)。
 */
@Entity(
    tableName = "track",
    foreignKeys = [
        ForeignKey(ServerEntity::class, ["id"], ["server_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(SyncFolderEntity::class, ["id"], ["sync_folder_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index(value = ["server_id", "remote_path"], unique = true),
        Index("sync_folder_id"),
        Index("title"),
        Index("artist"),
        Index("album"),
    ],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "remote_path") val remotePath: String,
    /** ダウンロード時点のETag */
    @ColumnInfo(name = "remote_etag") val remoteEtag: String? = null,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0,
    @ColumnInfo(name = "local_file") val localFile: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "downloaded_at") val downloadedAt: Long? = null,
    @ColumnInfo(name = "album_artist") val albumArtist: String? = null,
    @ColumnInfo(name = "track_number") val trackNumber: Int? = null,
    @ColumnInfo(name = "disc_number") val discNumber: Int? = null,
    @ColumnInfo(name = "cover_file") val coverFile: String? = null,
    @ColumnInfo(name = "sync_folder_id") val syncFolderId: Long? = null,
    /** 同期の提案を見送った曲。以後は提案に出さない。 */
    val dismissed: Boolean = false,
    /** サーバー上で最後に観測したETag。remote_etag と違えば更新あり(F9)。設計書への追加列。 */
    @ColumnInfo(name = "latest_etag") val latestEtag: String? = null,
    /** 再生に失敗した(ファイル破損の疑い)。再ダウンロードを提示する。設計書への追加列。 */
    @ColumnInfo(name = "playback_error") val playbackError: Boolean = false,
) {
    val fileName: String get() = remotePath.substringAfterLast('/')
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: fileName
    val displayArtist: String get() = artist?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST
    val displayAlbum: String get() = album?.takeIf { it.isNotBlank() } ?: UNKNOWN_ALBUM
    val isDownloaded: Boolean get() = localFile != null
    val hasUpdate: Boolean get() = localFile != null && latestEtag != null && remoteEtag != null && latestEtag != remoteEtag

    companion object {
        const val UNKNOWN_ARTIST = "不明なアーティスト"
        const val UNKNOWN_ALBUM = "不明なアルバム"
    }
}

@Entity(
    tableName = "download_task",
    foreignKeys = [
        ForeignKey(TrackEntity::class, ["id"], ["track_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class DownloadTaskEntity(
    @PrimaryKey @ColumnInfo(name = "track_id") val trackId: Long,
    /** queued / running / paused / failed */
    val state: String = STATE_QUEUED,
    @ColumnInfo(name = "bytes_done") val bytesDone: Long = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    /** 自動再試行の回数。上限を超えたら failed。設計書への追加列。 */
    val attempts: Int = 0,
    /** 一時ファイルを書き始めたときのETag。再開時の If-Range に使う。設計書への追加列。 */
    @ColumnInfo(name = "part_etag") val partEtag: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATE_QUEUED = "queued"
        const val STATE_RUNNING = "running"
        const val STATE_PAUSED = "paused"
        const val STATE_FAILED = "failed"
    }
}

@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playlist_item",
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlist_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TrackEntity::class, ["id"], ["track_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("playlist_id"), Index("track_id")],
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    val position: Int,
    @ColumnInfo(name = "track_id") val trackId: Long,
)

/** 同期走査で、ETagが変わったフォルダだけ降りるためのキャッシュ。設計書への追加テーブル。 */
@Entity(
    tableName = "sync_dir_cache",
    primaryKeys = ["sync_folder_id", "path"],
    foreignKeys = [
        ForeignKey(SyncFolderEntity::class, ["id"], ["sync_folder_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class SyncDirCacheEntity(
    @ColumnInfo(name = "sync_folder_id") val syncFolderId: Long,
    val path: String,
    val etag: String,
)

/** アプリ内ログ(9.1)。直近 [com.saitateppei.opencloudflac.data.AppLog.MAX] 件だけ残す。設計書への追加テーブル。 */
@Entity(tableName = "log_entry")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long = System.currentTimeMillis(),
    val level: String,
    val message: String,
)
