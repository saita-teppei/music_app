package com.saitateppei.opencloudflac.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.saitateppei.opencloudflac.data.db.TrackEntity
import com.saitateppei.opencloudflac.locator
import com.saitateppei.opencloudflac.playback.MediaTree
import com.saitateppei.opencloudflac.ui.AddToPlaylistDialog
import com.saitateppei.opencloudflac.ui.ArtistViewModel
import com.saitateppei.opencloudflac.ui.Artwork
import com.saitateppei.opencloudflac.ui.ConfirmDialog
import com.saitateppei.opencloudflac.ui.EmptyState
import com.saitateppei.opencloudflac.ui.LibraryViewModel
import com.saitateppei.opencloudflac.ui.MainViewModel
import com.saitateppei.opencloudflac.ui.PlaylistViewModel
import com.saitateppei.opencloudflac.ui.Routes
import com.saitateppei.opencloudflac.ui.TextInputDialog
import com.saitateppei.opencloudflac.ui.TrackRow
import androidx.compose.ui.platform.LocalContext

/** 曲の一覧と、その上の「プレイリストに追加」「ローカル削除」ダイアログをまとめて扱う */
@Composable
fun TrackList(
    main: MainViewModel,
    parent: String,
    tracks: List<TrackEntity>,
    queued: Set<Long> = emptySet(),
    header: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val lib = LocalContext.current.locator.library
    val player by main.player.state.collectAsStateWithLifecycle()
    val playlists by main.playlists.collectAsStateWithLifecycle()
    var addFor by remember { mutableStateOf<TrackEntity?>(null) }
    var deleteFor by remember { mutableStateOf<TrackEntity?>(null) }
    val playingId = player.currentMediaId?.let { MediaTree.parseTrackId(it)?.second }

    LazyColumn(modifier.fillMaxSize()) {
        if (header != null) item { header() }
        if (tracks.isEmpty()) item { EmptyState("曲がありません") }
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            TrackRow(
                track = t,
                cover = lib.coverFile(t),
                playing = t.id == playingId,
                queued = t.id in queued,
                onClick = { main.play(parent, tracks, i) },
                onAddToPlaylist = { addFor = t },
                onDeleteLocal = { deleteFor = t },
                onRedownload = { main.redownload(listOf(t.id)) },
            )
        }
    }
    addFor?.let { t ->
        AddToPlaylistDialog(
            playlists,
            onPick = { main.addToPlaylist(it, listOf(t.id)); addFor = null },
            onCreate = { main.createPlaylistAndAdd(it, listOf(t.id)); addFor = null },
            onDismiss = { addFor = null },
        )
    }
    deleteFor?.let { t ->
        ConfirmDialog(
            "ローカル保存を削除",
            "「${t.displayTitle}」を端末から削除します。プレイリストには残り、必要なときに再ダウンロードできます。",
            onOk = { main.deleteLocal(listOf(t.id)); deleteFor = null },
            onDismiss = { deleteFor = null },
        )
    }
}

