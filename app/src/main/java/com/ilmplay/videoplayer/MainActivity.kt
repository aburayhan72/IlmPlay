package com.ilmplay.videoplayer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class Vid(val id: Long, val uri: Uri, val name: String, val dur: Long, val size: Long, val folder: String)

val themes = listOf(Color(0xFF1ED28A), Color(0xFF4A86E8), Color(0xFFF4B400), Color(0xFFEA4C4C), Color(0xFFFF8A3D), Color(0xFFB65CF0))

fun loadVideos(c: Context): List<Vid> {
    val out = mutableListOf<Vid>()
    val p = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.SIZE, MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
    c.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, p, null, null,
        "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { q ->
        while (q.moveToNext()) {
            val id = q.getLong(0)
            out += Vid(id, ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                q.getString(1) ?: "video", q.getLong(2), q.getLong(3), q.getString(4) ?: "Other")
        }
    }
    return out
}

fun fmt(ms: Long): String { val s = ms / 1000; return "%02d:%02d".format(s / 60, s % 60) }
fun mb(b: Long) = "%.2fMB".format(b / 1048576.0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Root() }
    }
}

@Composable
fun Root() {
    val ctx = LocalContext.current
    val cfg = remember { ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE) }
    var accent by remember { mutableStateOf(cfg.getInt("accent", 0)) }
    var light by remember { mutableStateOf(cfg.getBoolean("light", false)) }
    val a = themes[accent]
    val scheme = if (light) lightColorScheme(primary = a, background = Color(0xFFF4F4F4), surface = Color.White)
    else darkColorScheme(primary = a, background = Color(0xFF121212), surface = Color(0xFF1E1E1E))
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            App(cfg, accent, light, { accent = it; cfg.edit().putInt("accent", it).apply() },
                { light = it; cfg.edit().putBoolean("light", it).apply() })
        }
    }
}

