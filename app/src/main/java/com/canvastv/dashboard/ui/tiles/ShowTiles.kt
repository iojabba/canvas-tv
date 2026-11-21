package com.canvastv.dashboard.ui.tiles

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateFloat
import com.canvastv.dashboard.data.Apps
import com.canvastv.dashboard.data.Episodes
import com.canvastv.dashboard.ui.readableOn
import com.canvastv.dashboard.data.Shows
import com.canvastv.dashboard.data.rememberRemoteData
import com.canvastv.dashboard.model.Tile
import com.canvastv.dashboard.ui.LocalPalette

/** The show's name and picture: what's set in Configure, else what the show's page says. */
@Composable
fun showInfo(tile: Tile): Pair<String, String> {
    val link = tile.cfg("link")
    var title = tile.cfg("title")
    var image = tile.cfg("image")
    if (link.isNotBlank() && (title.isBlank() || image.isBlank())) {
        // A show's page rarely changes; look it up once a week.
        val data by rememberRemoteData("show:$link", 7L * 24 * 3600 * 1000) { Shows.fetchInfo(link) }
        val (t, i) = Shows.parseInfo(data?.text)
        if (title.isBlank()) title = t
        if (image.isBlank()) image = i
    }
    return title to image
}

/** The cache key for a show's episode data; the page tab reads the same cache to count new shows. */
fun episodesKey(tile: Tile, lookup: String): String =
    "episodes:${tile.cfg("tvmaze").toIntOrNull() ?: lookup}:${Shows.serviceFor(tile.cfg("link"))?.name}"

/** True when [t] is a show tile with a new episode, from cached data (no network). */
fun hasNewEpisode(ctx: android.content.Context, t: Tile): Boolean =
    t.type == com.canvastv.dashboard.model.TileType.SHOW && t.cfgBool("newBadge", true) &&
        (t.cfg("title").isNotBlank() || t.cfg("tvmaze").isNotBlank()) &&
        Episodes.isNew(Episodes.parse(com.canvastv.dashboard.data.WidgetCache.read(ctx, episodesKey(t, t.cfg("title")))?.text), t.cfg("lastOpened").toLongOrNull())

/** How many show tiles on [page] have a new episode. */
fun newShowCount(ctx: android.content.Context, page: com.canvastv.dashboard.model.Page): Int = page.tiles.count { hasNewEpisode(ctx, it) }

/**
 * Moves show tiles with new episodes into the earliest show spots (reading order), among shows
 * of the same size. Other tiles stay put, so nothing overlaps.
 */
fun newShowsFirst(tiles: List<Tile>, isNew: (Tile) -> Boolean): List<Tile> {
    val moved = HashMap<String, Tile>()
    tiles.filter { it.type == com.canvastv.dashboard.model.TileType.SHOW }.groupBy { it.w to it.h }.values.forEach { group ->
        val slots = group.map { it.x to it.y }.sortedWith(compareBy({ it.second }, { it.first }))
        val order = group.sortedWith(compareBy<Tile>({ !isNew(it) }, { it.y }, { it.x }))
        order.forEachIndexed { i, t -> moved[t.id] = t.copy(x = slots[i].first, y = slots[i].second) }
    }
    return tiles.map { moved[it.id] ?: it }
}

@Composable
fun ShowTile(tile: Tile, animate: Boolean = true) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val link = tile.cfg("link")
    if (link.isBlank() && tile.cfg("pkg").isBlank()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text("TV show", fontSize = 14.sp, color = p.accentText)
            Text("Choose the show in Configure", fontSize = 13.sp, color = p.muted)
        }
        return
    }
    var (title, image) = showInfo(tile)
    val service = remember(link) { Shows.serviceFor(link) }
    // New-episode check (TVmaze), a few times a day.
    val lookup = tile.cfg("title").ifBlank { title }
    val tvmazeId = tile.cfg("tvmaze").toIntOrNull()
    val eps = if (tile.cfgBool("newBadge", true) && (lookup.isNotBlank() || tvmazeId != null)) {
        val data by rememberRemoteData(episodesKey(tile, lookup), 6L * 3600 * 1000) {
            Episodes.fetch(lookup, service?.name, tvmazeId)
        }
        Episodes.parse(data?.text)
    } else null
    if (tile.cfg("image").isBlank()) {
        // TVmaze's wide picture looks best on a 2×1 tile; the page's own picture is next.
        image = eps?.background ?: image.ifBlank { eps?.poster.orEmpty() }
    }
    if (title.isBlank()) title = eps?.name.orEmpty()
    val isNew = Episodes.isNew(eps, tile.cfg("lastOpened").toLongOrNull())
    val pkg = remember(link, tile.cfg("pkg")) { Shows.appFor(ctx, tile) }
    val badge = remember(pkg) { pkg?.let { Apps.art(ctx, it) } }
    Box(Modifier.fillMaxSize()) {
        if (image.isNotBlank()) {
            AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(p.accent.copy(alpha = 0.35f), Color.Transparent))))
        }
        // Shows with nothing new step back a little so the new ones stand out.
        if (!isNew && eps != null && tile.cfgBool("dimOld", true)) Box(Modifier.fillMaxSize().background(Color(0x66000000)))
        val showTitle = tile.cfgBool("showTitle", true) || image.isBlank()
        val next = if (tile.cfgBool("showNext", true)) Episodes.nextLabel(eps) else null
        if (showTitle || next != null) {
            androidx.compose.foundation.layout.Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                    .padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (showTitle) title.ifBlank { service?.name ?: "Show" } else "", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // When the next new (first-run) episode comes out.
                if (next != null) Text(
                    next, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    color = if (next.startsWith("Tonight") || next.startsWith("Tomorrow")) p.accent else Color.White,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (isNew) {
            // A thick, slowly pulsing frame and a ribbon across the top: visible from the couch.
            val pulse = if (animate) {
                val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "new")
                t.animateFloat(0.55f, 1f, androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.tween(1100), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse").value
            } else 1f
            Box(Modifier.fillMaxSize().border(5.dp, p.accent.copy(alpha = pulse)))
            Box(
                Modifier.align(Alignment.TopStart).fillMaxWidth().background(p.accent.copy(alpha = 0.92f)).padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    "★ NEW EPISODE" + (eps?.lastLabel?.let { " · $it" } ?: ""), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = readableOn(p.accent), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Which app it opens in, as a small corner icon.
        if (badge != null && tile.cfgBool("showApp", true) && !isNew) {
            Image(
                badge.first, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(if (badge.second) 40.dp else 24.dp, 24.dp).clip(RoundedCornerShape(5.dp)),
            )
        } else if (pkg == null && !isNew) {
            Text(
                "App not installed", fontSize = 11.sp, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0xAA000000), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}
