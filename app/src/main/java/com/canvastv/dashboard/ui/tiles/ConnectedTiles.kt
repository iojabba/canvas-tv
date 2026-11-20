package com.canvastv.dashboard.ui.tiles

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.canvastv.dashboard.data.CalEvent
import com.canvastv.dashboard.data.CryptoIds
import com.canvastv.dashboard.data.Headline
import com.canvastv.dashboard.data.Quote
import com.canvastv.dashboard.data.SpeedTest
import com.canvastv.dashboard.data.httpGet
import com.canvastv.dashboard.data.parseCoinGecko
import com.canvastv.dashboard.data.parseFeed
import com.canvastv.dashboard.data.parseIcs
import com.canvastv.dashboard.data.parseStooq
import com.canvastv.dashboard.data.rememberRemoteData
import com.canvastv.dashboard.data.splitList
import com.canvastv.dashboard.model.Tile
import com.canvastv.dashboard.ui.LocalPalette
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun refreshMs(tile: Tile, defaultMin: String) = (tile.cfg("refreshMin", defaultMin).toLongOrNull() ?: defaultMin.toLong()).coerceAtLeast(1) * 60_000L

@Composable
private fun TileHeader(text: String) {
    Text(text, fontSize = 14.sp, color = LocalPalette.current.accentText, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun StatusLine(text: String) {
    Text(text, fontSize = 13.sp, color = LocalPalette.current.muted, maxLines = 2)
}

// ---------------------------------------------------------------- news

/** Feeds are fetched together; one failing feed doesn't hide the others. */
private suspend fun fetchFeeds(urls: List<String>): String = coroutineScope {
    val all = urls.map { u -> async { runCatching { parseFeed(httpGet(u), u) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
    if (all.isEmpty()) error("No headlines")
    // Store as simple tab-separated lines so the cache stays readable.
    all.sortedByDescending { it.time }.take(40).joinToString("\n") { listOf(it.title, it.source, it.link, it.time.toString(), it.image.orEmpty()).joinToString("\t") { f -> f.replace('\t', ' ').replace('\n', ' ') } }
}

private fun readHeadlines(text: String): List<Headline> = text.lines().mapNotNull { l ->
    val c = l.split('\t'); if (c.size < 5) null else Headline(c[0], c[1], c[2], c[3].toLongOrNull() ?: 0, c[4].ifBlank { null })
}

@Composable
fun NewsTile(tile: Tile) {
    val p = LocalPalette.current
    val urls = splitList(tile.cfg("urls"))
    if (urls.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) { TileHeader("News"); StatusLine("Add RSS or Atom feed addresses in Configure") }
        return
    }
    val data by rememberRemoteData("news:${urls.joinToString()}", refreshMs(tile, "15")) { fetchFeeds(urls) }
    val items = data?.text?.let(::readHeadlines).orEmpty()
    val title = tile.cfg("title").ifBlank { "News" }
    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) { TileHeader(title); StatusLine(data?.error?.let { "Couldn't load feeds ($it)" } ?: "Loading…") }
        return
    }
    if (tile.cfg("style") == "ticker" || tile.h == 1) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            TileHeader("$title  ")
            Text(items.joinToString("     •     ") { it.title }, fontSize = 17.sp, color = p.onSurface, maxLines = 1,
                modifier = Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE, velocity = 60.dp))
        }
        return
    }
    // Rotating list: shows as many headlines as fit and moves on every 10 seconds.
    val perPage = (tile.h * 2).coerceAtLeast(2)
    var page by remember { mutableIntStateOf(0) }
    LaunchedEffect(items.size) { while (true) { delay(10_000); page = (page + 1) % ((items.size + perPage - 1) / perPage).coerceAtLeast(1) } }
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TileHeader(title)
        Crossfade(page, animationSpec = tween(600), label = "news") { pg ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.drop(pg * perPage).take(perPage).forEach { h ->
                    Column {
                        Text(h.title, fontSize = 16.sp, color = p.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(h.source.takeIf { it.isNotBlank() }, ago(h.time)).joinToString(" · "), fontSize = 11.sp, color = p.muted, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun ago(t: Long): String? {
    if (t <= 0) return null
    val m = (System.currentTimeMillis() - t) / 60_000
    return when { m < 1 -> "just now"; m < 60 -> "${m}m ago"; m < 1440 -> "${m / 60}h ago"; else -> "${m / 1440}d ago" }
}

// ---------------------------------------------------------------- stocks & crypto

private val quotesJson = Json { ignoreUnknownKeys = true }

private suspend fun fetchQuotes(symbols: List<String>): String = coroutineScope {
    val crypto = symbols.filter { it in CryptoIds }
    val stocks = symbols.filter { it !in CryptoIds }
    val c = async {
        if (crypto.isEmpty()) emptyList() else runCatching {
            parseCoinGecko(httpGet("https://api.coingecko.com/api/v3/simple/price?ids=${crypto.joinToString(",") { CryptoIds[it]!! }}&vs_currencies=usd&include_24hr_change=true"), crypto)
        }.getOrDefault(emptyList())
    }
    val s = async {
        if (stocks.isEmpty()) emptyList() else runCatching {
            val q = stocks.joinToString(",") { if ('.' in it || it.startsWith("^")) it.lowercase() else "${it.lowercase()}.us" }
            parseStooq(httpGet("https://stooq.com/q/l/?s=${URLEncoder.encode(q, "UTF-8")}&f=sd2t2ohlcv&h&e=csv"))
        }.getOrDefault(emptyList())
    }
    val all = c.await() + s.await()
    if (all.isEmpty()) error("No prices")
    val order = symbols.withIndex().associate { it.value to it.index }
    all.sortedBy { order[it.symbol] ?: 99 }.joinToString("\n") { "${it.symbol}\t${it.price}\t${it.changePct ?: ""}" }
}

private fun readQuotes(text: String) = text.lines().mapNotNull { l ->
    val c = l.split('\t'); if (c.size < 3) null else Quote(c[0], c[1].toDoubleOrNull() ?: return@mapNotNull null, c[2].toDoubleOrNull())
}

private fun fmtPrice(v: Double) = if (v >= 1000) "%,.0f".format(v) else if (v >= 1) "%,.2f".format(v) else "%.4f".format(v)

@Composable
fun TickerTile(tile: Tile) {
    val p = LocalPalette.current
    val symbols = splitList(tile.cfg("symbols", "BTC, ETH, AAPL, MSFT")).map { it.uppercase() }
    val data by rememberRemoteData("quotes:${symbols.joinToString()}", refreshMs(tile, "10")) { fetchQuotes(symbols) }
    val quotes = data?.text?.let(::readQuotes).orEmpty()
    if (quotes.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) { TileHeader("Markets"); StatusLine(data?.error?.let { "Couldn't load prices ($it)" } ?: "Loading…") }
        return
    }
    @Composable
    fun QuoteText(q: Quote) {
        val up = (q.changePct ?: 0.0) >= 0
        val col = if (q.changePct == null) p.muted else if (up) Color(0xFF4CD07D) else Color(0xFFFF6B6B)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(q.symbol, fontSize = 15.sp, color = p.onSurface, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text(fmtPrice(q.price), fontSize = 15.sp, color = p.onSurface)
            q.changePct?.let { Spacer(Modifier.width(6.dp)); Text((if (up) "▲" else "▼") + "%.2f%%".format(kotlin.math.abs(it)), fontSize = 13.sp, color = col) }
        }
    }
    if (tile.h == 1) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp).basicMarquee(iterations = Int.MAX_VALUE, velocity = 45.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            quotes.forEach { QuoteText(it) }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TileHeader("Markets" + if (data?.stale == true) " · offline" else "")
            quotes.take(tile.h * 3).forEach { QuoteText(it) }
        }
    }
}

// ---------------------------------------------------------------- calendar

@Composable
fun CalendarTile(tile: Tile) {
    val p = LocalPalette.current
    val urls = splitList(tile.cfg("icsUrls"))
    if (urls.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) {
            TileHeader("Calendar"); StatusLine("Paste your calendar's private iCal address in Configure (Google Calendar: Settings → your calendar → Secret address in iCal format)")
        }
        return
    }
    val days = tile.cfg("days", "7").toIntOrNull() ?: 7
    val data by rememberRemoteData("ics:${urls.joinToString()}", refreshMs(tile, "30")) {
        coroutineScope { urls.map { u -> async { runCatching { httpGet(u) }.getOrDefault("") } }.awaitAll() }
            .filter { "BEGIN:VCALENDAR" in it }.ifEmpty { error("No calendar data") }.joinToString("\n")
    }
    // Re-expand every minute so "now" moves forward without refetching.
    val now by produceState(LocalDateTime.now()) { while (true) { delay(60_000); value = LocalDateTime.now() } }
    val events = remember(data?.text, now.toLocalDate(), now.hour) {
        data?.text?.let { runCatching { parseIcs(it, now.toLocalDate().atStartOfDay(), days) }.getOrNull() }.orEmpty().filter { it.end > now }
    }
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TileHeader(tile.cfg("title").ifBlank { "Coming up" } + if (data?.stale == true) " · offline" else "")
        if (data?.text == null) { StatusLine(data?.error?.let { "Couldn't load the calendar ($it)" } ?: "Loading…"); return@Column }
        if (events.isEmpty()) { StatusLine("Nothing in the next $days days"); return@Column }
        var lastDay: LocalDate? = null
        events.take(tile.h * 3).forEach { e ->
            val d = e.start.toLocalDate().coerceAtLeastDate(now.toLocalDate())
            if (d != lastDay) {
                lastDay = d
                Text(dayLabel(d, now.toLocalDate()), fontSize = 12.sp, color = p.muted, modifier = Modifier.padding(top = 4.dp))
            }
            EventRow(e)
        }
    }
}

