package com.ilmplay.videoplayer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
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

fun sortVids(list: List<Vid>, prefs: SharedPreferences, sort: Int, asc: Boolean): List<Vid> {
    val base = when (sort) {
        0 -> list.sortedBy { it.name.lowercase() }
        2 -> list.sortedBy { prefs.getLong("t${it.id}", 0) }
        3 -> list.sortedBy { it.dur }
        4 -> list.sortedBy { it.size }
        else -> list.reversed()
    }
    return if (asc) base else base.reversed()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Root() }
    }
}

@Composable
fun Root() {
    val ctx = LocalContext.current
    val act = ctx as ComponentActivity
    val cfg = remember { ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE) }
    var accent by remember { mutableStateOf(cfg.getInt("accent", 0)) }
    var light by remember { mutableStateOf(cfg.getBoolean("light", false)) }
    SideEffect {
        val ic = WindowCompat.getInsetsController(act.window, act.window.decorView)
        ic.isAppearanceLightStatusBars = light
        ic.isAppearanceLightNavigationBars = light
    }
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
    var layout by remember { mutableStateOf(cfg.getInt("layout", 1)) }
    var sort by remember { mutableStateOf(cfg.getInt("sort", 1)) }
    var asc by remember { mutableStateOf(cfg.getBoolean("asc", false)) }
    var showView by remember { mutableStateOf(false) }

    if (qIdx >= 0) {
        BackHandler { qIdx = -1; tick++ }
        PlayerScreen(queue, qIdx, prefs, cfg) { qIdx = -1; tick++ }
        return
    }
    BackHandler(folder != null) { folder = null }
    val all = videos ?: return
    val lastId = remember(tick) { prefs.getLong("last", -1) }
    val last = all.find { it.id == lastId }
    val shown = sortVids(all.filter { it.name.contains(query, true) && (folder == null || it.folder == folder) }, prefs, sort, asc)
    fun go(t: Int) { tab = t; folder = null; query = ""; searching = false }
    fun play(list: List<Vid>, v: Vid) { queue = list; qIdx = list.indexOf(v).coerceAtLeast(0) }
    fun setView(l: Int, s: Int, a: Boolean) {
        layout = l; sort = s; asc = a
        cfg.edit().putInt("layout", l).putInt("sort", s).putBoolean("asc", a).apply()
    }

    if (showView) ViewDialog(tab, layout, sort, asc, { l, s, a -> setView(l, s, a) }, { go(it); showView = false }) { showView = false }

    Scaffold(
        floatingActionButton = {
            if (last != null) FloatingActionButton({ play(all, last) }, shape = CircleShape, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(32.dp), tint = Color.White)
            }
        },
        bottomBar = { BottomBar(tab) { go(it) } }
    ) { pad ->
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
                    if (canSearch) {
                        IconButton({ searching = true }) { Icon(Icons.Rounded.Search, null) }
                        IconButton({ showView = true }) { Icon(Icons.Rounded.GridView, null) }
                    }
                }
            }
            val bottomPad = PaddingValues(bottom = 88.dp)
            if (tab == 2) {
                SettingsTab(cfg, accent, light, setAccent, setLight)
            } else if (tab == 1 && folder == null) {
                LazyColumn(contentPadding = bottomPad) {
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
            } else if (layout == 1) {
                LazyVerticalGrid(GridCells.Fixed(2), contentPadding = bottomPad, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            } else {
                LazyColumn(contentPadding = bottomPad, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown) { v ->
                        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface)
                            .clickable { play(shown, v) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                Thumb(v, Modifier.size(120.dp, 72.dp).clip(MaterialTheme.shapes.small))
                                Text(fmt(v.dur), Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                                    color = Color.White, fontSize = 10.sp)
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(v.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                Text(mb(v.size), color = Color.Gray, fontSize = 12.sp)
                                val pr = prefs.getLong("p${v.id}", 0)
                                LinearProgressIndicator(progress = { if (v.dur > 0) (pr.toFloat() / v.dur).coerceIn(0f, 1f) else 0f },
                                    Modifier.fillMaxWidth().padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
    val items = listOf(Icons.Rounded.VideoLibrary to "Video", Icons.Rounded.Folder to "Folders", Icons.Rounded.Settings to "Settings")
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(52.dp)) {
            items.forEachIndexed { i, (ic, label) ->
                val c = if (tab == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.weight(1f).fillMaxHeight().clickable { onTab(i) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(ic, null, tint = c, modifier = Modifier.size(22.dp))
                    Text(label, color = c, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun Opt(icon: ImageVector, label: String, sel: Boolean, onClick: () -> Unit) {
    val c = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = c, modifier = Modifier.size(26.dp))
        Text(label, color = c, fontSize = 11.sp)
    }
}

@Composable
fun ViewDialog(tab: Int, layout: Int, sort: Int, asc: Boolean, onChange: (Int, Int, Boolean) -> Unit, goTab: (Int) -> Unit, onDismiss: () -> Unit) {
    val sorts = listOf(Icons.Rounded.SortByAlpha to "Title", Icons.Rounded.CalendarMonth to "Date", Icons.Rounded.History to "Played",
        Icons.Rounded.Timer to "Length", Icons.Rounded.SdStorage to "Size")
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onDismiss) { Text("Done") } }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("View Mode", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Opt(Icons.Rounded.Description, "Files", tab == 0) { goTab(0) }
                Opt(Icons.Rounded.Folder, "Folders", tab == 1) { goTab(1) }
            }
            Text("Layout", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Opt(Icons.Rounded.ViewList, "List", layout == 0) { onChange(0, sort, asc) }
                Opt(Icons.Rounded.GridView, "Grid", layout == 1) { onChange(1, sort, asc) }
            }
            Text("Sort", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                sorts.forEachIndexed { i, (ic, l) -> Opt(ic, l, sort == i) { onChange(layout, i, asc) } }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(asc, { onChange(layout, sort, true) }, { Text(if (sort == 1) "↑ Oldest" else "↑ Ascending") })
                FilterChip(!asc, { onChange(layout, sort, false) }, { Text(if (sort == 1) "↓ Newest" else "↓ Descending") })
            }
        }
    })
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
    Column(Modifier.padding(top = 4.dp).verticalScroll(rememberScrollState())) {
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
fun Thumb(v: Vid, m: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, v.id) {
        value = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.loadThumbnail(v.uri, Size(400, 240), null) }.getOrNull() }
    }
    bmp?.let { Image(it.asImageBitmap(), null, m, contentScale = ContentScale.Crop) } ?: Box(m.background(Color.DarkGray))
}
