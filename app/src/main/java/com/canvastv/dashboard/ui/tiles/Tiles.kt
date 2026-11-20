package com.canvastv.dashboard.ui.tiles

import android.app.ActivityManager
import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvView
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.TrafficStats
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.canvastv.dashboard.data.Apps
import com.canvastv.dashboard.data.NowPlaying
import com.canvastv.dashboard.data.NowPlayingState
import com.canvastv.dashboard.model.Tile
import com.canvastv.dashboard.model.TileType
import com.canvastv.dashboard.ui.LocalPalette
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Draws the inside of a tile. The focusable frame around it lives in DashboardScreen. */
@Composable
fun TileContent(tile: Tile, cellHeightDp: Float, liveVideo: Boolean, hiddenApps: List<String> = emptyList()) {
    when (tile.type) {
        TileType.CLOCK -> ClockTile(tile, cellHeightDp)
        TileType.APP -> AppTile(tile)
        TileType.HDMI -> HdmiTile(tile, liveVideo)
        TileType.STATS -> StatsTile(tile)
        TileType.NOTE -> NoteTile(tile, cellHeightDp)
        TileType.VIDEO -> VideoTile(tile, liveVideo)
        TileType.NOW_PLAYING -> NowPlayingTile(tile, cellHeightDp)
        TileType.COUNTDOWN -> CountdownTile(tile, cellHeightDp)
        TileType.FOLDER -> FolderTile(tile)
        TileType.WEATHER -> WeatherTile(tile, cellHeightDp)
        TileType.NEWS -> NewsTile(tile)
        TileType.TICKER -> TickerTile(tile)
        TileType.CALENDAR -> CalendarTile(tile)
        TileType.PHOTOS -> PhotosTile(tile, liveVideo)
        TileType.WEB -> WebTile(tile, liveVideo)
        TileType.SPEEDTEST -> {
            val ctx = LocalContext.current
            remember { com.canvastv.dashboard.data.SpeedTest.load(ctx) }
            SpeedTestTile(tile)
        }
        TileType.APP_DRAWER -> {
            val ctx = LocalContext.current
            val apps = remember(hiddenApps) { Apps.list(ctx).filter { it.pkg !in hiddenApps } }
            AppDrawerTile(tile, apps.size, apps.filter { it.isTvApp }.map { it.pkg })
        }
    }
}

// ---------------------------------------------------------------- clock

@Composable
private fun ClockTile(tile: Tile, cellHeightDp: Float) {
    val p = LocalPalette.current
    val zone = tile.cfg("timezone").takeIf { it.isNotBlank() }?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault()
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(1000 - (value % 1000)) }
    }
    val use24 = tile.cfgBool("24h", android.text.format.DateFormat.is24HourFormat(LocalContext.current))
    val showDate = tile.cfgBool("showDate", true)
    val analog = tile.cfg("style") == "analog"
    val city = tile.cfg("city")
    Column(
        Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (analog) {
            AnalogClock(now, zone, Modifier.weight(1f).fillMaxWidth())
        } else {
            val fmt = SimpleDateFormat(if (use24) "HH:mm" else "h:mm", Locale.getDefault()).apply { timeZone = zone }
            val big = (cellHeightDp * tile.h * 0.42f).coerceIn(28f, 140f)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmt.format(Date(now)), fontSize = big.sp, fontWeight = FontWeight.Light, color = p.onSurface, maxLines = 1)
                if (!use24) {
                    val ampm = SimpleDateFormat("a", Locale.getDefault()).apply { timeZone = zone }
                    Text(" " + ampm.format(Date(now)), fontSize = (big * 0.3f).sp, color = p.muted, modifier = Modifier.padding(bottom = (big * 0.18f).dp))
                }
            }
        }
        if (showDate) {
            val df = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).apply { timeZone = zone }
            Text(df.format(Date(now)), fontSize = 16.sp, color = p.muted, maxLines = 1)
        }
        if (city.isNotBlank()) Text(city, fontSize = 14.sp, color = p.accentText, maxLines = 1)
    }
}

