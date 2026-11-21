package com.ilmplay.videoplayer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class Vid(val id: Long, val uri: Uri, val name: String, val dur: Long, val size: Long, val folder: String)

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
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF1ED28A), background = Color(0xFF121212), surface = Color(0xFF1E1E1E))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App() }
            }
        }
    }
}

@Composable
fun App() {
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
    var folder by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<Vid?>(null) }
    var tick by remember { mutableStateOf(0) }

    playing?.let { v ->
        BackHandler { playing = null; tick++ }
        PlayerScreen(v, prefs) { playing = null; tick++ }
        return
    }
    val all = videos ?: return
    val lastId = remember(tick) { prefs.getLong("last", -1) }
    val last = all.find { it.id == lastId }
    val shown = all.filter { it.name.contains(query, true) && (folder == null || it.folder == folder) }

    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(tab == 0, { tab = 0; folder = null }, { Text("🎬") }, label = { Text("Video") })
            NavigationBarItem(tab == 1, { tab = 1; folder = null }, { Text("📁") }, label = { Text("Folders") })
        }
    }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp)) {
            Text(if (folder != null) "📁 $folder" else "VIDEO", style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp),
                placeholder = { Text("Search") }, singleLine = true)
            if (tab == 1 && folder == null) {
                LazyColumn {
                    items(all.groupBy { it.folder }.toList()) { (name, list) ->
                        Row(Modifier.fillMaxWidth().clickable { folder = name }.padding(14.dp)) {
                            Text("📁  $name", Modifier.weight(1f)); Text("${list.size} videos", color = Color.Gray)
                        }
                    }
                }
            } else {
                Text("${shown.size} VIDEOS total ${mb(shown.sumOf { it.size })}", color = Color.Gray)
                if (last != null && query.isEmpty() && folder == null) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { playing = last }) {
                        Thumb(last, Modifier.size(120.dp, 70.dp).clip(MaterialTheme.shapes.small))
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(last.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${fmt(prefs.getLong("p${last.id}", 0))} / ${fmt(last.dur)}", color = Color.Gray)
                            Text("Continue watching", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown) { v ->
                        Column(Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface).clickable { playing = v }) {
                            Thumb(v, Modifier.fillMaxWidth().height(100.dp))
                            Column(Modifier.padding(8.dp)) {
                                Text(v.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row { Text(fmt(v.dur), Modifier.weight(1f), color = Color.Gray); Text(mb(v.size), color = Color.Gray) }
                                val pr = prefs.getLong("p${v.id}", 0)
                                LinearProgressIndicator(progress = { if (v.dur > 0) (pr.toFloat() / v.dur).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
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
fun PlayerScreen(v: Vid, prefs: android.content.SharedPreferences, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(v.uri)); prepare()
            seekTo(prefs.getLong("p${v.id}", 0)); playWhenReady = true
        }
    }
    val speeds = listOf(0.5f, 1f, 1.5f, 2f)
    var si by remember { mutableStateOf(1) }
    var sleepMin by remember { mutableStateOf(0) }
    fun save() { prefs.edit().putLong("p${v.id}", player.currentPosition).putLong("last", v.id).apply() }
    LaunchedEffect(sleepMin) { if (sleepMin > 0) { delay(sleepMin * 60_000L); player.pause() } }
    DisposableEffect(Unit) { onDispose { save(); player.release() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ PlayerView(it).apply { this.player = player; setShowSubtitleButton(true) } }, Modifier.fillMaxSize())
        Row(Modifier.padding(12.dp).align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("←  ${v.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) }
            TextButton({ si = (si + 1) % speeds.size; player.setPlaybackSpeed(speeds[si]) }) { Text("${speeds[si]}x") }
            TextButton({ sleepMin = if (sleepMin == 0) 15 else if (sleepMin == 15) 30 else 0 }) { Text(if (sleepMin == 0) "😴 off" else "😴 ${sleepMin}m") }
        }
    }
}
