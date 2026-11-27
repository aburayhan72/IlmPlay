package com.fajrplayer.videoplayer

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun fmt(ms: Long): String { val s = ms / 1000; return "%02d:%02d".format(s / 60, s % 60) }
fun mb(b: Long) = "%.2fMB".format(b / 1048576.0)

@Composable
fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
    val items = listOf(Icons.Rounded.Home to "Home", Icons.Rounded.PlayArrow to "Player",
        Icons.Rounded.Favorite to "Favorites", Icons.Rounded.Settings to "Settings")
    Surface(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp), shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, border = BorderStroke(1.dp, Color(0x14FFFFFF))) {
        Row(Modifier.fillMaxWidth().height(60.dp)) {
            items.forEachIndexed { i, (ic, label) ->
                val sel = tab == i
                val c = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.weight(1f).fillMaxHeight().clickable { onTab(i) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(34.dp).clip(CircleShape).then(if (sel) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier),
                        contentAlignment = Alignment.Center) {
                        Icon(ic, null, tint = if (sel) Color.White else c, modifier = Modifier.size(22.dp))
                    }
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

val thumbCache = LruCache<Long, Bitmap>(80)

@Composable
fun Thumb(v: Vid, m: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(thumbCache.get(v.id), v.id) {
        if (value == null) {
            val b = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.loadThumbnail(v.uri, Size(320, 192), null) }.getOrNull() }
            if (b != null) thumbCache.put(v.id, b)
            value = b
        }
    }
    bmp?.let { Image(it.asImageBitmap(), null, m, contentScale = ContentScale.Crop) } ?: Box(m.background(Color.DarkGray))
}
