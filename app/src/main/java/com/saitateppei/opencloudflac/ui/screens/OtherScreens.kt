package com.saitateppei.opencloudflac.ui.screens

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.navigation.NavController
import com.saitateppei.opencloudflac.BuildConfig
import com.saitateppei.opencloudflac.data.db.DownloadTaskEntity
import com.saitateppei.opencloudflac.data.db.SyncFolderEntity
import com.saitateppei.opencloudflac.update.UpdateCheck
import com.saitateppei.opencloudflac.ui.Artwork
import com.saitateppei.opencloudflac.ui.BrowserViewModel
import com.saitateppei.opencloudflac.ui.DownloadsViewModel
import com.saitateppei.opencloudflac.ui.EmptyState
import com.saitateppei.opencloudflac.ui.MainViewModel
import com.saitateppei.opencloudflac.ui.Routes
import com.saitateppei.opencloudflac.ui.SettingsViewModel
import com.saitateppei.opencloudflac.ui.SyncViewModel
import com.saitateppei.opencloudflac.ui.formatBytes
import com.saitateppei.opencloudflac.ui.formatTime
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleScaffold(
    nav: NavController?,
    title: String,
    actions: @Composable () -> Unit = {},
    fab: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (nav != null) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
                },
                actions = { actions() },
            )
        },
        floatingActionButton = fab,
    ) { pad -> content(Modifier.padding(pad)) }
}

// ---------------------------------------------------------------- サーバー閲覧 (F2, F3)