@Composable
private fun AnalogClock(now: Long, zone: TimeZone, modifier: Modifier) {
    val p = LocalPalette.current
    val cal = Calendar.getInstance(zone).apply { timeInMillis = now }
    val h = cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60f
    val m = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
    val s = cal.get(Calendar.SECOND).toFloat()
    Canvas(modifier) {
        val r = min(size.width, size.height) / 2f * 0.92f
        val c = center
        drawCircle(p.onSurface.copy(alpha = 0.12f), r, c)
        for (i in 0 until 12) {
            val a = i / 12f * 2 * PI.toFloat() - PI.toFloat() / 2
            drawLine(p.muted, c + Offset(cos(a), sin(a)) * (r * 0.86f), c + Offset(cos(a), sin(a)) * r, strokeWidth = 3f)
        }
        fun hand(frac: Float, len: Float, w: Float, col: Color) {
            val a = frac * 2 * PI.toFloat() - PI.toFloat() / 2
            drawLine(col, c, c + Offset(cos(a), sin(a)) * (r * len), strokeWidth = w, cap = StrokeCap.Round)
        }
        hand(h / 12f, 0.5f, 9f, p.onSurface)
        hand(m / 60f, 0.75f, 6f, p.onSurface)
        hand(s / 60f, 0.82f, 2.5f, p.accentText)
        drawCircle(p.accent, 6f, c)
    }
}

// ---------------------------------------------------------------- app

@Composable
private fun AppTile(tile: Tile) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val pkg = tile.cfg("pkg")
    val label = remember(pkg, tile.cfg("label")) { tile.cfg("label").ifBlank { Apps.label(ctx, pkg) } }
    val art = remember(pkg) { Apps.art(ctx, pkg) }
    val showLabel = tile.cfgBool("showLabel", art?.second != true)
    val textOnly = tile.cfg("display") == "text"
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            textOnly || art == null -> Text(
                label, fontSize = 20.sp, color = p.onSurface, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp),
            )
            art.second -> Image(art.first, label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> Row(
                Modifier.fillMaxSize().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Image(art.first, label, Modifier.fillMaxHeight(0.8f).padding(end = 10.dp))
                Text(label, fontSize = 17.sp, color = p.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (showLabel && art?.second == true && !textOnly) {
            Text(
                label, fontSize = 13.sp, color = Color.White, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- HDMI

@Composable
private fun HdmiTile(tile: Tile, live: Boolean) {
    val p = LocalPalette.current
    val label = tile.cfg("label", "HDMI")
    val preview = tile.cfgBool("preview", true) && live
    var status by remember { mutableStateOf(if (preview) "Connecting…" else "") }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (preview) HdmiView(tile.cfg("inputId")) { status = it }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x88000000)).padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(label, fontSize = 15.sp, color = Color.White, maxLines = 1)
            val hint = when {
                !preview -> "Press OK to open"
                status.isNotBlank() -> "$status · OK for full screen"
                else -> "OK for full screen"
            }
            Text(hint, fontSize = 11.sp, color = Color(0xFFCCCCCC), maxLines = 1)
        }
        if (!preview) Text("▶", fontSize = 40.sp, color = p.accent, modifier = Modifier.align(Alignment.Center))
    }
}

/** Live picture from an HDMI input, using Android's TvView (confirmed working on the U75). */
@Composable
fun HdmiView(inputId: String, modifier: Modifier = Modifier.fillMaxSize(), onStatus: (String) -> Unit = {}) {
    if (inputId.isBlank()) { onStatus("No input chosen"); return }
    AndroidView(
        factory = { c ->
            TvView(c).apply {
                // Keep remote keys with the dashboard; the console uses its own controller.
                isFocusable = false
                isFocusableInTouchMode = false
                setCallback(object : TvView.TvInputCallback() {
                    override fun onConnectionFailed(id: String) = onStatus("Couldn't connect")
                    override fun onDisconnected(id: String) = onStatus("Disconnected")
                    override fun onVideoAvailable(id: String) = onStatus("")
                    override fun onVideoUnavailable(id: String, reason: Int) =
                        onStatus(if (reason == 2 /* VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL */) "No signal" else "Waiting for picture")
                })
                runCatching { tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId)) }
                    .onFailure { Log.w("CanvasTV", "tune failed", it); onStatus("Couldn't open input") }
            }
        },
        onRelease = { runCatching { it.reset() } },
        modifier = modifier,
    )
}

// ---------------------------------------------------------------- stats

private data class Stats(
    val ramUsedPct: Int, val ramTotalGb: Float,
    val storageFreeGb: Float, val storageTotalGb: Float,
    val downKbps: Long, val upKbps: Long,
    val ip: String, val uptime: String,
)

