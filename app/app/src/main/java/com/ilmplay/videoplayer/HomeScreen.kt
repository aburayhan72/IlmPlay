package com.fajrplayer.videoplayer

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val folderColors = listOf(Color(0xFFFF6A1A), Color(0xFFFFC21A), Color(0xFF2F80FF), Color(0xFF22C55E), Color(0xFFB65CF0))

@Composable
fun ColorFolder(c: Color, m: Modifier) {
    Canvas(m) {
        val w = size.width; val h = size.height
        drawRoundRect(c.copy(alpha = 0.85f), Offset(0f, 0f), Size(w * 0.45f, h * 0.4f), CornerRadius(6f))
        drawRoundRect(c, Offset(0f, h * 0.18f), Size(w, h * 0.82f), CornerRadius(8f))
        drawRoundRect(Color(0x33FFFFFF), Offset(0f, h * 0.38f), Size(w, h * 0.62f), CornerRadius(8f))
    }
}

@Composable
fun HomeCard(m: Modifier, icon: ImageVector, tint: Color, circle: Boolean, title: String, count: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(m.clip(shape).background(MaterialTheme.colorScheme.surface).border(1.dp, Color(0x14FFFFFF), shape)
        .clickable { onClick() }.padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (circle) Box(Modifier.size(44.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
            } else Icon(icon, null, tint = tint, modifier = Modifier.size(44.dp))
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, null, tint = Color.Gray, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("$count Files", color = Color.Gray, fontSize = 14.sp)
    }
}

@Composable
fun HomeScreen(
    recent: Int, fav: Int, audio: Int, video: Int, folders: List<Pair<String, Int>>,
    onSearch: () -> Unit, onView: () -> Unit, onRecent: () -> Unit, onFav: () -> Unit, onAudio: () -> Unit,
    onVideo: () -> Unit, onAllFolders: () -> Unit, onFolder: (String) -> Unit
) {
    val p = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(18.dp)
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(p, Color(0xFFB65CF0)))),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Row {
                        Text("Fajr", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text("Player", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = p)
                    }
                    Text("Your Videos, Your Moments.", color = Color.Gray, fontSize = 12.sp)
                }
                IconButton(onSearch) { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(28.dp)) }
                IconButton(onView) { Icon(Icons.Rounded.Apps, null, modifier = Modifier.size(28.dp)) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeCard(Modifier.weight(1f), Icons.Rounded.AccessTime, Color(0xFFEC2F6B), true, "Recent", recent, onRecent)
                HomeCard(Modifier.weight(1f), Icons.Rounded.Favorite, Color(0xFFFFB020), false, "Favorite", fav, onFav)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeCard(Modifier.weight(1f), Icons.Rounded.MusicNote, Color(0xFF2F80FF), false, "Audio", audio, onAudio)
                HomeCard(Modifier.weight(1f), Icons.Rounded.Movie, Color(0xFF22C55E), false, "Video", video, onVideo)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(24.dp).clip(RoundedCornerShape(2.dp)).background(p))
                Text("Folders", Modifier.padding(start = 12.dp).weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.clickable { onAllFolders() }, verticalAlignment = Alignment.CenterVertically) {
                    Text("View All", color = p, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Rounded.ChevronRight, null, tint = p)
                }
            }
        }
        folders.take(5).forEachIndexed { i, (name, n) ->
            item {
                Row(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface).border(1.dp, Color(0x14FFFFFF), shape)
                    .clickable { onFolder(name) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ColorFolder(folderColors[i % folderColors.size], Modifier.size(44.dp, 36.dp))
                    Column(Modifier.weight(1f).padding(start = 18.dp)) {
                        Text(name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$n Files", color = Color.Gray, fontSize = 14.sp)
                    }
                    Icon(Icons.Rounded.MoreVert, null, tint = Color.Gray)
                }
            }
        }
    }
}
