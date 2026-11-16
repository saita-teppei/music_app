package com.saitateppei.opencloudflac.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream

data class Tags(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long?,
)

/** 標準の MediaMetadataRetriever でタグとジャケットを読む(外部ライブラリ不要, 4.1) */
object TagReader {
    fun read(file: File, coverOut: File): Pair<Tags, Boolean> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val tags = Tags(
                title = r.extract(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = r.extract(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = r.extract(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = r.extract(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                trackNumber = parseNumber(r.extract(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                discNumber = parseNumber(r.extract(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                durationMs = r.extract(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            )
            val cover = r.embeddedPicture?.let { saveCover(it, coverOut) } ?: false
            return tags to cover
        } catch (e: Exception) {
            return Tags(null, null, null, null, null, null, null) to false
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    /** "3/12" → 3 */
    fun parseNumber(s: String?): Int? = s?.trim()?.substringBefore('/')?.trim()?.toIntOrNull()

    private fun MediaMetadataRetriever.extract(key: Int): String? =
        extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }

    /** 大きなジャケットは縮小してJPEGで保存する(Android Autoや通知への受け渡しを軽くする) */
    private fun saveCover(bytes: ByteArray, out: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_COVER && bounds.outHeight / (sample * 2) >= MAX_COVER) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        val scaled = if (bmp.width > MAX_COVER || bmp.height > MAX_COVER) {
            val f = MAX_COVER.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
        } else bmp
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return true
    }

    private const val MAX_COVER = 600
}
