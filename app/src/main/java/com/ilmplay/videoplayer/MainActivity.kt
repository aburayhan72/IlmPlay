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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
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
            App(accent, light, { accent = it; cfg.edit().putInt("accent", it).apply() },
                { light = it; cfg.edit().putBoolean("light", it).apply() })
        }
    }
}

@Composable
fun App(accent: Int, light: Boolean, setAccent: (Int) -> Unit, setLight: (Boolean) -> Unit) {
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
    var playing by remember { mutableStateOf<Vid?>(null) }
    var tick by remember { mutableStateOf(0) }

    playing?.let { v ->
        BackHandler { playing = null; tick++ }
        PlayerScreen(v, prefs) { playing = null; tick++ }
        return
    }
    BackHandler(folder != null) { folder = null }
    val all = videos ?: return
    val lastId = remember(tick) { prefs.getLong("last", -1) }
    val last = all.find { it.id == lastId }
    val shown = all.filter { it.name.contains(query, true) && (folder == null || it.folder == folder) }
    fun go(t: Int) { tab = t; folder = null; query = ""; searching = false }

    Scaffold(bottomBar = {
        Column {
            if (last != null) MiniBar(last, prefs) { playing = last }
            NavigationBar {
                NavigationBarItem(tab == 0, { go(0) }, { Text("🎬") }, label = { Text("Video") })
                NavigationBarItem(tab == 1, { go(1) }, { Text("📁") }, label = { Text("Folders") })
                NavigationBarItem(tab == 2, { go(2) }, { Text("🎨") }, label = { Text("Theme") })
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            val title = when { tab == 2 -> "Theme"; tab == 1 -> folder ?: "Folders"; else -> "All videos" }
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
                    Text("✕", Modifier.clickable { searching = false; query = "" }.padding(12.dp), fontSize = 18.sp)
                } else {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (canSearch) Text("🔍", Modifier.clickable { searching = true }.padding(12.dp), fontSize = 20.sp)
                }
            }
            if (tab == 2) {
                Text("Colorful Themes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    themes.forEachIndexed { i, c ->
                        Box(Modifier.size(40.dp).clip(CircleShape).background(c)
                            .then(if (i == accent) Modifier.border(3.dp, MaterialTheme.colorScheme.onBackground, CircleShape) else Modifier)
                            .clickable { setAccent(i) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Light theme", Modifier.weight(1f)); Switch(light, setLight)
                }
            } else if (tab == 1 && folder == null) {
                LazyColumn {
                    items(all.groupBy { it.folder }.toList()) { (name, list) ->
                        Row(Modifier.fillMaxWidth().clickable { folder = name }.padding(14.dp)) {
                            Text("📁  $name", Modifier.weight(1f)); Text("${list.size} videos", color = Color.Gray)
                        }
                    }
                }
            } else {
                LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown) { v ->
                        Column(Modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surface).clickable { playing = v }) {
                            Box {
                                Thumb(v, Modifier.fillMaxWidth().height(70.dp))
                                Text(fmt(v.dur), Modifier.align(Alignment.BottomEnd).background(Color(0x99000000)).padding(horizontal = 4.dp),
                                    color = Color.White, fontSize = 10.sp)
                            }
                            Column(Modifier.padding(5.dp)) {
                                Text(v.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                                Text(mb(v.size), color = Color.Gray, fontSize = 10.sp)
                                val pr = prefs.getLong("p${v.id}", 0)
                                LinearProgressIndicator(progress = { if (v.dur > 0) (pr.toFloat() / v.dur).coerceIn(0f, 1f) else 0f },
                                    Modifier.fillMaxWidth().padding(top = 3.dp))
                            }
                        }
                    }
                }
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
            Text("▶", fontSize = 26.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
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
fun PlayerScreen(v: Vid, prefs: SharedPreferences, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val act = ctx as ComponentActivity
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(v.uri)); prepare()
            seekTo(prefs.getLong("p${v.id}", 0)); playWhenReady = true
        }
    }
    val speeds = listOf(0.5f, 1f, 1.5f, 2f)
    var si by remember { mutableStateOf(1) }
    var sleepMin by remember { mutableStateOf(0) }
    var showCtl by remember { mutableStateOf(true) }
    var isPlay by remember { mutableStateOf(true) }
    var pos by remember { mutableStateOf(0L) }
    var hud by remember { mutableStateOf("") }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var land by remember { mutableStateOf(false) }
    val dur = v.dur.coerceAtLeast(1L)

    fun save() { prefs.edit().putLong("p${v.id}", player.currentPosition).putLong("last", v.id).apply() }
    LaunchedEffect(Unit) { while (true) { pos = player.currentPosition; isPlay = player.isPlaying; delay(300) } }
    LaunchedEffect(showCtl, isPlay) { if (showCtl && isPlay) { delay(3500); showCtl = false } }
    LaunchedEffect(sleepMin) { if (sleepMin > 0) { delay(sleepMin * 60_000L); player.pause() } }
    DisposableEffect(Unit) {
        act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            save(); player.release()
            act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val lp = act.window.attributes; lp.screenBrightness = -1f; act.window.attributes = lp
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ PlayerView(it).apply { this.player = player; useController = false } },
            Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))

        // Gesture layer: tap, double tap, long press (2x), brightness/volume, pinch zoom
        Box(Modifier.fillMaxSize()
            .pointerInput(si) {
                detectTapGestures(
                    onTap = { showCtl = !showCtl },
                    onDoubleTap = { o ->
                        val d = if (o.x < size.width / 2) -10000L else 10000L
                        player.seekTo((player.currentPosition + d).coerceAtLeast(0))
                    },
                    onLongPress = { player.setPlaybackSpeed(2f); hud = "2x ⏩" },
                    onPress = { tryAwaitRelease(); player.setPlaybackSpeed(speeds[si]); hud = "" }
                )
            }
            .pointerInput(Unit) {
                var left = true
                var volF = 0f
                detectVerticalDragGestures(
                    onDragStart = { o -> left = o.x < size.width / 2; volF = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() },
                    onDragEnd = { hud = "" }, onDragCancel = { hud = "" },
                    onVerticalDrag = { _, dy ->
                        val d = -dy / size.height
                        if (left) {
                            val lp = act.window.attributes
                            val cur = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
                            lp.screenBrightness = (cur + d).coerceIn(0.02f, 1f)
                            act.window.attributes = lp
                            hud = "☀ ${(lp.screenBrightness * 100).toInt()}%"
                        } else {
                            volF = (volF + d * maxVol * 1.5f).coerceIn(0f, maxVol.toFloat())
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, volF.roundToInt(), 0)
                            hud = "🔊 ${(volF / maxVol * 100).toInt()}%"
                        }
                    })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        if (ev.changes.size >= 2) {
                            scale = (scale * ev.calculateZoom()).coerceIn(1f, 4f)
                            offset = if (scale <= 1.01f) Offset.Zero else offset + ev.calculatePan()
                            ev.changes.forEach { it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            })

        if (hud.isNotEmpty()) Text(hud, Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(12.dp),
            color = Color.White, fontSize = 20.sp)

        if (showCtl) {
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0x66000000)).padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("←", Modifier.clickable { onClose() }.padding(10.dp), color = Color.White, fontSize = 22.sp)
                Text(v.name, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${speeds[si]}x", Modifier.clickable { si = (si + 1) % speeds.size; player.setPlaybackSpeed(speeds[si]) }.padding(10.dp), color = Color.White)
                Text(if (sleepMin == 0) "😴" else "😴${sleepMin}m",
                    Modifier.clickable { sleepMin = if (sleepMin == 0) 15 else if (sleepMin == 15) 30 else 0 }.padding(10.dp), color = Color.White)
                Text("⟳", Modifier.clickable {
                    land = !land
                    act.requestedOrientation = if (land) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }.padding(10.dp), color = Color.White, fontSize = 22.sp)
            }
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("⏪", Modifier.clickable { player.seekTo((player.currentPosition - 10000).coerceAtLeast(0)) }.padding(8.dp), fontSize = 28.sp)
                Text(if (isPlay) "⏸" else "▶", Modifier.clickable { if (player.isPlaying) player.pause() else player.play() }.padding(8.dp), fontSize = 44.sp)
                Text("⏩", Modifier.clickable { player.seekTo(player.currentPosition + 10000) }.padding(8.dp), fontSize = 28.sp)
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x66000000)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(fmt(pos), color = Color.White, fontSize = 12.sp)
                Slider((pos.toFloat() / dur).coerceIn(0f, 1f), { pos = (it * dur).toLong(); player.seekTo(pos) },
                    Modifier.weight(1f).padding(horizontal = 8.dp))
                Text(fmt(dur), color = Color.White, fontSize = 12.sp)
            }
        }
    }
}