@Composable
fun PlayButtons(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        FilledTonalButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, null); Text("すべて再生") }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
        OutlinedButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, null); Text("シャッフル") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(nav: NavController, main: MainViewModel) {
    val vm: LibraryViewModel = viewModel()
    val query by vm.query.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    val queued by vm.queued.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) TextField(
                        value = query, onValueChange = { vm.query.value = it },
                        placeholder = { Text("曲名・アーティスト・アルバム") }, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth(),
                    ) else Text("ライブラリ")
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) vm.query.value = "" }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "検索")
                    }
                    IconButton(onClick = { nav.navigate(Routes.browse("/")) }) { Icon(Icons.Default.Cloud, "サーバーを閲覧") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "メニュー") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("ダウンロード一覧") }, leadingIcon = { Icon(Icons.Default.Download, null) },
                                onClick = { menu = false; nav.navigate(Routes.DOWNLOADS) })
                            DropdownMenuItem(text = { Text("同期と新しい曲") }, leadingIcon = { Icon(Icons.Default.Sync, null) },
                                onClick = { menu = false; nav.navigate(Routes.SYNC) })
                            DropdownMenuItem(text = { Text("設定") }, leadingIcon = { Icon(Icons.Default.Settings, null) },
                                onClick = { menu = false; nav.navigate(Routes.SETTINGS) })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (searching && query.isNotBlank()) {
                val results by vm.results.collectAsStateWithLifecycle()
                TrackList(main, MediaTree.SONGS, results, queued)
                return@Column
            }
            val tabs = listOf("曲", "アーティスト", "アルバム", "プレイリスト")
            // タブは左右スワイプでも切り替えられる。開いていたタブは画面回転や戻ったときも保つ。
            val pager = rememberPagerState(initialPage = tab) { tabs.size }
            val scope = rememberCoroutineScope()
            LaunchedEffect(pager.currentPage) { tab = pager.currentPage }
            PrimaryTabRow(selectedTabIndex = pager.currentPage) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = pager.currentPage == i, onClick = { scope.launch { pager.animateScrollToPage(i) } }, text = { Text(t) })
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { it }) { page ->
            when (page) {
                0 -> {
                    val tracks by vm.tracks.collectAsStateWithLifecycle()
                    if (tracks.isEmpty()) EmptyState("まだ曲がありません。右上の雲のアイコンからサーバーの曲をダウンロードしてください。")
                    else TrackList(main, MediaTree.SONGS, tracks, queued, header = {
                        PlayButtons({ main.play(MediaTree.SONGS, tracks, 0, shuffle = false) }, { main.play(MediaTree.SONGS, tracks, 0, shuffle = true) })
                    })
                }
                1 -> {
                    val artists by vm.artists.collectAsStateWithLifecycle()
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (artists.isEmpty()) item { EmptyState("アーティストがありません") }
                        items(artists, key = { it.name }) { a ->
                            ListItem(
                                leadingContent = { Icon(Icons.Default.Person, null) },
                                headlineContent = { Text(a.name.ifEmpty { TrackEntity.UNKNOWN_ARTIST }) },
                                supportingContent = { Text("${a.trackCount}曲") },
                                modifier = Modifier.clickable { nav.navigate(Routes.artist(a.name)) },
                            )
                        }
                    }
                }
                2 -> {
                    val albums by vm.albums.collectAsStateWithLifecycle()
                    val lib = LocalContext.current.locator.library
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (albums.isEmpty()) item { EmptyState("アルバムがありません") }
                        items(albums, key = { it.artist + "\u0000" + it.album }) { a ->
                            ListItem(
                                leadingContent = { Artwork(lib.coverFile(a.coverFile), 48.dp) },
                                headlineContent = { Text(a.album.ifEmpty { TrackEntity.UNKNOWN_ALBUM }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text("${a.artist.ifEmpty { TrackEntity.UNKNOWN_ARTIST }} · ${a.trackCount}曲") },
                                modifier = Modifier.clickable { nav.navigate(Routes.album(a.artist, a.album)) },
                            )
                        }
                    }
                }
                else -> PlaylistsTab(nav, main)
            }
            }
        }
    }
}

@Composable
private fun PlaylistsTab(nav: NavController, main: MainViewModel) {
    val vm: PlaylistViewModel = viewModel()
    val playlists by main.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListItem(
                leadingContent = { Icon(Icons.Default.Add, null) },
                headlineContent = { Text("新しいプレイリスト") },
                modifier = Modifier.clickable { creating = true },
            )
        }
        items(playlists, key = { it.id }) { p ->
            ListItem(
                leadingContent = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                headlineContent = { Text(p.name) },
                supportingContent = { Text("${p.itemCount}曲") },
                modifier = Modifier.clickable { nav.navigate(Routes.playlist(p.id)) },
            )
        }
    }
    if (creating) TextInputDialog("新しいプレイリスト", "", onOk = { vm.create(it); creating = false }, onDismiss = { creating = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(nav: NavController, title: String, actions: @Composable () -> Unit = {}, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
                actions = { actions() },
            )
        },
    ) { pad -> content(Modifier.padding(pad)) }
}

