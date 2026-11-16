package com.saitateppei.opencloudflac.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.saitateppei.opencloudflac.CrashReporter
import com.saitateppei.opencloudflac.Notifications
import com.saitateppei.opencloudflac.ui.screens.AlbumScreen
import com.saitateppei.opencloudflac.ui.screens.ArtistScreen
import com.saitateppei.opencloudflac.ui.screens.BrowserScreen
import com.saitateppei.opencloudflac.ui.screens.DownloadsScreen
import com.saitateppei.opencloudflac.ui.screens.LibraryScreen
import com.saitateppei.opencloudflac.ui.screens.LogScreen
import com.saitateppei.opencloudflac.ui.screens.PlayerScreen
import com.saitateppei.opencloudflac.ui.screens.PlaylistScreen
import com.saitateppei.opencloudflac.ui.screens.ServerSetupScreen
import com.saitateppei.opencloudflac.ui.screens.SettingsScreen
import com.saitateppei.opencloudflac.ui.screens.SyncScreen
import com.saitateppei.opencloudflac.ui.theme.AppTheme

object Routes {
    const val SETUP = "setup"
    const val LIBRARY = "library"
    const val PLAYER = "player"
    const val DOWNLOADS = "downloads"
    const val SYNC = "sync"
    const val SETTINGS = "settings"
    const val LOGS = "logs"
    fun artist(name: String) = "artist?name=${Uri.encode(name)}"
    fun album(artist: String, album: String) = "album?artist=${Uri.encode(artist)}&album=${Uri.encode(album)}"
    fun playlist(id: Long) = "playlist/$id"
    fun browse(path: String) = "browse?path=${Uri.encode(path)}"
}

class MainActivity : ComponentActivity() {
    private val main: MainViewModel by viewModels()
    private val pendingRoute = mutableStateOf<String?>(null)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        pendingRoute.value = intent.getStringExtra(Notifications.EXTRA_ROUTE)
        val crash = CrashReporter.take(this)
        setContent {
            AppTheme {
                AppRoot(main, pendingRoute.value) { pendingRoute.value = null }
                if (crash != null) CrashDialog(crash)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingRoute.value = intent.getStringExtra(Notifications.EXTRA_ROUTE)
    }
}

@Composable
private fun AppRoot(main: MainViewModel, route: String?, consumeRoute: () -> Unit) {
    val hasServerAtStart by main.hasServerAtStart.collectAsStateWithLifecycle()
    val server by main.server.collectAsStateWithLifecycle()
    val player by main.player.state.collectAsStateWithLifecycle()
    val message by main.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val nav = rememberNavController()

    val startWithServer = hasServerAtStart
    if (startWithServer == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    MessageEffect(message, { snackbar.showSnackbar(it) }, main::consumeMessage)
    LaunchedEffect(route, server) {
        if (route != null && server != null) { nav.navigate(route) { launchSingleTop = true }; consumeRoute() }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                AppNavHost(nav, main, start = if (startWithServer) Routes.LIBRARY else Routes.SETUP)
            }
            if (current != Routes.PLAYER && current != Routes.SETUP) {
                Box(Modifier.navigationBarsPadding()) {
                    MiniPlayer(player, onOpen = { nav.navigate(Routes.PLAYER) }, onToggle = main.player::togglePlay, onNext = { main.player.next() })
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, main: MainViewModel, start: String) {
    NavHost(nav, startDestination = start) {
        composable(Routes.SETUP) {
            ServerSetupScreen(firstRun = true, onDone = {
                nav.navigate(Routes.LIBRARY) { popUpTo(Routes.SETUP) { inclusive = true } }
            }, onBack = null)
        }
        composable(Routes.LIBRARY) { LibraryScreen(nav, main) }
        composable(
            "artist?name={name}",
            arguments = listOf(navArgument("name") { type = NavType.StringType; defaultValue = "" }),
        ) { ArtistScreen(nav, main, it.arguments?.getString("name").orEmpty()) }
        composable(
            "album?artist={artist}&album={album}",
            arguments = listOf(
                navArgument("artist") { type = NavType.StringType; defaultValue = "" },
                navArgument("album") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { AlbumScreen(nav, main, it.arguments?.getString("artist").orEmpty(), it.arguments?.getString("album").orEmpty()) }
        composable("playlist/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            PlaylistScreen(nav, main, it.arguments?.getLong("id") ?: 0)
        }
        composable(
            "browse?path={path}",
            arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "/" }),
        ) { BrowserScreen(nav, main, it.arguments?.getString("path") ?: "/") }
        composable(Routes.DOWNLOADS) { DownloadsScreen(nav) }
        composable(Routes.SYNC) { SyncScreen(nav, main) }
        composable(Routes.SETTINGS) { SettingsScreen(nav, main) }
        composable("server") { ServerSetupScreen(firstRun = false, onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() }) }
        composable(Routes.LOGS) { LogScreen(nav) }
        composable(Routes.PLAYER) { PlayerScreen(nav, main) }
    }
}

/** 前回のクラッシュ内容。コピーして共有できる。 */
@Composable
private fun CrashDialog(text: String) {
    var open by remember { mutableStateOf(true) }
    if (!open) return
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { open = false },
        title = { androidx.compose.material3.Text("前回、アプリが異常終了しました") },
        text = {
            androidx.compose.foundation.text.selection.SelectionContainer {
                androidx.compose.material3.Text(
                    text,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)); open = false
            }) { androidx.compose.material3.Text("コピーして閉じる") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { open = false }) { androidx.compose.material3.Text("閉じる") } },
    )
}
