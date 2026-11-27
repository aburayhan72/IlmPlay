package com.fajrplayer.videoplayer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.MediaStore
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

data class Vid(val id: Long, val uri: Uri, val name: String, val dur: Long, val size: Long, val folder: String)

val themes = listOf(Color(0xFF8B3DFF), Color(0xFF1ED28A), Color(0xFF4A86E8), Color(0xFFF4B400), Color(0xFFEA4C4C), Color(0xFFFF8A3D))

fun loadMedia(c: Context, audio: Boolean): List<Vid> {
    val out = mutableListOf<Vid>()
    val base = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val p = arrayOf(BaseColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DURATION,
        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
    val sel = if (audio) "${MediaStore.Audio.Media.IS_MUSIC} != 0" else null
    runCatching {
        c.contentResolver.query(base, p, sel, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { q ->
            while (q.moveToNext()) {
                val id = q.getLong(0)
                out += Vid(id, ContentUris.withAppendedId(base, id), q.getString(1) ?: "media", q.getLong(2), q.getLong(3), q.getString(4) ?: "Other")
            }
        }
    }
    return out
}

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
    val a = themes[accent.coerceIn(0, themes.lastIndex)]
    val scheme = if (light) lightColorScheme(primary = a, background = Color(0xFFF4F4F4), surface = Color.White)
    else darkColorScheme(primary = a, background = Color(0xFF0B0B12), surface = Color(0xFF15151F))
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
    val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    var videos by remember { mutableStateOf<List<Vid>?>(null) }
    var audios by remember { mutableStateOf<List<Vid>>(emptyList()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        videos = loadMedia(ctx, false); audios = loadMedia(ctx, true)
    }
    LaunchedEffect(Unit) { launcher.launch(perms) }

    var tab by remember { mutableStateOf(0) }
    var src by remember { mutableStateOf(0) }
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

    fun goTab(t: Int) { tab = t; src = if (t == 2) 3 else 0; folder = null; query = ""; searching = false }
    fun goSrc(s: Int, f: String? = null) { tab = 1; src = s; folder = f; query = ""; searching = false }
    fun play(list: List<Vid>, v: Vid) { queue = list; qIdx = list.indexOf(v).coerceAtLeast(0) }
    fun setView(l: Int, s: Int, a: Boolean) {
        layout = l; sort = s; asc = a
        cfg.edit().putInt("layout", l).putInt("sort", s).putBoolean("asc", a).apply()
    }

    if (qIdx >= 0) {
        BackHandler { qIdx = -1; tick++ }
        PlayerScreen(queue, qIdx, prefs, cfg) { qIdx = -1; tick++ }
        return
    }
    BackHandler(tab != 0) { if (folder != null) folder = null else goTab(0) }
    val all = videos ?: return
    val lastId = remember(tick) { prefs.getLong("last", -1) }
    val last = all.find { it.id == lastId }
    val recentList = remember(all, tick) { all.filter { prefs.getLong("t${it.id}", 0) > 0 }.sortedByDescending { prefs.getLong("t${it.id}", 0) } }
    val favList = remember(all, tick) { all.filter { prefs.getBoolean("f${it.id}", false) } }
    val folders = remember(all) { all.groupBy { it.folder }.map { it.key to it.value.size }.sortedByDescending { it.second } }
    val baseList = when (src) { 1 -> recentList; 2 -> audios; 3 -> favList; else -> all }
    val shown = remember(baseList, query, folder, sort, asc, src) {
        val f = baseList.filter { it.name.contains(query, true) && (src != 0 || folder == null || it.folder == folder) }
        if (src == 1) f else sortVids(f, prefs, sort, asc)
    }

    if (showView) ViewDialog(if (src == 4) 1 else 0, layout, sort, asc, { l, s, a -> setView(l, s, a) },
        { goSrc(if (it == 1) 4 else 0); showView = false }) { showView = false }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (last != null && tab != 3) FloatingActionButton({ play(all, last) }, shape = CircleShape, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(32.dp), tint = Color.White)
            }
        },
        bottomBar = { BottomBar(tab) { goTab(it) } }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (tab == 0) {
                HomeScreen(recentList.size, favList.size, audios.size, all.size, folders,
                    { goSrc(0); searching = true }, { showView = true }, { goSrc(1) }, { goTab(2) }, { goSrc(2) },
                    { goSrc(0) }, { goSrc(4) }, { goSrc(0, it) })
            } else Column(Modifier.padding(horizontal = 12.dp)) {
                val title = when {
                    tab == 3 -> "Settings"; tab == 2 -> "Favorites"
                    src == 1 -> "Recent"; src == 2 -> "Audio"; src == 4 -> "Folders"
                    else -> folder ?: "All videos"
                }
                val canSearch = tab in 1..2 && src != 4
                val showFolders = tab == 1 && src == 4
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
                if (shown.isEmpty() && !showFolders && tab != 3) Text("No files", color = Color.Gray, modifier = Modifier.padding(16.dp))
                if (tab == 3) {
                    SettingsTab(cfg, accent, light, setAccent, setLight)
                } else if (showFolders) {
                    LazyColumn(contentPadding = bottomPad) {
                        items(folders) { (name, n) ->
                            Row(Modifier.fillMaxWidth().clickable { goSrc(0, name) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                FolderIcon(Modifier.size(64.dp, 52.dp), name == "Camera")
                                Column(Modifier.padding(start = 16.dp)) {
                                    Text(name, fontSize = 17.sp)
                                    Text(if (n == 1) "1 video" else "$n videos", color = Color.Gray, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                } else if (layout == 1) {
                    LazyVerticalGrid(GridCells.Fixed(2), contentPadding = bottomPad, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shown, key = { it.id }) { v ->
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
                        items(shown, key = { it.id }) { v ->
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
}
