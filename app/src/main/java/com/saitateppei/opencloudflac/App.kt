package com.saitateppei.opencloudflac

import android.app.Application
import android.content.Context
import com.saitateppei.opencloudflac.backup.PlaylistBackup
import com.saitateppei.opencloudflac.data.AppLog
import com.saitateppei.opencloudflac.data.LibraryRepository
import com.saitateppei.opencloudflac.data.PlaylistRepository
import com.saitateppei.opencloudflac.data.SecureTokenStore
import com.saitateppei.opencloudflac.data.SettingsStore
import com.saitateppei.opencloudflac.data.db.AppDatabase
import com.saitateppei.opencloudflac.data.webdav.WebDavClient
import com.saitateppei.opencloudflac.download.DownloadController
import com.saitateppei.opencloudflac.sync.SyncController
import com.saitateppei.opencloudflac.update.UpdateManager

/** 依存の組み立て。DIライブラリは使わず手で配線する(保守性, 2.2)。 */
class ServiceLocator(context: Context) {
    val db = AppDatabase.create(context)
    val http = WebDavClient.httpClient()
    val dav = WebDavClient(http)
    val log = AppLog(db.logDao())
    val tokens = SecureTokenStore(context)
    val settings = SettingsStore(db.serverDao(), tokens, dav)
    val library = LibraryRepository(context, db, log)
    val playlists = PlaylistRepository(db.playlistDao())
    val downloads = DownloadController(context, db.downloadDao(), library, log)
    val sync = SyncController(context, db, log)
    val updates = UpdateManager(context, http, log)
    val backup = PlaylistBackup(db, settings)
}

class App : Application() {
    lateinit var locator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        locator = ServiceLocator(this)
        Notifications.createChannels(this)
        locator.sync.schedulePeriodic()
        // 前回途中だったダウンロードがあれば再開する
        locator.downloads.kick()
    }
}

val Context.locator: ServiceLocator get() = (applicationContext as App).locator
