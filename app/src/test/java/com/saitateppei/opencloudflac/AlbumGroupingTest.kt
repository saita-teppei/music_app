package com.saitateppei.opencloudflac

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.saitateppei.opencloudflac.data.db.AppDatabase
import com.saitateppei.opencloudflac.data.db.ServerEntity
import com.saitateppei.opencloudflac.data.db.TrackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** コンピレーション(曲ごとにアーティストが違い、アルバムアーティストが共通)が1枚にまとまるか */
@RunWith(RobolectricTestRunner::class)
class AlbumGroupingTest {
    @Test fun compilationIsOneAlbum() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val sid = db.serverDao().insert(ServerEntity(baseUrl = "https://h", username = "u", tokenRef = "r"))
        val t = db.trackDao()
        listOf("A", "B", "C").forEachIndexed { i, a ->
            t.insert(TrackEntity(serverId = sid, remotePath = "/$i.flac", localFile = "$i.flac",
                artist = a, albumArtist = "Various Artists", album = "Hacknet OST"))
        }
        t.insert(TrackEntity(serverId = sid, remotePath = "/x.flac", localFile = "x.flac", artist = "Solo", album = "Hacknet OST"))
        t.insert(TrackEntity(serverId = sid, remotePath = "/n.flac", localFile = "n.flac"))

        val albums = t.observeAlbums().first()
        assertEquals(listOf("Hacknet OST|Solo", "Hacknet OST|Various Artists", "|"), albums.map { "${it.album}|${it.artist}" })
        assertEquals(3, albums.first { it.artist == "Various Artists" }.trackCount)
        assertEquals(1, t.albumsOfArtist("Various Artists").size)
        assertEquals(3, t.observeArtists().first().size) // Solo, Various Artists, 不明
        db.close()
    }
}