@Composable
private fun StatsTile(tile: Tile) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val stats by produceState<Stats?>(null) {
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastTx = TrafficStats.getTotalTxBytes()
        var lastT = SystemClock.elapsedRealtime()
        while (true) {
            delay(2000)
            val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes(); val t = SystemClock.elapsedRealtime()
            val secs = ((t - lastT) / 1000f).coerceAtLeast(0.5f)
            value = readStats(ctx, ((rx - lastRx) * 8 / 1000 / secs).toLong(), ((tx - lastTx) * 8 / 1000 / secs).toLong())
            lastRx = rx; lastTx = tx; lastT = t
        }
    }
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("System", fontSize = 15.sp, color = p.accentText)
        val s = stats
        if (s == null) { Text("Measuring…", fontSize = 14.sp, color = p.muted); return@Column }
        StatRow("Memory", "${s.ramUsedPct}% of ${"%.1f".format(s.ramTotalGb)} GB", s.ramUsedPct / 100f)
        val usedFrac = if (s.storageTotalGb > 0) 1 - s.storageFreeGb / s.storageTotalGb else 0f
        StatRow("Storage", "${"%.1f".format(s.storageFreeGb)} GB free", usedFrac)
        Text("Network  ↓ ${fmtRate(s.downKbps)}  ↑ ${fmtRate(s.upKbps)}", fontSize = 13.sp, color = p.onSurface, maxLines = 1)
        if (tile.h >= 2) {
            Text("IP ${s.ip}", fontSize = 12.sp, color = p.muted, maxLines = 1)
            Text("Up ${s.uptime}", fontSize = 12.sp, color = p.muted, maxLines = 1)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, frac: Float) {
    val p = LocalPalette.current
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, fontSize = 13.sp, color = p.onSurface)
            Spacer(Modifier.weight(1f))
            Text(value, fontSize = 13.sp, color = p.muted, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(p.onSurface.copy(alpha = 0.15f))) {
            Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().background(p.accentText))
        }
    }
}

private fun fmtRate(kbps: Long) = if (kbps >= 1000) "%.1f Mb/s".format(kbps / 1000f) else "$kbps kb/s"

private fun readStats(ctx: Context, down: Long, up: Long): Stats {
    val am = ctx.getSystemService(ActivityManager::class.java)
    val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val fs = StatFs(Environment.getDataDirectory().path)
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val lp: LinkProperties? = cm.getLinkProperties(cm.activeNetwork)
    val ip = lp?.linkAddresses?.map { it.address.hostAddress ?: "" }?.firstOrNull { it.contains('.') } ?: "offline"
    val up_ = SystemClock.elapsedRealtime() / 1000
    val uptime = if (up_ >= 86400) "${up_ / 86400}d ${up_ % 86400 / 3600}h" else "${up_ / 3600}h ${up_ % 3600 / 60}m"
    return Stats(
        ramUsedPct = (100 - mi.availMem * 100 / mi.totalMem).toInt(),
        ramTotalGb = mi.totalMem / 1e9f,
        storageFreeGb = fs.availableBytes / 1e9f,
        storageTotalGb = fs.totalBytes / 1e9f,
        downKbps = down.coerceAtLeast(0), upKbps = up.coerceAtLeast(0),
        ip = ip, uptime = uptime,
    )
}

// ---------------------------------------------------------------- note

@Composable
private fun NoteTile(tile: Tile, cellHeightDp: Float) {
    val p = LocalPalette.current
    val text = tile.cfg("text", "Edit this note in Configure")
    val size = when (tile.cfg("size")) { "S" -> 16f; "L" -> 30f; "XL" -> 44f; else -> 22f }
    Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.CenterStart) {
        Text(
            text, fontSize = size.coerceAtMost(cellHeightDp * tile.h * 0.6f).sp, color = p.onSurface,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------- video

@Composable
private fun VideoTile(tile: Tile, live: Boolean) {
    val p = LocalPalette.current
    val url = tile.cfg("url")
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (url.isBlank()) {
            Text("Set a stream URL in Configure\n(IPTV, Plex/Jellyfin, RTSP camera, MP4)", fontSize = 14.sp, color = p.muted, textAlign = TextAlign.Center)
        } else if (live) {
            StreamPlayer(url, muted = tile.cfgBool("muted", true))
        }
        val title = tile.cfg("title")
        if (title.isNotBlank()) {
            Text(title, fontSize = 13.sp, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).background(Color(0x88000000)).padding(6.dp))
        }
    }
}

/**
 * Shares one player per stream address. Opening a video tile full screen hands the same, already
 * playing player to the full-screen view instead of starting (and downloading) the stream again.
 */