@Composable
fun ArtistScreen(nav: NavController, main: MainViewModel, artist: String) {
    val vm: ArtistViewModel = viewModel()
    val tracks by remember(artist) { vm.tracks(artist) }.collectAsState(emptyList())
    val parent = MediaTree.artistId(artist)
    DetailScaffold(nav, artist.ifEmpty { TrackEntity.UNKNOWN_ARTIST }) { m ->
        TrackList(main, parent, tracks, modifier = m, header = {
            PlayButtons({ main.play(parent, tracks, 0, shuffle = false) }, { main.play(parent, tracks, 0, shuffle = true) })
        })
    }
}

@Composable
fun AlbumScreen(nav: NavController, main: MainViewModel, artist: String, album: String) {
    val vm: ArtistViewModel = viewModel()
    val tracks by remember(artist, album) { vm.albumTracks(album, artist) }.collectAsState(emptyList())
    val parent = MediaTree.albumId(artist, album)
    val lib = LocalContext.current.locator.library
    DetailScaffold(nav, album.ifEmpty { TrackEntity.UNKNOWN_ALBUM }) { m ->
        TrackList(main, parent, tracks, modifier = m, header = {
            Column {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(tracks.firstNotNullOfOrNull { lib.coverFile(it) }, 96.dp)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(album.ifEmpty { TrackEntity.UNKNOWN_ALBUM }, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        Text(artist.ifEmpty { TrackEntity.UNKNOWN_ARTIST })
                        Text("${tracks.size}曲")
                    }
                }
                PlayButtons({ main.play(parent, tracks, 0, shuffle = false) }, { main.play(parent, tracks, 0, shuffle = true) })
            }
        })
    }
}

@Composable
fun PlaylistScreen(nav: NavController, main: MainViewModel, id: Long) {
    val vm: PlaylistViewModel = viewModel()
    val playlist by remember(id) { vm.playlist(id) }.collectAsState(null)
    val items by remember(id) { vm.items(id) }.collectAsState(emptyList())
    val lib = LocalContext.current.locator.library
    val downloads = LocalContext.current.locator.downloads
    val queued by remember { downloads.observeIds() }.collectAsState(emptyList())
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val parent = MediaTree.playlistId(id)
    val tracks = items.map { it.track }
    val missing = tracks.filter { !it.isDownloaded && it.id !in queued }

    DetailScaffold(nav, playlist?.name ?: "", actions = {
        IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "名前を変更") }
        IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, "削除") }
    }) { m ->
        LazyColumn(m.fillMaxSize()) {
            item {
                Column {
                    PlayButtons({ main.play(parent, tracks, 0, shuffle = false) }, { main.play(parent, tracks, 0, shuffle = true) })
                    if (missing.isNotEmpty()) {
                        OutlinedButton(onClick = { main.redownload(missing.map { it.id }) }, modifier = Modifier.padding(horizontal = 16.dp)) {
                            Icon(Icons.Default.Download, null); Text("未ダウンロードの${missing.size}曲を取得")
                        }
                    }
                }
            }
            if (items.isEmpty()) item { EmptyState("ライブラリの曲メニューから追加できます") }
            itemsIndexed(items, key = { _, r -> r.itemId }) { i, row ->
                TrackRow(
                    track = row.track,
                    cover = lib.coverFile(row.track),
                    playing = false,
                    queued = row.track.id in queued,
                    onClick = { main.play(parent, tracks, i) },
                    onAddToPlaylist = null,
                    onDeleteLocal = { main.deleteLocal(listOf(row.track.id)) },
                    onRedownload = { main.redownload(listOf(row.track.id)) },
                    extraMenuItems = buildList {
                        if (i > 0) add("上へ移動" to { vm.move(id, i, i - 1) })
                        if (i < items.size - 1) add("下へ移動" to { vm.move(id, i, i + 1) })
                        add("プレイリストから外す" to { vm.remove(id, row.itemId) })
                    },
                )
            }
        }
    }
    if (renaming) TextInputDialog("名前を変更", playlist?.name ?: "", onOk = { vm.rename(id, it); renaming = false }, onDismiss = { renaming = false })
    if (deleting) ConfirmDialog("プレイリストを削除", "「${playlist?.name}」を削除します。曲そのものは削除されません。",
        onOk = { vm.delete(id); deleting = false; nav.popBackStack() }, onDismiss = { deleting = false })
}