private fun LocalDate.coerceAtLeastDate(min: LocalDate) = if (isBefore(min)) min else this

private fun dayLabel(d: LocalDate, today: LocalDate) = when (d) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    else -> d.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault()))
}

@Composable
private fun EventRow(e: CalEvent) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.Top) {
        Text(if (e.allDay) "All day" else e.start.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())),
            fontSize = 14.sp, color = p.accentText, modifier = Modifier.width(72.dp), maxLines = 1)
        Text(e.title + if (e.location.isNotBlank()) " · ${e.location}" else "", fontSize = 15.sp, color = p.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- photos

private val listJson = Json { ignoreUnknownKeys = true }

/** A photo source is a list of image addresses, or RSS/Atom feeds whose items carry images. */
private suspend fun fetchPhotoList(sources: List<String>): String = coroutineScope {
    sources.map { src ->
        async {
            val lower = src.lowercase().substringBefore('?')
            if (listOf(".jpg", ".jpeg", ".png", ".webp", ".gif").any { lower.endsWith(it) }) listOf(src)
            else runCatching { parseFeed(httpGet(src), src).mapNotNull { it.image } }.getOrDefault(emptyList())
        }
    }.awaitAll().flatten().distinct().ifEmpty { error("No photos found") }
        .let { listJson.encodeToString(ListSerializer(String.serializer()), it) }
}

@Composable
fun PhotosTile(tile: Tile, live: Boolean) {
    val sources = splitList(tile.cfg("sources"))
    if (sources.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) {
            TileHeader("Photos"); StatusLine("Add picture addresses or a photo feed (for example a Flickr or blog RSS feed) in Configure")
        }
        return
    }
    val data by rememberRemoteData("photos:${sources.joinToString()}", refreshMs(tile, "60")) { fetchPhotoList(sources) }
    val photos = remember(data?.text) { data?.text?.let { runCatching { listJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty() }
    if (photos.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) { TileHeader("Photos"); StatusLine(data?.error?.let { "Couldn't load photos ($it)" } ?: "Loading…") }
        return
    }
    val seconds = (tile.cfg("seconds", "15").toLongOrNull() ?: 15L).coerceAtLeast(3)
    val shuffle = tile.cfgBool("shuffle", true)
    val order = remember(photos, shuffle) { if (shuffle) photos.shuffled() else photos }
    var i by remember { mutableIntStateOf(0) }
    if (live) LaunchedEffect(order) { while (true) { delay(seconds * 1000); i = (i + 1) % order.size } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(order[i % order.size], animationSpec = tween(900), label = "photo") { url ->
            AsyncImage(model = url, contentDescription = null, contentScale = if (tile.cfg("fit") == "fit") ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

// ---------------------------------------------------------------- web page

/** A read-only web page. It doesn't take focus, so the remote keeps moving between tiles. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebTile(tile: Tile, live: Boolean) {
    val url = tile.cfg("url")
    if (url.isBlank() || !live) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) {
            TileHeader(tile.cfg("title").ifBlank { "Web page" })
            StatusLine(if (url.isBlank()) "Set a web address in Configure" else "Paused in lightweight mode")
        }
        return
    }
    val zoom = tile.cfg("zoom", "100").toIntOrNull() ?: 100
    val reloadMs = refreshMs(tile, "15")
    var view by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(view, reloadMs) { while (true) { delay(reloadMs); view?.reload() } }
    AndroidView(
        factory = { c ->
            WebView(c).apply {
                isFocusable = false
                isFocusableInTouchMode = false
                settings.javaScriptEnabled = tile.cfgBool("js", true)
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                setInitialScale(zoom)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = WebViewClient()
                loadUrl(url)
                view = this
            }
        },
        update = { if (it.url != null && it.originalUrl != url && it.url != url) it.loadUrl(url) },
        onRelease = { it.destroy() },
        modifier = Modifier.fillMaxSize(),
    )
}

// ---------------------------------------------------------------- speed test

@Composable
fun SpeedTestTile(tile: Tile) {
    val p = LocalPalette.current
    val st = SpeedTest.state
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) {
        TileHeader("Internet speed")
        val r = st.last
        when {
            st.running -> {
                Text(st.phase, fontSize = 20.sp, color = p.onSurface)
                Text(st.liveMbps?.let { "%.0f Mb/s".format(it) } ?: "", fontSize = 15.sp, color = p.muted)
            }
            r == null -> StatusLine("Press OK to test your connection")
            else -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("↓ %.0f".format(r.downMbps), fontSize = 30.sp, color = p.onSurface, fontWeight = FontWeight.Light)
                    Text(" Mb/s", fontSize = 13.sp, color = p.muted, modifier = Modifier.padding(bottom = 5.dp))
                    Spacer(Modifier.size(14.dp))
                    Text("↑ %.0f".format(r.upMbps), fontSize = 22.sp, color = p.onSurface, fontWeight = FontWeight.Light)
                    Text(" Mb/s", fontSize = 13.sp, color = p.muted, modifier = Modifier.padding(bottom = 3.dp))
                }
                Text("Ping ${r.pingMs} ms · ${ago(r.time) ?: ""} · OK to retest", fontSize = 12.sp, color = p.muted, maxLines = 1)
            }
        }
        st.error?.let { Text("Test failed: $it", fontSize = 12.sp, color = Color(0xFFFF8A80), maxLines = 1) }
    }
}

// ---------------------------------------------------------------- message board

@Composable
fun MessagesTile(tile: Tile) {
    val p = LocalPalette.current
    val msgs = com.canvastv.dashboard.data.Messages.list
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TileHeader(tile.cfg("title").ifBlank { "Messages" })
        if (msgs.isEmpty()) {
            StatusLine(if (com.canvastv.dashboard.data.CompanionServer.running) "Send one from your phone: ${com.canvastv.dashboard.data.CompanionServer.address() ?: ""}"
                else "Turn on Settings → Phone setup to send messages from your phone")
            return@Column
        }
        msgs.take(tile.h * 2).forEach { m ->
            Column {
                Text(m.text, fontSize = 17.sp, color = p.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(m.from.takeIf { it.isNotBlank() }, ago(m.time)).joinToString(" · "), fontSize = 11.sp, color = p.muted, maxLines = 1)
            }
        }
    }
}
