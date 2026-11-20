package com.canvastv.dashboard.ui.tiles

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.canvastv.dashboard.data.Apps
import com.canvastv.dashboard.data.httpGet
import com.canvastv.dashboard.data.rememberRemoteData
import com.canvastv.dashboard.model.Tile
import com.canvastv.dashboard.ui.LocalPalette
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------- countdown

/** When the countdown ends. Yearly events roll over to next year once they've passed. */
fun countdownTarget(tile: Tile, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
    val date = runCatching { LocalDate.parse(tile.cfg("date").trim()) }.getOrNull() ?: return null
    val time = runCatching { LocalTime.parse(tile.cfg("time").trim()) }.getOrDefault(LocalTime.MIDNIGHT)
    var t = LocalDateTime.of(date, time)
    if (tile.cfgBool("yearly", false)) {
        t = t.withYear(now.year)
        // Keep "today" showing all day for date-only yearly events like birthdays.
        val end = if (tile.cfg("time").isBlank()) t.plusDays(1) else t
        if (end.isBefore(now) || end == now) t = t.plusYears(1)
    }
    return t
}

@Composable
fun CountdownTile(tile: Tile, cellHeightDp: Float) {
    val p = LocalPalette.current
    val now by produceState(LocalDateTime.now()) { while (true) { value = LocalDateTime.now(); delay(1000) } }
    val label = tile.cfg("label").ifBlank { "Countdown" }
    val target = countdownTarget(tile, now)
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 16.sp, color = p.accentText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (target == null) {
            Text("Set a date in Configure", fontSize = 15.sp, color = p.muted)
            return@Column
        }
        val big = (cellHeightDp * tile.h * 0.34f).coerceIn(24f, 110f)
        val dateOnly = tile.cfg("time").isBlank()
        val today = now.toLocalDate()
        when {
            dateOnly && target.toLocalDate() == today -> Text("Today!", fontSize = big.sp, color = p.onSurface, fontWeight = FontWeight.Light)
            target.isAfter(now) -> {
                val d = Duration.between(now, target)
                val days = if (dateOnly) java.time.temporal.ChronoUnit.DAYS.between(today, target.toLocalDate()) else d.toDays()
                if (days >= 1) {
                    Text("$days", fontSize = big.sp, color = p.onSurface, fontWeight = FontWeight.Light, maxLines = 1)
                    Text(if (days == 1L) "day to go" else "days to go", fontSize = 14.sp, color = p.muted)
                } else {
                    Text("%d:%02d:%02d".format(d.seconds / 3600, d.seconds % 3600 / 60, d.seconds % 60), fontSize = big.sp, color = p.onSurface, fontWeight = FontWeight.Light, maxLines = 1)
                    Text("to go", fontSize = 14.sp, color = p.muted)
                }
            }
            else -> {
                val ago = java.time.temporal.ChronoUnit.DAYS.between(target.toLocalDate(), today)
                Text(if (ago == 0L) "Now!" else "$ago", fontSize = big.sp, color = p.onSurface, fontWeight = FontWeight.Light)
                if (ago > 0) Text(if (ago == 1L) "day ago" else "days ago", fontSize = 14.sp, color = p.muted)
            }
        }
        if (tile.h >= 2) Text(target.format(DateTimeFormatter.ofPattern(if (dateOnly) "EEE, MMM d, yyyy" else "EEE, MMM d · h:mm a", Locale.getDefault())), fontSize = 13.sp, color = p.muted, maxLines = 1)
    }
}

// ---------------------------------------------------------------- app drawer & folders

@Composable
private fun AppIconRow(pkgs: List<String>, size: Int) {
    val ctx = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        pkgs.forEach { pkg ->
            val art = remember(pkg) { Apps.art(ctx, pkg) }
            Box(Modifier.size(if (art?.second == true) (size * 16 / 9).dp else size.dp, size.dp).clip(RoundedCornerShape(6.dp))) {
                if (art != null) Image(art.first, null, Modifier.fillMaxSize(), contentScale = if (art.second) ContentScale.Crop else ContentScale.Fit)
            }
        }
    }
}

@Composable
fun AppDrawerTile(tile: Tile, count: Int, preview: List<String>) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center) {
        Text(tile.cfg("label").ifBlank { "All apps" }, fontSize = 18.sp, color = p.onSurface, maxLines = 1)
        Text("$count apps · OK to open", fontSize = 12.sp, color = p.muted, maxLines = 1)
        if (tile.h >= 2 || tile.w >= 3) { Spacer(Modifier.size(6.dp)); AppIconRow(preview.take(if (tile.w >= 4) 5 else 3), 28) }
    }
}

