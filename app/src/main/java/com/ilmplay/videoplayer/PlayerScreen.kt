package com.ilmplay.videoplayer

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
fun QChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(label, color = Color.White, fontSize = 10.sp)
    }
}

@Composable
fun SeekRow(pos: State<Long>, dur: Long, onSeek: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(fmt(pos.value), color = Color.White, fontSize = 12.sp)
        Slider((pos.value.toFloat() / dur).coerceIn(0f, 1f), { onSeek((it * dur).toLong()) }, Modifier.weight(1f).padding(horizontal = 8.dp))
        Text(fmt(dur), color = Color.White, fontSize = 12.sp)
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
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
            setMediaItem(MediaItem.fromUri(list[startIdx].uri)); prepare()
            seekTo(prefs.getLong("p${list[startIdx].id}", 0)); playWhenReady = true
            setPlaybackSpeed(cfg.getFloat("speed", 1f))
        }
    }
    var si by remember { mutableStateOf(speeds.indexOf(cfg.getFloat("speed", 1f)).let { if (it < 0) 2 else it }) }
    var showCtl by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var isPlay by remember { mutableStateOf(true) }
    val posS = remember { mutableStateOf(0L) }
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
    var menu by remember { mutableStateOf(false) }
    var cc by remember { mutableStateOf(true) }
    var fav by remember(idx) { mutableStateOf(prefs.getBoolean("f${v.id}", false)) }
    val dur = v.dur.coerceAtLeast(1L)

    fun save() {
        val vv = list[cur.value]
        prefs.edit().putLong("p${vv.id}", player.currentPosition).putLong("last", vv.id)
            .putLong("t${vv.id}", System.currentTimeMillis()).apply()
    }
    fun open(i: Int) {
        if (i !in list.indices) return
        save(); idx = i; pa = -1; pb = -1
        val nv = list[i]
        player.setMediaItem(MediaItem.fromUri(nv.uri)); player.prepare()
        player.seekTo(prefs.getLong("p${nv.id}", 0)); player.play()
    }
    fun abToggle() {
        val p = player.currentPosition
        if (pa < 0) pa = p else if (pb < 0) { if (p > pa) pb = p } else { pa = -1; pb = -1 }
    }
    fun rotate() {
        land = !land
        act.requestedOrientation = if (land) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
    LaunchedEffect(Unit) {
        while (true) {
            val p = player.currentPosition
            isPlay = player.isPlaying
            if (showCtl || pb > 0) posS.value = p
            if (pb > 0 && pa >= 0 && p >= pb) player.seekTo(pa)
            delay(if (showCtl || pb > 0) 250 else 500)
        }
    }
    LaunchedEffect(showCtl) { if (showCtl) posS.value = player.currentPosition }
    LaunchedEffect(showCtl, isPlay, menu) { if (showCtl && isPlay && !menu) { delay(3500); showCtl = false } }
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
            update = {
                it.resizeMode = when (resize) { 1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM; 2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL; else -> AspectRatioFrameLayout.RESIZE_MODE_FIT }
                it.subtitleView?.visibility = if (cc) View.VISIBLE else View.GONE
            })

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
                detectVerticalDragGestures(
                    onDragStart = { o -> left = o.x < size.width / 2; volF = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() },
                    onDragEnd = { volP = -1; briP = -1 }, onDragCancel = { volP = -1; briP = -1 },
                    onVerticalDrag = { _, dy ->
                        if (!locked && gest && !zooming) {
                            val d = -dy / size.height
                            if (left) {
                                volF = (volF + d * maxVol * 1.5f).coerceIn(0f, maxVol.toFloat())
                                am.setStreamVolume(AudioManager.STREAM_MUSIC, volF.roundToInt(), 0)
                                volP = (volF / maxVol * 100).toInt()
                            } else {
                                val lp = act.window.attributes
                                val c = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
                                lp.screenBrightness = (c + d).coerceIn(0.02f, 1f)
                                act.window.attributes = lp
                                briP = (lp.screenBrightness * 100).toInt()
                            }
                        }
                    })
            }
            .pointerInput(locked) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        if (!locked && ev.changes.size >= 2) {
                            zooming = true
                            scale = (scale * ev.calculateZoom()).coerceIn(1f, 4f)
                            offset = if (scale <= 1.01f) Offset.Zero else offset + ev.calculatePan()
                            ev.changes.forEach { it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    zooming = false
                }
            })

        if (volP >= 0) SideBar(Modifier.align(Alignment.CenterStart).padding(start = 36.dp), volP, Icons.Rounded.VolumeUp)
        if (briP >= 0) SideBar(Modifier.align(Alignment.CenterEnd).padding(end = 36.dp), briP, Icons.Rounded.BrightnessMedium)
        if (hud.isNotEmpty()) Text(hud, Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(12.dp), color = Color.White, fontSize = 20.sp)

        AnimatedVisibility(showCtl && locked, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                IconButton({ locked = false }, Modifier.align(Alignment.BottomStart).padding(16.dp)) { Icon(Icons.Rounded.Lock, null, tint = Color.White) }
            }
        }

        AnimatedVisibility(showCtl && !locked, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).statusBarsPadding()) {
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClose) { Icon(Icons.Rounded.ArrowBack, null, tint = Color.White) }
                        Text(v.name, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        IconButton({ fav = !fav; prefs.edit().putBoolean("f${v.id}", fav).apply() }) {
                            Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = Color.White)
                        }
                        IconButton({ cc = !cc }) {
                            Icon(Icons.Rounded.ClosedCaption, null, tint = if (cc) Color.White else Color(0x80FFFFFF))
                        }
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, null, tint = Color.White) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Aspect ratio") }, { resize = (resize + 1) % 3; menu = false })
                                DropdownMenuItem({ Text(if (pa < 0) "A-B repeat" else if (pb < 0) "Set point B" else "Clear A-B") }, { abToggle(); menu = false })
                            }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 8.dp)) {
                        QChip(Icons.Rounded.ScreenRotation, "Rotation") { rotate() }
                        QChip(Icons.Rounded.Equalizer, "Equalizer") {
                            runCatching {
                                ctx.startActivity(Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MOVIE))
                            }
                        }
                        QChip(Icons.Rounded.Speed, "${speeds[si]}x") { si = (si + 1) % speeds.size; player.setPlaybackSpeed(speeds[si]) }
                        QChip(Icons.Rounded.AspectRatio, "Ratio") { resize = (resize + 1) % 3 }
                        QChip(Icons.Rounded.Repeat, if (pa < 0) "A-B" else if (pb < 0) "Set B" else "Clear") { abToggle() }
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).navigationBarsPadding().padding(top = 16.dp, bottom = 4.dp)) {
                    SeekRow(posS, dur) { posS.value = it; player.seekTo(it) }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                        IconButton({ locked = true }, Modifier.align(Alignment.CenterStart).size(44.dp)) { Icon(Icons.Rounded.LockOpen, null, tint = Color.White) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ open(idx - 1) }, Modifier.size(40.dp), enabled = idx > 0) { Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
                            IconButton({ player.seekTo((player.currentPosition - seekMs).coerceAtLeast(0)) }, Modifier.size(40.dp)) { Icon(Icons.Rounded.Replay10, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
                            Box(Modifier.padding(horizontal = 6.dp).size(60.dp).border(2.dp, Color.White, CircleShape).clip(CircleShape)
                                .clickable { if (player.isPlaying) player.pause() else player.play() }, contentAlignment = Alignment.Center) {
                                Icon(if (isPlay) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(36.dp))
                            }
                            IconButton({ player.seekTo(player.currentPosition + seekMs) }, Modifier.size(40.dp)) { Icon(Icons.Rounded.Forward10, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
                            IconButton({ open(idx + 1) }, Modifier.size(40.dp), enabled = idx < list.lastIndex) { Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
                        }
                        IconButton({ rotate() }, Modifier.align(Alignment.CenterEnd).size(44.dp)) {
                            Icon(if (land) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, null, tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}
