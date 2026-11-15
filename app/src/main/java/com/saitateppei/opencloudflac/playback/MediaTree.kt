package com.saitateppei.opencloudflac.playback

import android.net.Uri
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.saitateppei.opencloudflac.data.LibraryRepository
import com.saitateppei.opencloudflac.data.PlaylistRepository
import com.saitateppei.opencloudflac.data.db.TrackEntity

/**
 * メディアIDの体系と閲覧ツリー(Android Auto, F13)。
 *
 *  root ─┬ artists ── artist:<名前> ── <親>|t:<曲ID>
 *        ├ albums ─── album:<キー> ─── <親>|t:<曲ID>
 *        ├ playlists ─ playlist:<ID> ─ <親>|t:<曲ID>
 *        └ songs ───────────────────── songs|t:<曲ID>
 *
 * 再生する曲のIDに親(文脈)を含めるので、Autoで1曲選ぶとそのアルバムやプレイリスト全体がキューに入る。
 */
class MediaTree(private val library: LibraryRepository, private val playlists: PlaylistRepository) {

    fun root(): MediaItem = browsable(ROOT, "ライブラリ", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            browsable(ARTISTS, "アーティスト", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
            browsable(ALBUMS, "アルバム", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
            browsable(PLAYLISTS, "プレイリスト", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            browsable(SONGS, "すべての曲", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        )
        parentId == ARTISTS -> library.downloaded()
            .groupBy { artistKey(it) }.keys.sortedWith(unknownLast())
            .map { browsable(ARTIST_PREFIX + enc(it), it.ifEmpty { TrackEntity.UNKNOWN_ARTIST }, MediaMetadata.MEDIA_TYPE_ARTIST) }
        parentId == ALBUMS -> library.downloaded()
            .groupBy { albumKey(it) }.entries.sortedWith(compareBy({ it.key.second.isEmpty() }, { it.key.second.lowercase() }))
            .map { (key, tracks) ->
                browsable(
                    ALBUM_PREFIX + enc(key.first + SEP + key.second),
                    key.second.ifEmpty { TrackEntity.UNKNOWN_ALBUM },
                    MediaMetadata.MEDIA_TYPE_ALBUM,
                    subtitle = key.first.ifEmpty { TrackEntity.UNKNOWN_ARTIST },
                    artwork = tracks.firstNotNullOfOrNull { library.coverFile(it) }?.let { Uri.fromFile(it) },
                )
            }
        parentId == PLAYLISTS -> playlists.all().map {
            browsable(PLAYLIST_PREFIX + it.id, it.name, MediaMetadata.MEDIA_TYPE_PLAYLIST)
        }
        else -> tracksOf(parentId).map { playable(parentId, it) }
    }

    /** 親IDが指す曲の並び(ダウンロード済みのみ) */
    suspend fun tracksOf(parentId: String): List<TrackEntity> = when {
        parentId == SONGS -> library.downloaded()
        parentId.startsWith(ARTIST_PREFIX) -> library.byArtist(dec(parentId.removePrefix(ARTIST_PREFIX)))
        parentId.startsWith(ALBUM_PREFIX) -> {
            val (artist, album) = dec(parentId.removePrefix(ALBUM_PREFIX)).split(SEP, limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            library.byAlbum(album, artist)
        }
        parentId.startsWith(PLAYLIST_PREFIX) -> parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
            ?.let { playlists.tracks(it) }.orEmpty().filter { it.isDownloaded }
        else -> emptyList()
    }

    suspend fun item(mediaId: String): MediaItem? {
        parseTrackId(mediaId)?.let { (parent, id) ->
            val t = library.get(id)?.takeIf { it.isDownloaded } ?: return null
            return playable(parent, t)
        }
        return when (mediaId) {
            ROOT -> root()
            else -> null
        }
    }

    /** 1曲の指定を、その文脈(アルバム等)全体と開始位置に展開する */
    suspend fun expand(mediaId: String): Pair<List<MediaItem>, Int>? {
        val (parent, id) = parseTrackId(mediaId) ?: return null
        val list = tracksOf(parent)
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return item(mediaId)?.let { listOf(it) to 0 }
        return list.map { playable(parent, it) } to idx
    }

    /** 音声検索: 曲名・アーティスト・アルバムの部分一致。空ならすべての曲。 */
    suspend fun search(query: String): List<MediaItem> {
        val q = query.trim().lowercase()
        val all = library.downloaded()
        if (q.isEmpty()) return all.map { playable(SONGS, it) }
        return all.filter { t ->
            listOfNotNull(t.title, t.artist, t.album, t.albumArtist, t.fileName).any { it.lowercase().contains(q) }
        }.map { playable(SONGS, it) }
    }

    fun playable(parent: String, t: TrackEntity): MediaItem {
        val file = library.localFile(t)
        val md = MediaMetadata.Builder()
            .setTitle(t.displayTitle)
            .setArtist(t.displayArtist)
            .setAlbumTitle(t.displayAlbum)
            .setAlbumArtist(t.albumArtist)
            .setTrackNumber(t.trackNumber)
            .setDiscNumber(t.discNumber)
            .setDurationMs(t.durationMs)
            .setArtworkUri(library.coverFile(t)?.let { Uri.fromFile(it) })
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder()
            .setMediaId(trackMediaId(parent, t.id))
            .setUri(file?.let { Uri.fromFile(it) })
            .setMediaMetadata(md)
            .build()
    }

    private fun browsable(id: String, title: String, type: Int, subtitle: String? = null, artwork: Uri? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artwork)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(type)
                    .build()
            )
            .build()

    companion object {
        const val ROOT = "root"
        const val ARTISTS = "artists"
        const val ALBUMS = "albums"
        const val PLAYLISTS = "playlists"
        const val SONGS = "songs"
        const val ARTIST_PREFIX = "artist:"
        const val ALBUM_PREFIX = "album:"
        const val PLAYLIST_PREFIX = "playlist:"
        private const val TRACK_SEP = "|t:"
        private const val SEP = "\u0000"

        fun trackMediaId(parent: String, trackId: Long) = "$parent$TRACK_SEP$trackId"

        fun parseTrackId(mediaId: String): Pair<String, Long>? {
            val i = mediaId.lastIndexOf(TRACK_SEP)
            if (i < 0) return null
            val id = mediaId.substring(i + TRACK_SEP.length).toLongOrNull() ?: return null
            return mediaId.substring(0, i) to id
        }

        fun artistKey(t: TrackEntity): String =
            t.albumArtist?.takeIf { it.isNotBlank() } ?: t.artist?.takeIf { it.isNotBlank() } ?: ""

        fun albumKey(t: TrackEntity): Pair<String, String> = artistKey(t) to (t.album?.takeIf { it.isNotBlank() } ?: "")

        fun artistId(name: String) = ARTIST_PREFIX + enc(name)
        fun albumId(artist: String, album: String) = ALBUM_PREFIX + enc(artist + SEP + album)
        fun playlistId(id: Long) = PLAYLIST_PREFIX + id

        private fun enc(s: String) = Base64.encodeToString(s.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        private fun dec(s: String) = String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))

        private fun unknownLast() = compareBy<String>({ it.isEmpty() }, { it.lowercase() })
    }
}