fun folderApps(tile: Tile): List<String> = tile.cfg("pkgs").split(',').map { it.trim() }.filter { it.isNotEmpty() }

@Composable
fun FolderTile(tile: Tile) {
    val p = LocalPalette.current
    val pkgs = folderApps(tile)
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("▤ ", fontSize = 18.sp, color = p.accentText)
            Text(tile.cfg("name").ifBlank { "Folder" }, fontSize = 18.sp, color = p.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (pkgs.isEmpty()) "Empty · add apps in Configure" else "${pkgs.size} apps", fontSize = 12.sp, color = p.muted, maxLines = 1)
        if (pkgs.isNotEmpty() && (tile.h >= 2 || tile.w >= 3)) { Spacer(Modifier.size(6.dp)); AppIconRow(pkgs.take(if (tile.w >= 4) 5 else 3), 28) }
    }
}

// ---------------------------------------------------------------- weather (Open-Meteo, no API key)

data class Weather(
    val place: String,
    val tempNow: Int,
    val codeNow: Int,
    val humidity: Int,
    val wind: Int,
    val days: List<Triple<LocalDate, Int, Pair<Int, Int>>>, // date, code, (max, min)
    val aqi: Int? = null,
    /** Active National Weather Service alerts, most severe first (US only). */
    val alerts: List<String> = emptyList(),
)

/** US AQI bands, as AirNow names them. */
fun aqiLabel(aqi: Int): String = when {
    aqi <= 50 -> "Good"
    aqi <= 100 -> "Moderate"
    aqi <= 150 -> "Unhealthy for sensitive groups"
    aqi <= 200 -> "Unhealthy"
    aqi <= 300 -> "Very unhealthy"
    else -> "Hazardous"
}

private val lenient = Json { ignoreUnknownKeys = true }

/** Looks up the place, then fetches current weather and a 4-day forecast. Returns Open-Meteo's JSON plus the place name. */
suspend fun fetchWeather(place: String, fahrenheit: Boolean): String {
    // Accept "City" or "City, Region": search by the first part and prefer a result matching the rest.
    val parts = place.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val geo = lenient.parseToJsonElement(httpGet("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(parts.firstOrNull() ?: place.trim(), "UTF-8")}&count=10&language=en")).jsonObject
    val results = geo["results"]?.jsonArray?.map { it.jsonObject }.orEmpty()
    val hint = parts.drop(1).joinToString(" ").lowercase()
    val hit = results.firstOrNull { r -> hint.isNotEmpty() && listOf("admin1", "country", "country_code").any { k -> r[k]?.jsonPrimitive?.content?.lowercase()?.let { hint.contains(it) || it.contains(hint) } == true } }
        ?: results.firstOrNull() ?: error("Place not found")
    val lat = hit["latitude"]!!.jsonPrimitive.double
    val lon = hit["longitude"]!!.jsonPrimitive.double
    val name = listOfNotNull(hit["name"]?.jsonPrimitive?.content, hit["admin1"]?.jsonPrimitive?.content).joinToString(", ")
    val unit = if (fahrenheit) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else ""
    val fc = httpGet(
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,relative_humidity_2m,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=4$unit",
    )
    // Optional extras; a failure here never hides the forecast.
    val aq = runCatching { httpGet("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=us_aqi,pm2_5") }.getOrNull()
    val cc = hit["country_code"]?.jsonPrimitive?.content
    val alerts = if (cc == "US") runCatching { httpGet("https://api.weather.gov/alerts/active?point=${"%.4f".format(Locale.US, lat)},${"%.4f".format(Locale.US, lon)}") }.getOrNull() else null
    return buildString {
        append("""{"place":${kotlinx.serialization.json.JsonPrimitive(name)},"forecast":$fc""")
        if (aq != null) append(""","aq":$aq""")
        if (alerts != null) append(""","alerts":$alerts""")
        append("}")
    }
}