@Composable
fun BrowserScreen(nav: NavController, main: MainViewModel, path: String) {
    val vm: BrowserViewModel = viewModel(key = "browse:$path")
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(path) { if (s.entries.isEmpty() && !s.loading) vm.open(path) }

    SimpleScaffold(
        nav,
        if (path == "/") "サーバー" else path.trimEnd('/').substringAfterLast('/'),
        actions = {
            IconButton(onClick = vm::selectAllFiles) { Icon(Icons.Default.SelectAll, "ファイルをすべて選択") }
            IconButton(onClick = { vm.registerSyncFolder(main::toast) }) { Icon(Icons.Default.Sync, "このフォルダを同期に登録") }
            IconButton(onClick = { vm.open(path) }) { Icon(Icons.Default.Refresh, "再読み込み") }
        },
        fab = {
            if (s.selected.isNotEmpty()) ExtendedFloatingActionButton(onClick = { vm.enqueueSelected(main::toast) }) {
                if (s.busy) CircularProgressIndicator(Modifier.size(20.dp)) else Text("${s.selected.size}件をダウンロード")
            }
        },
    ) { m ->
        Column(m.fillMaxSize()) {
            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            s.error?.let {
                Column(Modifier.padding(16.dp)) {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.open(path) }) { Text("再試行") }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (!s.loading && s.error == null && s.entries.isEmpty()) item { EmptyState("フォルダまたはFLACファイルがありません") }
                items(s.entries, key = { it.path }) { e ->
                    val known = s.known[e.path]
                    ListItem(
                        leadingContent = { Icon(if (e.isDirectory) Icons.Default.Folder else Icons.Default.MusicNote, null) },
                        headlineContent = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            if (!e.isDirectory) Text(
                                formatBytes(e.size) + when {
                                    known?.hasUpdate == true -> " · 保存済み(サーバーで更新あり)"
                                    known?.isDownloaded == true -> " · 保存済み"
                                    else -> ""
                                }
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (known?.isDownloaded == true && !known.hasUpdate) Icon(Icons.Default.CheckCircle, "保存済み", tint = MaterialTheme.colorScheme.primary)
                                Checkbox(checked = e.path in s.selected, onCheckedChange = { vm.toggle(e) })
                            }
                        },
                        modifier = Modifier.clickable {
                            if (e.isDirectory) nav.navigate(Routes.browse(e.path)) else vm.toggle(e)
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- ダウンロード一覧 (F4)

@Composable
fun DownloadsScreen(nav: NavController) {
    val vm: DownloadsViewModel = viewModel()
    val rows by vm.rows.collectAsStateWithLifecycle()
    SimpleScaffold(nav, "ダウンロード", actions = {
        TextButton(onClick = vm::pauseAll) { Text("すべて中断") }
        TextButton(onClick = vm::resumeAll) { Text("すべて再開") }
    }) { m ->
        LazyColumn(m.fillMaxSize()) {
            if (rows.isEmpty()) item { EmptyState("ダウンロード中の曲はありません") }
            items(rows, key = { it.trackId }) { r ->
                val label = when (r.state) {
                    DownloadTaskEntity.STATE_RUNNING -> "ダウンロード中"
                    DownloadTaskEntity.STATE_QUEUED -> "待機中"
                    DownloadTaskEntity.STATE_PAUSED -> "中断"
                    else -> "失敗"
                }
                ListItem(
                    headlineContent = { Text(r.title ?: r.remotePath.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            val frac = if (r.sizeBytes > 0) (r.bytesDone.toFloat() / r.sizeBytes).coerceIn(0f, 1f) else 0f
                            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                            Text("$label · ${formatBytes(r.bytesDone)} / ${formatBytes(r.sizeBytes)}")
                            r.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    },
                    trailingContent = {
                        Row {
                            when (r.state) {
                                DownloadTaskEntity.STATE_RUNNING, DownloadTaskEntity.STATE_QUEUED ->
                                    IconButton(onClick = { vm.pause(r.trackId) }) { Icon(Icons.Default.Pause, "中断") }
                                else -> IconButton(onClick = { vm.resume(r.trackId) }) {
                                    Icon(if (r.state == DownloadTaskEntity.STATE_FAILED) Icons.Default.Refresh else Icons.Default.PlayArrow, "再開")
                                }
                            }
                            IconButton(onClick = { vm.cancel(r.trackId) }) { Icon(Icons.Default.Close, "キャンセル") }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

// ---------------------------------------------------------------- 同期 (F11, F9)

@Composable
fun SyncScreen(nav: NavController, main: MainViewModel) {
    val vm: SyncViewModel = viewModel()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val proposals by vm.proposals.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(setOf<Long>()) }
    LaunchedEffect(proposals) { selected = selected.intersect(proposals.map { it.id }.toSet()) }

    SimpleScaffold(nav, "同期と新しい曲", actions = {
        TextButton(onClick = vm::syncNow, enabled = !running && folders.isNotEmpty()) { Text(if (running) "同期中…" else "今すぐ同期") }
    }) { m ->
        LazyColumn(m.fillMaxSize()) {
            item { SectionTitle("同期するフォルダ") }
            if (folders.isEmpty()) item { EmptyState("サーバー閲覧画面の同期アイコンで、フォルダを登録できます") }
            items(folders, key = { it.id }) { f ->
                ListItem(
                    leadingContent = { Icon(Icons.Default.Folder, null) },
                    headlineContent = { Text(f.remotePath) },
                    supportingContent = {
                        Text(
                            (if (f.mode == SyncFolderEntity.MODE_AUTO) "自動でダウンロード" else "提案のみ") +
                                (f.lastScanAt?.let { " · 前回 " + formatDate(it) } ?: " · 未走査")
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = f.mode == SyncFolderEntity.MODE_AUTO, onCheckedChange = { vm.setMode(f, it) })
                            IconButton(onClick = { vm.remove(f.id) }) { Icon(Icons.Default.Delete, "登録解除") }
                        }
                    },
                )
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("新しい曲・更新された曲 (${proposals.size})")
                if (proposals.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val targets = if (selected.isEmpty()) proposals.map { it.id } else selected.toList()
                    Button(onClick = { vm.accept(targets); main.toast("${targets.size}曲をキューに入れました"); selected = emptySet() }) {
                        Text(if (selected.isEmpty()) "すべてダウンロード" else "選択した${selected.size}曲をダウンロード")
                    }
                    OutlinedButton(onClick = { vm.dismiss(targets); selected = emptySet() }) { Text("見送る") }
                }
            }
            if (proposals.isEmpty()) item { EmptyState("提案はありません") }
            items(proposals, key = { it.id }) { t ->
                ListItem(
                    leadingContent = { Checkbox(t.id in selected, { c -> selected = if (c) selected + t.id else selected - t.id }) },
                    headlineContent = { Text(t.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text((if (t.isDownloaded) "更新あり · " else "新しい曲 · ") + t.remotePath.substringBeforeLast('/'),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

private fun formatDate(ms: Long) = SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(ms))

// ---------------------------------------------------------------- サーバー設定 (F1)

@Composable
fun ServerSetupScreen(firstRun: Boolean, onDone: () -> Unit, onBack: (() -> Unit)?) {
    val vm: SettingsViewModel = viewModel()
    val server by vm.server.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val result by vm.testResult.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var initialized by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(server) {
        val s = server
        if (!initialized && s != null) { url = s.baseUrl; user = s.username; initialized = true }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (firstRun) "サーバーの設定" else "接続先") },
            navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (firstRun) Text("OpenCloudのWebDAVのURL、ユーザー名、アプリトークンを入力してください。スマホがtailnetに接続している必要があります。")
            OutlinedTextField(url, { url = it }, label = { Text("WebDAVのURL") },
                placeholder = { Text("https://cloud.example.ts.net/remote.php/webdav") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("ユーザー名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it },
                label = { Text(if (server != null) "アプリトークン(変更するときだけ入力)" else "アプリトークン") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.test(url, user, token) }, enabled = !testing && url.isNotBlank()) { Text("接続テスト") }
                Button(
                    onClick = { vm.save(url, user, token, onDone) },
                    enabled = url.isNotBlank() && user.isNotBlank() && (token.isNotBlank() || server != null),
                ) { Text("保存") }
                if (testing) CircularProgressIndicator(Modifier.size(24.dp))
            }
            result?.let { Text(it, color = if (it == "接続できました") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            Text("トークンは端末のKeystoreで暗号化して保存します。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------- 設定 (F10, 9.3)

@Composable
fun SettingsScreen(nav: NavController, main: MainViewModel) {
    val vm: SettingsViewModel = viewModel()
    val server by vm.server.collectAsStateWithLifecycle()
    val bytes by vm.storageBytes.collectAsStateWithLifecycle()
    val count by vm.downloadedCount.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportPlaylists(uri, main::toast)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importPlaylists(uri, main::toast)
    }

    SimpleScaffold(nav, "設定") { m ->
        Column(m.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle("接続先")
            ListItem(
                headlineContent = { Text(server?.baseUrl ?: "未設定") },
                supportingContent = { Text(server?.username ?: "") },
                modifier = Modifier.clickable { nav.navigate("server") },
            )
            SectionTitle("保存領域")
            ListItem(headlineContent = { Text("${count}曲 · ${formatBytes(bytes)}") },
                supportingContent = { Text("曲ごとのメニューから「ローカル保存を削除」で容量を空けられます") })
            SectionTitle("同期")
            ListItem(headlineContent = { Text("同期フォルダと新しい曲の提案") }, modifier = Modifier.clickable { nav.navigate(Routes.SYNC) })
            SectionTitle("プレイリストのバックアップ")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    try { exportLauncher.launch("playlists.json") } catch (_: ActivityNotFoundException) { }
                }) { Text("書き出す") }
                OutlinedButton(onClick = {
                    try { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) } catch (_: ActivityNotFoundException) { }
                }) { Text("読み込む") }
            }
            SectionTitle("アプリの更新")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("現在のバージョン: ${BuildConfig.VERSION_NAME}")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = vm::checkUpdate, enabled = !update.checking && update.progress == null) { Text("更新を確認") }
                    if (update.checking) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(24.dp))
                }
                when (val r = update.result) {
                    is UpdateCheck.UpToDate -> Text("最新です")
                    is UpdateCheck.Failed -> Text(r.message, color = MaterialTheme.colorScheme.error)
                    is UpdateCheck.Available -> {
                        Text("新しいバージョン ${r.release.version} があります(${formatBytes(r.release.apkSize)})")
                        val p = update.progress
                        if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        else Button(onClick = { vm.downloadAndInstall(r.release) }) { Text("ダウンロードしてインストール") }
                        Text("インストール時にOSの確認画面が表示されます。", style = MaterialTheme.typography.bodySmall)
                    }
                    null -> Unit
                }
                update.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            SectionTitle("その他")
            ListItem(headlineContent = { Text("ログ") }, modifier = Modifier.clickable { nav.navigate(Routes.LOGS) })
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------- ログ (9.1)

@Composable
fun LogScreen(nav: NavController) {
    val vm: SettingsViewModel = viewModel()
    val logs by vm.logs.collectAsStateWithLifecycle()
    SimpleScaffold(nav, "ログ", actions = { TextButton(onClick = vm::clearLogs) { Text("消去") } }) { m ->
        LazyColumn(m.fillMaxSize()) {
            if (logs.isEmpty()) item { EmptyState("ログはありません") }
            items(logs, key = { it.id }) { l ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("${formatDate(l.time)}  ${l.level}", style = MaterialTheme.typography.labelSmall,
                        color = if (l.level == "ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(l.message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 再生画面 (F6)

@Composable
fun PlayerScreen(nav: NavController, main: MainViewModel) {
    val s by main.player.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { while (true) { main.player.refresh(); delay(500) } }
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) }

    SimpleScaffold(nav, "再生中", actions = {
        IconButton(onClick = { main.stop(); nav.popBackStack() }) { Icon(Icons.Default.Stop, "再生を停止") }
        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ExpandMore, "閉じる") }
    }) { m ->
        if (!s.hasItem) { EmptyState("再生中の曲はありません"); return@SimpleScaffold }
        Column(m.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val artwork = s.artworkUri?.path?.let { File(it) }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                Artwork(artwork, maxWidth, Modifier.aspectRatio(1f))
            }
            Text(s.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${s.artist} · ${s.album}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Column(Modifier.fillMaxWidth()) {
                val dur = s.durationMs.coerceAtLeast(1)
                Slider(
                    value = if (dragging) dragPos else (s.positionMs.toFloat() / dur).coerceIn(0f, 1f),
                    onValueChange = { dragging = true; dragPos = it },
                    onValueChangeFinished = { main.player.seekTo((dragPos * dur).toLong()); dragging = false },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(if (dragging) (dragPos * dur).toLong() else s.positionMs), style = MaterialTheme.typography.labelSmall)
                    Text(formatTime(s.durationMs), style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(onClick = main.player::toggleShuffle) {
                    Icon(Icons.Default.Shuffle, "シャッフル", tint = if (s.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { main.player.previous() }) { Icon(Icons.Default.SkipPrevious, "前の曲", Modifier.size(36.dp)) }
                FilledIconButton(onClick = main.player::togglePlay, modifier = Modifier.size(72.dp)) {
                    Icon(if (s.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (s.isPlaying) "一時停止" else "再生", Modifier.size(40.dp))
                }
                IconButton(onClick = { main.player.next() }) { Icon(Icons.Default.SkipNext, "次の曲", Modifier.size(36.dp)) }
                IconButton(onClick = main.player::cycleRepeat) {
                    Icon(
                        if (s.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "リピート",
                        tint = if (s.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
