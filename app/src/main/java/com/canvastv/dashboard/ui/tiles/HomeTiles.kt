package com.canvastv.dashboard.ui.tiles

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.canvastv.dashboard.data.DashboardViewModel
import com.canvastv.dashboard.data.HaEntity
import com.canvastv.dashboard.data.HomeAssistant
import com.canvastv.dashboard.data.RemoteData
import com.canvastv.dashboard.data.WidgetCache
import com.canvastv.dashboard.data.parseHaStates
import com.canvastv.dashboard.data.splitList
import com.canvastv.dashboard.model.HaServer
import com.canvastv.dashboard.model.Tile
import com.canvastv.dashboard.ui.LocalPalette
import kotlinx.coroutines.delay

fun haIds(tile: Tile): List<String> = splitList(tile.cfg("entities"))
private fun haKey(ids: List<String>) = "ha:" + ids.joinToString(",")

/** The Home Assistant server saved in Settings (shared by every tile). */
@Composable
fun haServer(): HaServer = DashboardViewModel.layoutState(LocalContext.current)?.value?.homeAssistant ?: HaServer()

/**
 * Live states for [ids]. Starts from the disk cache, refreshes every [refreshMs], and again
 * right after any button press anywhere in the app.
 */
@Composable
fun rememberHaStates(server: HaServer, ids: List<String>, refreshMs: Long): State<RemoteData?> {
    val ctx = LocalContext.current.applicationContext
    val key = haKey(ids)
    return produceState(WidgetCache.read(ctx, key), key, server, HomeAssistant.revision) {
        if (!server.configured || ids.isEmpty()) return@produceState
        while (true) {
            value = try {
                val text = HomeAssistant.fetchStates(server, ids)
                WidgetCache.write(ctx, key, text)
                RemoteData(text, System.currentTimeMillis())
            } catch (e: Exception) {
                (value ?: RemoteData(null, 0L)).copy(error = e.message ?: "offline")
            }
            delay(if (value?.stale == true) minOf(refreshMs, 30_000L) else refreshMs)
        }
    }
}

/** A one-device tile with a harmless action (a light, a scene) acts on OK directly; others open the list. */
fun haQuickEntity(ctx: Context, tile: Tile): HaEntity? {
    val ids = haIds(tile)
    if (ids.size != 1) return null
    val e = WidgetCache.read(ctx, haKey(ids))?.text?.let { parseHaStates(it, ids) }?.firstOrNull() ?: return null
    return e.takeIf { it.action != null && !it.needsConfirm && !it.missing }
}

@Composable
fun HomeAssistantTile(tile: Tile, cellHeightDp: Float) {
    val p = LocalPalette.current
    val server = haServer()
    val ids = haIds(tile)
    val title = tile.cfg("title").ifBlank { "Home" }
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Text(title, fontSize = 15.sp, color = p.accentText, maxLines = 1)
        when {
            !server.configured -> { Text("Add your Home Assistant address and token in Settings", fontSize = 14.sp, color = p.muted); return@Column }
            ids.isEmpty() -> { Text("Choose devices in Configure", fontSize = 14.sp, color = p.muted); return@Column }
        }
        val refreshMs = (tile.cfg("refreshSec", "30").toLongOrNull() ?: 30L).coerceAtLeast(5L) * 1000L
        val data by rememberHaStates(server, ids, refreshMs)
        val list = data?.text?.let { parseHaStates(it, ids) }
        if (list == null) {
            Text(if (data?.error != null) "Couldn't reach Home Assistant (${data?.error})" else "Loading…", fontSize = 14.sp, color = p.muted)
            return@Column
        }
        val fit = (((cellHeightDp * tile.h) - 64f) / 28f).toInt().coerceAtLeast(1)
        Spacer(Modifier.size(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            list.take(fit).forEach { e -> HaRow(e) }
        }
        val more = list.size - fit
        if (more > 0) Text("+$more more · press OK", fontSize = 12.sp, color = p.muted)
        else if (data?.stale == true) Text("Offline · showing the last known state", fontSize = 11.sp, color = p.muted)
    }
}

@Composable
private fun HaRow(e: HaEntity) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val dot = when {
            e.missing || e.state == "unavailable" -> Color(0xFFFF8A80)
            e.isOn -> p.accent
            else -> p.muted.copy(alpha = 0.5f)
        }
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        Spacer(Modifier.size(10.dp))
        Text(e.name, fontSize = 16.sp, color = p.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.size(8.dp))
        Text(e.display, fontSize = 15.sp, color = if (e.isOn) p.accentText else p.muted, maxLines = 1)
    }
}
