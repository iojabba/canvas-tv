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

@Composable
fun ShowTile(tile: Tile) {
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
        val data by rememberRemoteData("episodes:${tvmazeId ?: lookup}:${service?.name}", 6L * 3600 * 1000) {
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
        val showTitle = tile.cfgBool("showTitle", true) || image.isBlank()
        if (showTitle) {
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 6.dp),
            ) {
                Text(title.ifBlank { service?.name ?: "Show" }, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (isNew) {
            Box(Modifier.fillMaxSize().border(3.dp, p.accent))
            Text(
                "NEW" + (eps?.lastLabel?.let { " · $it" } ?: ""), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = readableOn(p.accent),
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(p.accent, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        } else if (eps?.nextAirs != null && tile.cfgBool("showNext", true)) {
            val days = java.time.Duration.between(java.time.Instant.now(), eps.nextAirs).toDays()
            if (days in 0..6) Text(
                if (days == 0L) "New tonight" else "Next: " + eps.nextAirs.atZone(java.time.ZoneId.systemDefault()).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()),
                fontSize = 11.sp, color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0xAA000000), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        // Which app it opens in, as a small corner icon.
        if (badge != null && tile.cfgBool("showApp", true)) {
            Image(
                badge.first, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(if (badge.second) 40.dp else 24.dp, 24.dp).clip(RoundedCornerShape(5.dp)),
            )
        } else if (pkg == null) {
            Text(
                "App not installed", fontSize = 11.sp, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0xAA000000), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}
