package com.saitateppei.opencloudflac.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.saitateppei.opencloudflac.data.db.SyncFolderEntity
import com.saitateppei.opencloudflac.data.db.TrackEntity
import com.saitateppei.opencloudflac.data.webdav.DavEntry
import com.saitateppei.opencloudflac.data.webdav.DavException
import com.saitateppei.opencloudflac.locator
import com.saitateppei.opencloudflac.playback.PlayerConnection
import com.saitateppei.opencloudflac.update.ReleaseInfo
import com.saitateppei.opencloudflac.update.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun <T> kotlinx.coroutines.flow.Flow<T>.state(vm: AndroidViewModel, initial: T): StateFlow<T> =
    stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

/** アプリ全体で共有: 再生操作とトースト的なメッセージ */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val loc = app.locator
    val player = PlayerConnection(app)
    val server = loc.settings.observeServer().state(this, null)
    /** 起動時に読んだ「サーバー設定があるか」。null は読み込み中。最初の画面の決定に使う。 */
    val hasServerAtStart = MutableStateFlow<Boolean?>(null)
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        viewModelScope.launch {
            hasServerAtStart.value = loc.settings.server() != null
        }
        loc.sync.scanOnLaunch()
    }

    fun toast(msg: String) { _message.value = msg }
    fun consumeMessage() { _message.value = null }

    fun play(parent: String, tracks: List<TrackEntity>, index: Int, shuffle: Boolean = false) =
        player.play(parent, tracks, index, shuffle)

    fun deleteLocal(ids: List<Long>) = viewModelScope.launch { loc.library.deleteLocal(ids); toast("ローカル保存を削除しました") }

    fun redownload(ids: List<Long>) = viewModelScope.launch {
        val n = loc.downloads.enqueueTracks(ids)
        toast(if (n > 0) "${n}曲をダウンロードキューに入れました" else "すでにキューにあります")
    }

    val playlists = loc.playlists.observePlaylists().state(this, emptyList())

    fun addToPlaylist(playlistId: Long, trackIds: List<Long>) = viewModelScope.launch {
        loc.playlists.add(playlistId, trackIds); toast("プレイリストに追加しました")
    }

    fun createPlaylistAndAdd(name: String, trackIds: List<Long>) = viewModelScope.launch {
        val id = loc.playlists.create(name)
        if (trackIds.isNotEmpty()) loc.playlists.add(id, trackIds)
        toast("プレイリスト「$name」を作成しました")
    }

    override fun onCleared() { player.release() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val lib = app.locator.library
    val query = MutableStateFlow("")
    val tracks = lib.observeDownloaded().state(this, emptyList())
    val artists = lib.observeArtists().state(this, emptyList())
    val albums = lib.observeAlbums().state(this, emptyList())
    val results = query.flatMapLatest { q -> lib.search(q.trim()) }.state(this, emptyList())
    val queued = app.locator.downloads.observeIds().map { it.toSet() }.state(this, emptySet())
}

class ArtistViewModel(app: Application) : AndroidViewModel(app) {
    private val lib = app.locator.library
    fun tracks(artist: String) = lib.observeByArtist(artist)
    fun albumTracks(album: String, artist: String) = lib.observeByAlbum(album, artist)
}

class PlaylistViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.locator.playlists
    fun playlist(id: Long) = repo.observe(id)
    fun items(id: Long) = repo.observeItems(id)
    fun rename(id: Long, name: String) = viewModelScope.launch { repo.rename(id, name) }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
    fun remove(id: Long, itemId: Long) = viewModelScope.launch { repo.remove(id, itemId) }
    fun move(id: Long, from: Int, to: Int) = viewModelScope.launch { repo.move(id, from, to) }
    fun create(name: String) = viewModelScope.launch { repo.create(name) }
}

data class BrowserState(
    val path: String = "/",
    val loading: Boolean = false,
    val entries: List<DavEntry> = emptyList(),
    val known: Map<String, TrackEntity> = emptyMap(),
    val error: String? = null,
    val selected: Set<String> = emptySet(),
    val busy: Boolean = false,
)

class BrowserViewModel(app: Application) : AndroidViewModel(app) {
    private val loc = app.locator
    private val _state = MutableStateFlow(BrowserState())
    val state = _state.asStateFlow()

