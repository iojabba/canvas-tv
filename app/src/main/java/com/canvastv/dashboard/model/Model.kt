package com.canvastv.dashboard.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * The whole dashboard, saved as one JSON document.
 * Grid coordinates are in cells: x/y from the top-left, w/h in cells.
 */
@Serializable
data class Dashboard(
    val version: Int = SCHEMA_VERSION,
    val columns: Int = 12,
    val rows: Int = 6,
    val overscanPct: Float = 0f,
    val pageTimerSec: Int = 0,
    /** Turns off live previews, zoom, glow and background images for slower TVs. */
    val lightweight: Boolean = false,
    /** SHA-256 of the edit-lock PIN, or null when edit mode is unlocked. */
    val pinHash: String? = null,
    val theme: ThemeSpec = ThemeSpec(),
    val apps: AppPrefs = AppPrefs(),
    val pages: List<Page> = listOf(Page(name = "Home")),
) {
    companion object { const val SCHEMA_VERSION = 1 }
}

@Serializable
data class Page(
    val id: String = newId(),
    val name: String,
    val tiles: List<Tile> = emptyList(),
)

@Serializable
data class Tile(
    val id: String = newId(),
    val type: TileType,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val config: Map<String, String> = emptyMap(),
    val style: TileStyle = TileStyle(),
) {
    fun cfg(key: String, default: String = "") = config[key] ?: default
    fun cfgBool(key: String, default: Boolean) = config[key]?.toBooleanStrictOrNull() ?: default
    fun withCfg(key: String, value: String) = copy(config = config + (key to value))
    fun overlaps(o: Tile) = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
}

@Serializable
enum class TileType(val label: String, val defaultW: Int, val defaultH: Int) {
    CLOCK("Clock", 4, 2),
    APP("App shortcut", 2, 1),
    HDMI("HDMI input", 4, 2),
    STATS("System stats", 3, 2),
    NOTE("Note", 3, 1),
    VIDEO("Video stream", 4, 2),
    NOW_PLAYING("Now playing", 4, 2),
    COUNTDOWN("Countdown", 3, 2),
    APP_DRAWER("All apps", 2, 1),
    FOLDER("App folder", 2, 1),
    WEATHER("Weather", 3, 2),
    NEWS("News / RSS", 4, 2),
    TICKER("Stocks & crypto", 4, 1),
    CALENDAR("Calendar", 3, 3),
    PHOTOS("Photo slideshow", 4, 3),
    WEB("Web page", 4, 3),
    SPEEDTEST("Speed test", 3, 2),
}

/** How the app drawer lists apps, which ones it hides, and which were opened recently. */
@Serializable
data class AppPrefs(
    val hidden: List<String> = emptyList(),
    val sort: AppSort = AppSort.NAME,
    /** Most recent first, launches made from Canvas TV only. */
    val recent: List<String> = emptyList(),
)

@Serializable
enum class AppSort(val label: String) { NAME("A to Z"), RECENT("Recently used"), TV_FIRST("TV apps first") }

@Serializable
data class TileStyle(
    val cornerDp: Int = 18,
    val opacity: Float = 0.72f,
    val border: Boolean = false,
    val colorHex: String? = null,
)

@Serializable
enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), AMOLED("AMOLED black"), AUTO("Auto by time of day") }

@Serializable
enum class BgKind { SOLID, GRADIENT, IMAGE, BING }

@Serializable
data class Background(
    val kind: BgKind = BgKind.GRADIENT,
    val color1: String = "#0F2027",
    val color2: String = "#2C5364",
    val imageUrl: String = "",
    val dim: Float = 0.25f,
)

@Serializable
data class ThemeSpec(
    val mode: ThemeMode = ThemeMode.DARK,
    val accentHex: String = "#FFC857",
    val background: Background = Background(),
    val fontScale: Float = 1f,
    val focusScale: Float = 1.06f,
    val focusEffect: FocusEffect = FocusEffect.GLOW,
    val font: FontChoice = FontChoice.SANS,
    /** Solid tiles, strong outlines and pure black/white text. */
    val highContrast: Boolean = false,
    /** Take the accent color from the background picture. */
    val autoAccent: Boolean = false,
    /** Switch to built-in holiday colors around US holidays. */
    val seasonal: Boolean = false,
    /** Team colors on chosen days of the week (1 = Monday … 7 = Sunday). */
    val gameDayColors: Int = -1,
    val gameDays: List<Int> = listOf(7),
)

@Serializable
enum class FocusEffect(val label: String) { GLOW("Glow"), OUTLINE("Outline"), THICK("Thick outline"), ZOOM("Zoom only") }

@Serializable
enum class FontChoice(val label: String) { SANS("Default"), CONDENSED("Condensed"), SERIF("Serif"), MONO("Monospace") }

fun newId(): String = UUID.randomUUID().toString().take(8)

// ---------------------------------------------------------------- grid helpers

fun Tile.fits(d: Dashboard) = x >= 0 && y >= 0 && w >= 1 && h >= 1 && x + w <= d.columns && y + h <= d.rows

/** True if [t] fits the grid and doesn't overlap any other tile on the page. */
fun Page.canPlace(t: Tile, d: Dashboard) = t.fits(d) && tiles.none { it.id != t.id && it.overlaps(t) }

/** Finds the first free spot for a w×h tile, scanning row by row. */
fun Page.freeSpot(w: Int, h: Int, d: Dashboard): Pair<Int, Int>? {
    for (y in 0..d.rows - h) for (x in 0..d.columns - w) {
        val probe = Tile(type = TileType.NOTE, x = x, y = y, w = w, h = h)
        if (canPlace(probe, d)) return x to y
    }
    return null
}
