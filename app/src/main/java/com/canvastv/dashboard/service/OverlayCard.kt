package com.canvastv.dashboard.service

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.canvastv.dashboard.data.DashboardViewModel
import com.canvastv.dashboard.data.Messages
import com.canvastv.dashboard.data.rememberRemoteData
import com.canvastv.dashboard.model.OverlayMode
import com.canvastv.dashboard.model.TileType
import com.canvastv.dashboard.ui.tiles.fetchWeather
import com.canvastv.dashboard.ui.tiles.parseWeather
import com.canvastv.dashboard.ui.tiles.weatherText
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** The card the overlay window shows over other apps. Reads the live layout, so Settings changes apply at once. */
@Composable
fun OverlayCard() {
    val ctx = LocalContext.current
    val d = DashboardViewModel.layoutState(ctx)?.value ?: return
    val spec = d.overlay
    val accent = runCatching { Color(android.graphics.Color.parseColor(d.theme.accentHex)) }.getOrDefault(Color(0xFFFFC857))
    val msg = Messages.banner
    LaunchedEffect(msg) { if (msg != null) { delay(20_000); if (Messages.banner == msg) Messages.banner = null } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000L * (60 - (now / 1000) % 60).coerceAtLeast(1)) } }

    val bg = Color(0xFF10161E).copy(alpha = spec.opacity.coerceIn(0.3f, 1f))
    Column(
        Modifier.widthIn(max = 460.dp).background(bg, RoundedCornerShape(16.dp))
            .then(if (msg != null) Modifier.border(2.dp, accent, RoundedCornerShape(16.dp)) else Modifier)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        if (spec.mode != OverlayMode.MESSAGES) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)), fontSize = 30.sp, color = Color.White, fontWeight = FontWeight.Light)
                if (spec.mode == OverlayMode.CLOCK_WEATHER) {
                    val place = spec.weatherPlace.ifBlank {
                        d.pages.flatMap { it.tiles }.firstOrNull { it.type == TileType.WEATHER && it.cfg("place").isNotBlank() }?.cfg("place").orEmpty()
                    }
                    if (place.isBlank()) {
                        Spacer(Modifier.size(14.dp))
                        Text("Set a city in Settings", fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
                    } else {
                        val f = spec.fahrenheit
                        // Same cache key as the weather tile, so they share one download.
                        val data by rememberRemoteData("weather:$place:$f", 30 * 60_000L) { fetchWeather(place, f) }
                        data?.text?.let(::parseWeather)?.let { w ->
                            Spacer(Modifier.size(14.dp))
                            Text(weatherText(w.codeNow).first, fontSize = 24.sp)
                            Spacer(Modifier.size(6.dp))
                            Text("${w.tempNow}°", fontSize = 26.sp, color = Color.White)
                        }
                    }
                }
            }
        }
        if (msg != null) {
            if (spec.mode != OverlayMode.MESSAGES) Spacer(Modifier.size(6.dp))
            Text(if (msg.from.isBlank()) "Message" else "Message from ${msg.from}", fontSize = 14.sp, color = accent)
            Text(msg.text, fontSize = 22.sp, color = Color.White)
        }
    }
}