fun parseWeather(text: String): Weather? = runCatching {
    val root = lenient.parseToJsonElement(text).jsonObject
    val fc = root["forecast"]!!.jsonObject
    val cur = fc["current"]!!.jsonObject
    val daily = fc["daily"]!!.jsonObject
    fun arr(k: String) = daily[k]!!.jsonArray
    val days = arr("time").indices.map { i ->
        Triple(
            LocalDate.parse(arr("time")[i].jsonPrimitive.content),
            arr("weather_code")[i].jsonPrimitive.int,
            arr("temperature_2m_max")[i].jsonPrimitive.double.roundToInt() to arr("temperature_2m_min")[i].jsonPrimitive.double.roundToInt(),
        )
    }
    Weather(
        place = root["place"]!!.jsonPrimitive.content,
        tempNow = cur["temperature_2m"]!!.jsonPrimitive.double.roundToInt(),
        codeNow = cur["weather_code"]!!.jsonPrimitive.int,
        humidity = cur["relative_humidity_2m"]?.jsonPrimitive?.double?.roundToInt() ?: 0,
        wind = cur["wind_speed_10m"]?.jsonPrimitive?.double?.roundToInt() ?: 0,
        days = days,
        aqi = runCatching { root["aq"]!!.jsonObject["current"]!!.jsonObject["us_aqi"]!!.jsonPrimitive.double.roundToInt() }.getOrNull(),
        alerts = runCatching {
            val rank = mapOf("Extreme" to 0, "Severe" to 1, "Moderate" to 2, "Minor" to 3)
            root["alerts"]!!.jsonObject["features"]!!.jsonArray.map { it.jsonObject["properties"]!!.jsonObject }
                .sortedBy { rank[it["severity"]?.jsonPrimitive?.content] ?: 9 }
                .mapNotNull { it["event"]?.jsonPrimitive?.content }.distinct()
        }.getOrDefault(emptyList()),
    )
}.getOrNull()

/** WMO weather codes as used by Open-Meteo. */
fun weatherText(code: Int): Pair<String, String> = when (code) {
    0 -> "☀" to "Clear"
    1 -> "🌤" to "Mostly clear"
    2 -> "⛅" to "Partly cloudy"
    3 -> "☁" to "Cloudy"
    45, 48 -> "🌫" to "Fog"
    51, 53, 55, 56, 57 -> "🌦" to "Drizzle"
    61, 63, 65, 66, 67 -> "🌧" to "Rain"
    71, 73, 75, 77 -> "🌨" to "Snow"
    80, 81, 82 -> "🌦" to "Showers"
    85, 86 -> "🌨" to "Snow showers"
    95, 96, 99 -> "⛈" to "Thunderstorm"
    else -> "•" to "—"
}

@Composable
fun WeatherTile(tile: Tile, cellHeightDp: Float) {
    val p = LocalPalette.current
    val place = tile.cfg("place")
    if (place.isBlank()) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) {
            Text("Weather", fontSize = 15.sp, color = p.accentText)
            Text("Set your city in Configure", fontSize = 15.sp, color = p.muted)
        }
        return
    }
    val f = tile.cfg("units", "F") == "F"
    val refreshMs = (tile.cfg("refreshMin", "30").toLongOrNull() ?: 30L).coerceAtLeast(5L) * 60_000L
    val data by rememberRemoteData("weather:$place:$f", refreshMs) { fetchWeather(place, f) }
    val w = data?.text?.let(::parseWeather)
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) {
        if (w == null) {
            Text(place, fontSize = 15.sp, color = p.accentText, maxLines = 1)
            Text(if (data?.error != null) "Couldn't load weather (${data?.error})" else "Loading…", fontSize = 14.sp, color = p.muted)
            return@Column
        }
        val (icon, desc) = weatherText(w.codeNow)
        val big = (cellHeightDp * tile.h * 0.32f).coerceIn(26f, 90f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = (big * 0.8f).sp)
            Spacer(Modifier.size(10.dp))
            Text("${w.tempNow}°", fontSize = big.sp, color = p.onSurface, fontWeight = FontWeight.Light)
            Spacer(Modifier.size(12.dp))
            Column {
                Text(desc, fontSize = 15.sp, color = p.onSurface, maxLines = 1)
                Text(w.place, fontSize = 12.sp, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (tile.h >= 2) {
            Spacer(Modifier.size(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                w.days.take(if (tile.w >= 4) 4 else 3).forEachIndexed { i, (date, code, mm) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (i == 0) "Today" else date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()), fontSize = 12.sp, color = p.muted)
                        Text(weatherText(code).first, fontSize = 18.sp)
                        Text("${mm.first}° / ${mm.second}°", fontSize = 12.sp, color = p.onSurface)
                    }
                }
            }
        }
        val ageMin = ((System.currentTimeMillis() - (data?.updatedAt ?: 0L)) / 60_000).toInt()
        if (w.alerts.isNotEmpty()) Text("⚠ " + w.alerts.joinToString(" · "), fontSize = 13.sp, color = Color(0xFFFFB74D), maxLines = 1, overflow = TextOverflow.Ellipsis)
        else if (w.aqi != null && (tile.h >= 2 || w.aqi > 100)) Text("Air quality ${w.aqi} · ${aqiLabel(w.aqi)}", fontSize = 12.sp,
            color = if (w.aqi > 100) Color(0xFFFFB74D) else p.muted, maxLines = 1)
        if (data?.stale == true) Text("Offline · showing data from $ageMin min ago", fontSize = 11.sp, color = p.muted)
    }
}