object PlayerPool {
    private class Entry(val player: ExoPlayer, var refs: Int, var error: androidx.compose.runtime.MutableState<String?>)
    private val entries = HashMap<String, Entry>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun acquire(ctx: Context, url: String): Pair<ExoPlayer, androidx.compose.runtime.State<String?>> {
        main.removeCallbacksAndMessages(url)
        val e = entries.getOrPut(url) {
            val err = mutableStateOf<String?>(null)
            val p = ExoPlayer.Builder(ctx.applicationContext).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                repeatMode = Player.REPEAT_MODE_ALL
                addListener(object : Player.Listener {
                    override fun onPlayerError(e: androidx.media3.common.PlaybackException) { err.value = e.errorCodeName }
                })
                prepare()
                play()
            }
            Entry(p, 0, err)
        }
        e.refs++
        return e.player to e.error
    }

    /** Releases a little later, so a hand-over between tile and full screen keeps playing. */
    fun release(url: String) {
        val e = entries[url] ?: return
        e.refs--
        if (e.refs > 0) return
        main.postAtTime({
            if (e.refs <= 0 && entries[url] === e) { entries.remove(url); e.player.release() }
        }, url, android.os.SystemClock.uptimeMillis() + 1500)
    }
}

/** A looping Media3 player for any address Media3 understands (HLS, RTSP, MP4, …). */
@Composable
fun StreamPlayer(url: String, muted: Boolean, modifier: Modifier = Modifier.fillMaxSize(), fill: Boolean = true) {
    val ctx = LocalContext.current
    val (player, error) = remember(url) { PlayerPool.acquire(ctx, url) }
    DisposableEffect(url) { onDispose { PlayerPool.release(url) } }
    androidx.compose.runtime.SideEffect { player.volume = if (muted) 0f else 1f }
    Box(modifier) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    isFocusable = false
                    this.player = player
                }
            },
            // Tiles fill their box; full screen shows the whole picture.
            update = { it.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        error.value?.let {
            Text("Stream error: $it", fontSize = 12.sp, color = Color(0xFFFF8A80), modifier = Modifier.align(Alignment.Center).background(Color(0xAA000000)).padding(8.dp))
        }
    }
}

// ---------------------------------------------------------------- now playing

@Composable
private fun NowPlayingTile(tile: Tile, cellHeightDp: Float) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    DisposableEffect(Unit) {
        NowPlaying.acquire(ctx)
        onDispose { NowPlaying.release() }
    }
    when (val st = NowPlaying.state) {
        NowPlayingState.NeedsAccess -> Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) {
            Text("Now playing", fontSize = 15.sp, color = p.accentText)
            Text("Needs a one-time permission. See Settings → About.", fontSize = 13.sp, color = p.muted)
        }
        NowPlayingState.Nothing -> Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) {
            Text("Now playing", fontSize = 15.sp, color = p.accentText)
            Text("Nothing is playing", fontSize = 18.sp, color = p.muted)
        }
        is NowPlayingState.Playing -> {
            val info = st.info
            val appName = remember(info.pkg) { Apps.label(ctx, info.pkg) }
            val pos by produceState(info.positionNow(), info) {
                while (true) { value = info.positionNow(); delay(1000) }
            }
            Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val artSize = (cellHeightDp * tile.h - 40f).coerceIn(40f, 220f)
                Box(Modifier.size(artSize.dp).clip(RoundedCornerShape(10.dp)).background(p.onSurface.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                    val bmp = remember(info.art) { info.art?.asImageBitmap() }
                    if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Text("♪", fontSize = (artSize * 0.4f).sp, color = p.muted)
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text((if (info.playing) "▶  " else "❚❚  ") + appName, fontSize = 13.sp, color = p.accentText, maxLines = 1)
                    Text(info.title.ifBlank { "Unknown title" }, fontSize = 19.sp, color = p.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (info.artist.isNotBlank()) Text(info.artist, fontSize = 14.sp, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (info.durationMs > 0) {
                        Spacer(Modifier.size(4.dp))
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.onSurface.copy(alpha = 0.15f))) {
                            Box(Modifier.fillMaxWidth((pos.toFloat() / info.durationMs).coerceIn(0f, 1f)).fillMaxHeight().background(p.accentText))
                        }
                        Text("${fmtTime(pos)} / ${fmtTime(info.durationMs)}", fontSize = 11.sp, color = p.muted)
                    }
                }
            }
        }
    }
}

private fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Suppress("unused")
private val previewSize = Modifier.size(1.dp)