    fun open(path: String) {
        _state.value = BrowserState(path = path, loading = true)
        viewModelScope.launch {
            try {
                val cfg = loc.settings.config() ?: error("サーバーを設定してください")
                val list = withContext(Dispatchers.IO) { loc.dav.list(cfg, path, 1) }
                    .filter { it.path.trimEnd('/') != path.trimEnd('/') && it.path != path }
                    .filter { it.isDirectory || it.isFlac }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                loc.library.observeRemoteEtags(cfg.serverId, list)
                val known = loc.library.knownByPath(cfg.serverId, list.filter { it.isFlac }.map { it.path })
                _state.value = _state.value.copy(loading = false, entries = list, known = known)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: e.toString())
            }
        }
    }

    fun toggle(e: DavEntry) {
        val s = _state.value
        _state.value = s.copy(selected = if (e.path in s.selected) s.selected - e.path else s.selected + e.path)
    }

    fun selectAllFiles() {
        val s = _state.value
        _state.value = s.copy(selected = s.entries.filter { it.isFlac }.map { it.path }.toSet())
    }

    fun clearSelection() { _state.value = _state.value.copy(selected = emptySet()) }

    /** 選択したファイルとフォルダ(配下を再帰的に)をキューへ */
    fun enqueueSelected(onDone: (String) -> Unit) {
        val s = _state.value
        val chosen = s.entries.filter { it.path in s.selected }
        if (chosen.isEmpty()) return
        _state.value = s.copy(busy = true)
        viewModelScope.launch {
            val msg = try {
                val cfg = loc.settings.config() ?: error("サーバーを設定してください")
                val files = withContext(Dispatchers.IO) {
                    val out = ArrayList<DavEntry>()
                    for (e in chosen) if (e.isDirectory) collect(cfg, e.path, out) else out += e
                    out
                }
                val n = loc.downloads.enqueueEntries(cfg.serverId, files)
                "${n}曲をダウンロードキューに入れました"
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            _state.value = _state.value.copy(busy = false, selected = emptySet())
            open(_state.value.path)
            onDone(msg)
        }
    }

    private fun collect(cfg: com.saitateppei.opencloudflac.data.webdav.ServerConfig, path: String, out: MutableList<DavEntry>, depth: Int = 0) {
        if (depth > 20) return
        for (e in loc.dav.list(cfg, path, 1)) {
            if (e.path.trimEnd('/') == path.trimEnd('/')) continue
            if (e.isDirectory) collect(cfg, e.path, out, depth + 1) else if (e.isFlac) out += e
        }
    }

    fun registerSyncFolder(onDone: (String) -> Unit) = viewModelScope.launch {
        val server = loc.settings.server() ?: return@launch onDone("サーバーを設定してください")
        val ok = loc.sync.addFolder(server.id, _state.value.path)
        onDone(if (ok) "同期フォルダに登録しました" else "すでに登録されています")
    }
}

class DownloadsViewModel(app: Application) : AndroidViewModel(app) {
    private val d = app.locator.downloads
    val rows = d.observeAll().state(this, emptyList())
    fun pause(id: Long) = viewModelScope.launch { d.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { d.resume(id) }
    fun cancel(id: Long) = viewModelScope.launch { d.cancel(id) }
    fun pauseAll() = viewModelScope.launch { d.pauseAll() }
    fun resumeAll() = viewModelScope.launch { d.resumeAll() }
}

class SyncViewModel(app: Application) : AndroidViewModel(app) {
    private val loc = app.locator
    val folders = loc.sync.observeFolders().state(this, emptyList())
    val proposals = loc.sync.observeProposals().state(this, emptyList())
    val running = loc.sync.observeRunning().state(this, false)
    fun syncNow() = loc.sync.syncNow()
    fun setMode(f: SyncFolderEntity, auto: Boolean) = viewModelScope.launch {
        loc.sync.setMode(f, if (auto) SyncFolderEntity.MODE_AUTO else SyncFolderEntity.MODE_SUGGEST)
    }
    fun remove(id: Long) = viewModelScope.launch { loc.sync.removeFolder(id) }
    fun accept(ids: List<Long>) = viewModelScope.launch { loc.downloads.enqueueTracks(ids) }
    fun dismiss(ids: List<Long>) = viewModelScope.launch { loc.sync.dismiss(ids) }
}

data class UpdateUi(
    val checking: Boolean = false,
    val result: UpdateCheck? = null,
    val progress: Float? = null,
    val error: String? = null,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val loc = app.locator
    val server = loc.settings.observeServer().state(this, null)
    val storageBytes = loc.library.observeStorageBytes().state(this, 0L)
    val downloadedCount = loc.library.observeDownloadedCount().state(this, 0)
    val testResult = MutableStateFlow<String?>(null)
    val testing = MutableStateFlow(false)
    private val _update = MutableStateFlow(UpdateUi())
    val update = _update.asStateFlow()
    val logs = loc.db.logDao().observe().state(this, emptyList())

    fun test(url: String, user: String, token: String) = viewModelScope.launch {
        testing.value = true
        testResult.value = loc.settings.testConnection(url, user, token).fold({ it }, { e ->
            when (e) {
                is DavException -> e.message
                else -> e.message ?: e.toString()
            }
        })
        testing.value = false
    }

    fun save(url: String, user: String, token: String, onSaved: () -> Unit) = viewModelScope.launch {
        try {
            loc.settings.save(url, user, token.ifEmpty { null })
            loc.downloads.kick()
            onSaved()
        } catch (e: Exception) {
            testResult.value = e.message
        }
    }

    fun checkUpdate() = viewModelScope.launch {
        _update.value = UpdateUi(checking = true)
        _update.value = UpdateUi(result = loc.updates.checkForUpdate())
    }

    fun downloadAndInstall(info: ReleaseInfo) = viewModelScope.launch {
        if (!loc.updates.canInstall()) {
            loc.updates.openInstallPermissionSettings()
            return@launch
        }
        try {
            _update.value = _update.value.copy(progress = 0f, error = null)
            val apk = loc.updates.downloadUpdate(info) { p -> _update.value = _update.value.copy(progress = p) }
            _update.value = _update.value.copy(progress = null)
            loc.updates.install(apk)
        } catch (e: Exception) {
            _update.value = _update.value.copy(progress = null, error = e.message)
        }
    }

    fun exportPlaylists(uri: Uri, onDone: (String) -> Unit) = viewModelScope.launch {
        onDone(try {
            val json = loc.backup.exportJson()
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) }
            }
            "プレイリストを書き出しました"
        } catch (e: Exception) { "書き出しに失敗しました: ${e.message}" })
    }

    fun importPlaylists(uri: Uri, onDone: (String) -> Unit) = viewModelScope.launch {
        onDone(try {
            val json = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            }
            "${loc.backup.importJson(json)}件のプレイリストを読み込みました"
        } catch (e: Exception) { "読み込みに失敗しました: ${e.message}" })
    }

    fun clearLogs() = viewModelScope.launch { loc.db.logDao().clear() }
}
