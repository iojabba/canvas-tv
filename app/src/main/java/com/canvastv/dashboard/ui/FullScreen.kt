package com.canvastv.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.canvastv.dashboard.model.Tile
import com.canvastv.dashboard.model.TileType
import com.canvastv.dashboard.ui.tiles.HdmiView
import com.canvastv.dashboard.ui.tiles.StreamPlayer
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen view of an HDMI or video tile.
 * For HDMI this is "game mode": the console fills the screen and our own player sits in a corner.
 * Back returns to the dashboard (handled in MainActivity, because TvView can swallow keys).
 */
@Composable
fun FullScreenTile(tile: Tile) {
    var status by remember { mutableStateOf("") }
    var showHint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(4000); showHint = false }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (tile.type) {
            TileType.HDMI -> HdmiView(tile.cfg("inputId")) { status = it }
            TileType.VIDEO -> StreamPlayer(tile.cfg("url"), muted = tile.cfgBool("muted", false), fill = false)
            else -> Unit
        }

        if (tile.type == TileType.HDMI) {
            val align = when (tile.cfg("cornerPos", "BR")) {
                "BL" -> Alignment.BottomStart
                "TR" -> Alignment.TopEnd
                "TL" -> Alignment.TopStart
                else -> Alignment.BottomEnd
            }
            val width = when (tile.cfg("cornerSize", "M")) { "S" -> 320.dp; "L" -> 640.dp; else -> 460.dp }
            val url = tile.cfg("cornerUrl")
            val clock = tile.cfgBool("cornerClock", true)
            if (url.isNotBlank() || clock) {
                Column(Modifier.align(align).padding(32.dp).width(width)) {
                    if (clock) CornerClock()
                    if (url.isNotBlank()) {
                        Box(Modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
                            StreamPlayer(url, muted = tile.cfgBool("cornerMuted", true))
                        }
                    }
                }
            }
        }

        if (status.isNotBlank()) Hint(status, Modifier.align(Alignment.Center))
        if (showHint) Hint("${tile.cfg("label", tile.cfg("title", "Full screen"))} · press Back for the dashboard", Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
    }
}

@Composable
private fun CornerClock() {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    Text(
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(now)),
        fontSize = 22.sp, color = Color.White,
        modifier = Modifier.padding(bottom = 8.dp).background(Color(0xAA000000), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