@Composable
fun App(cfg: SharedPreferences, accent: Int, light: Boolean, setAccent: (Int) -> Unit, setLight: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("pos", Context.MODE_PRIVATE) }
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    var videos by remember { mutableStateOf<List<Vid>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        videos = if (ok) loadVideos(ctx) else emptyList()
    }
    LaunchedEffect(Unit) { launcher.launch(perm) }

    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf<String?>(null) }
    var queue by remember { mutableStateOf<List<Vid>>(emptyList()) }
    var qIdx by remember { mutableStateOf(-1) }
    var tick by remember { mutableStateOf(0) }

    if (qIdx >= 0) {
        BackHandler { qIdx = -1; tick++ }
        PlayerScreen(queue, qIdx, prefs, cfg) { qIdx = -1; tick++ }
        return
    }
    BackHandler(folder != null) { folder = null }
    val all = videos ?: return
    val lastId = remember(tick) { prefs.getLong("last", -1) }
    val last = all.find { it.id == lastId }
    val shown = all.filter { it.name.contains(query, true) && (folder == null || it.folder == folder) }
    fun go(t: Int) { tab = t; folder = null; query = ""; searching = false }
    fun play(list: List<Vid>, v: Vid) { queue = list; qIdx = list.indexOf(v).coerceAtLeast(0) }

    Scaffold(bottomBar = {
        Column {
            if (last != null) MiniBar(last, prefs) { play(all, last) }
            NavigationBar {
                NavigationBarItem(tab == 0, { go(0) }, { Icon(Icons.Rounded.VideoLibrary, null) }, label = { Text("Video") })
                NavigationBarItem(tab == 1, { go(1) }, { Icon(Icons.Rounded.Folder, null) }, label = { Text("Folders") })
                NavigationBarItem(tab == 2, { go(2) }, { Icon(Icons.Rounded.Settings, null) }, label = { Text("Settings") })
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            val title = when { tab == 2 -> "Settings"; tab == 1 -> folder ?: "Folders"; else -> "All videos" }
            val canSearch = tab == 0 || (tab == 1 && folder != null)
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                if (searching) {
                    Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart) {
                        BasicTextField(query, { query = it }, singleLine = true,
                            textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth())
                        if (query.isEmpty()) Text("Search", color = Color.Gray)
                    }
                    IconButton({ searching = false; query = "" }) { Icon(Icons.Rounded.Close, null) }
                } else {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (canSearch) IconButton({ searching = true }) { Icon(Icons.Rounded.Search, null) }
                }
            }
            if (tab == 2) {
                SettingsTab(cfg, accent, light, setAccent, setLight)
            } else if (tab == 1 && folder == null) {
                LazyColumn {
                    items(all.groupBy { it.folder }.toList()) { (name, list) ->
                        Row(Modifier.fillMaxWidth().clickable { folder = name }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FolderIcon(Modifier.size(64.dp, 52.dp), name == "Camera")
                            Column(Modifier.padding(start = 16.dp)) {
                                Text(name, fontSize = 17.sp)
                                Text(if (list.size == 1) "1 video" else "${list.size} videos", color = Color.Gray, fontSize = 13.sp)
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown) { v ->
                        Column(Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface).clickable { play(shown, v) }) {
                            Box {
                                Thumb(v, Modifier.fillMaxWidth().height(96.dp))
                                Text(fmt(v.dur), Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                                    color = Color.White, fontSize = 11.sp)
                            }
                            Column(Modifier.padding(8.dp)) {
                                Text(v.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                Text(mb(v.size), color = Color.Gray, fontSize = 11.sp)
                                val pr = prefs.getLong("p${v.id}", 0)
                                LinearProgressIndicator(progress = { if (v.dur > 0) (pr.toFloat() / v.dur).coerceIn(0f, 1f) else 0f },
                                    Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FolderIcon(m: Modifier, camera: Boolean) {
    val c = Color(0xFF454D58)
    Box(m, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawRoundRect(c, Offset(0f, h * 0.05f), androidx.compose.ui.geometry.Size(w * 0.42f, h * 0.3f), CornerRadius(h * 0.1f))
            drawRoundRect(c, Offset(0f, h * 0.18f), androidx.compose.ui.geometry.Size(w, h * 0.82f), CornerRadius(h * 0.12f))
        }
        if (camera) Icon(Icons.Rounded.PhotoCamera, null, tint = Color(0xFF7A828F), modifier = Modifier.padding(top = 8.dp).size(24.dp))
    }
}

@Composable
fun SettingsTab(cfg: SharedPreferences, accent: Int, light: Boolean, setAccent: (Int) -> Unit, setLight: (Boolean) -> Unit) {
    var spd by remember { mutableStateOf(cfg.getFloat("speed", 1f)) }
    var seek by remember { mutableStateOf(cfg.getInt("seek", 10)) }
    var gest by remember { mutableStateOf(cfg.getBoolean("gest", true)) }
    Column(Modifier.padding(top = 4.dp)) {
        Text("Colorful Themes", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            themes.forEachIndexed { i, c ->
                Box(Modifier.size(40.dp).clip(CircleShape).background(c)
                    .then(if (i == accent) Modifier.border(3.dp, MaterialTheme.colorScheme.onBackground, CircleShape) else Modifier)
                    .clickable { setAccent(i) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Light theme", Modifier.weight(1f)); Switch(light, setLight) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Gesture control", Modifier.weight(1f)); Switch(gest, { gest = it; cfg.edit().putBoolean("gest", it).apply() })
        }
        Text("Default playback speed", Modifier.padding(top = 14.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.5f, 1f, 1.5f, 2f).forEach { s ->
                FilterChip(spd == s, { spd = s; cfg.edit().putFloat("speed", s).apply() }, { Text("${s}x") })
            }
        }
        Text("Double tap seek (seconds)", Modifier.padding(top = 14.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 15, 30).forEach { s ->
                FilterChip(seek == s, { seek = s; cfg.edit().putInt("seek", s).apply() }, { Text("${s}s") })
            }
        }
    }
}

@Composable
fun MiniBar(v: Vid, prefs: SharedPreferences, onOpen: () -> Unit) {
    val pos = prefs.getLong("p${v.id}", 0)
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable { onOpen() }) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(v, Modifier.size(72.dp, 44.dp).clip(MaterialTheme.shapes.small))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(v.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                Text("${fmt(pos)} / ${fmt(v.dur)}", color = Color.Gray, fontSize = 12.sp)
            }
            Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp).size(32.dp))
        }
        LinearProgressIndicator(progress = { if (v.dur > 0) (pos.toFloat() / v.dur).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth())
    }
}

@Composable
fun Thumb(v: Vid, m: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, v.id) {
        value = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.loadThumbnail(v.uri, Size(400, 240), null) }.getOrNull() }
    }
    bmp?.let { Image(it.asImageBitmap(), null, m, contentScale = ContentScale.Crop) } ?: Box(m.background(Color.DarkGray))
}

@Composable
fun SideBar(modifier: Modifier, pct: Int, icon: ImageVector) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$pct%", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.width(4.dp).height(110.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x66FFFFFF)), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.width(4.dp).fillMaxHeight(pct.coerceIn(0, 100) / 100f).background(MaterialTheme.colorScheme.primary))
        }
        Spacer(Modifier.height(8.dp))
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun PlayerScreen(list: List<Vid>, startIdx: Int, prefs: SharedPreferences, cfg: SharedPreferences, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val act = ctx as ComponentActivity
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    val seekMs = remember { cfg.getInt("seek", 10) * 1000L }
    val gest = remember { cfg.getBoolean("gest", true) }
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    var idx by remember { mutableStateOf(startIdx) }
    val cur = rememberUpdatedState(idx)
    val v = list[idx]
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(list[startIdx].uri)); prepare()
            seekTo(prefs.getLong("p${list[startIdx].id}", 0)); playWhenReady = true
            setPlaybackSpeed(cfg.getFloat("speed", 1f))
        }
    }
    var si by remember { mutableStateOf(speeds.indexOf(cfg.getFloat("speed", 1f)).let { if (it < 0) 2 else it }) }
    var showCtl by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var isPlay by remember { mutableStateOf(true) }
    var pos by remember { mutableStateOf(0L) }
    var hud by remember { mutableStateOf("") }
    var volP by remember { mutableStateOf(-1) }
    var briP by remember { mutableStateOf(-1) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var zooming by remember { mutableStateOf(false) }
    var land by remember { mutableStateOf(false) }
    var resize by remember { mutableStateOf(0) }
    var pa by remember { mutableStateOf(-1L) }
    var pb by remember { mutableStateOf(-1L) }
    val dur = v.dur.coerceAtLeast(1L)

    fun save() { val vv = list[cur.value]; prefs.edit().putLong("p${vv.id}", player.currentPosition).putLong("last", vv.id).apply() }
    fun open(i: Int) {
        if (i !in list.indices) return
        save(); idx = i; pa = -1; pb = -1
        val nv = list[i]
        player.setMediaItem(MediaItem.fromUri(nv.uri)); player.prepare()
        player.seekTo(prefs.getLong("p${nv.id}", 0)); player.play()
    }
    LaunchedEffect(Unit) {
        while (true) {
            pos = player.currentPosition; isPlay = player.isPlaying
            if (pb > 0 && pos >= pb && pa >= 0) player.seekTo(pa)
            delay(300)
        }
    }
    LaunchedEffect(showCtl, isPlay) { if (showCtl && isPlay) { delay(3000); showCtl = false } }
    DisposableEffect(Unit) {
        act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ic = WindowCompat.getInsetsController(act.window, act.window.decorView)
        ic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ic.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            save(); player.release()
            ic.show(WindowInsetsCompat.Type.systemBars())
            act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val lp = act.window.attributes; lp.screenBrightness = -1f; act.window.attributes = lp
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ PlayerView(it).apply { this.player = player; useController = false } },
            Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            update = { it.resizeMode = when (resize) { 1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM; 2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL; else -> AspectRatioFrameLayout.RESIZE_MODE_FIT } })

        Box(Modifier.fillMaxSize()
            .pointerInput(si, locked) {
                detectTapGestures(
                    onTap = { showCtl = !showCtl },
                    onDoubleTap = { o ->
                        if (!locked && gest) player.seekTo((player.currentPosition + (if (o.x < size.width / 2) -seekMs else seekMs)).coerceAtLeast(0))
                    },
                    onLongPress = { if (!locked && gest) { player.setPlaybackSpeed(2f); hud = "2x ⏩" } },
                    onPress = { tryAwaitRelease(); player.setPlaybackSpeed(speeds[si]); hud = "" }
                )
            }
            .pointerInput(locked) {
                var left = true
                var volF = 0f
                detectVerticalDrag
