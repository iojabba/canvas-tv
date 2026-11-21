package com.canvastv.dashboard.data

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.canvastv.dashboard.model.Background
import com.canvastv.dashboard.model.AppSort
import com.canvastv.dashboard.model.BgKind
import com.canvastv.dashboard.model.Dashboard
import com.canvastv.dashboard.model.Page
import com.canvastv.dashboard.model.Tile
import com.canvastv.dashboard.model.TileType
import com.canvastv.dashboard.model.canPlace
import com.canvastv.dashboard.model.freeSpot
import com.canvastv.dashboard.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** What the remote's arrow keys are doing to a tile right now. */
enum class EditMode { MOVE, RESIZE }

data class ActiveEdit(val tileId: String, val mode: EditMode)

class DashboardViewModel(app: Application) : AndroidViewModel(app), CompanionApi {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val file = File(app.filesDir, "dashboard.json")

    /**
     * The layout lives once per process, not once per screen. Android can open a second copy of
     * the dashboard (one as the home screen and one from the apps row); both now show and edit
     * the same layout instead of overwriting each other's saves.
     */
    private val shared: androidx.compose.runtime.MutableState<Dashboard> =
        synchronized(Companion) { sharedLayout ?: mutableStateOf(load()).also { sharedLayout = it } }

    /** Settings that the background service acts on. */
    fun setOpenOnStartup(on: Boolean) { update { it.copy(openOnStartup = on) }; com.canvastv.dashboard.service.CanvasService.sync(getApplication()) }
    fun setOverlay(o: com.canvastv.dashboard.model.OverlaySpec) { update { it.copy(overlay = o) }; com.canvastv.dashboard.service.CanvasService.sync(getApplication()) }
    fun setHomeAssistant(h: com.canvastv.dashboard.model.HaServer) = update { it.copy(homeAssistant = h) }

    var dashboard: Dashboard
        get() = shared.value
        private set(v) { shared.value = v }

    init {
        CompanionServer.attach(this)
        if (dashboard.phoneSetup) CompanionServer.start(app)
        Messages.load(app)
    }

    init {
        // Write the starting layout right away, so a fresh install doesn't rebuild the preset
        // (in a different order) on every launch until the first edit.
        if (!file.exists()) scheduleSave()
    }
    var pageIndex by mutableStateOf(0)
    var editing by mutableStateOf(false)
    var activeEdit by mutableStateOf<ActiveEdit?>(null)
    var fullScreenTile by mutableStateOf<Tile?>(null)
    var message by mutableStateOf<String?>(null)

    /** True after the PIN was entered; cleared when edit mode ends. */
    var unlocked by mutableStateOf(false)
        private set
    /** Set while the PIN pad is showing; runs once the right PIN is entered. */
    var pinRequest by mutableStateOf<(() -> Unit)?>(null)
        private set
    private var failedPins = 0
    private var pinLockedUntil = 0L

    /** The layout as it was when edit mode started, to decide if a history entry is needed. */
    private var editStart: Dashboard? = null

    val page: Page get() = dashboard.pages[pageIndex.coerceIn(0, dashboard.pages.lastIndex)]

    // ------------------------------------------------------------ persistence

    private fun load(): Dashboard {
        val text = runCatching { file.readText() }.getOrNull()
        val parsed = text?.let { runCatching { json.decodeFromString(Dashboard.serializer(), it) }.getOrNull() }
        if (text != null && parsed == null) {
            // Keep the unreadable file for inspection instead of silently losing it.
            runCatching { file.copyTo(File(file.parentFile, "dashboard.broken.json"), overwrite = true) }
            Log.w(TAG, "Saved layout could not be read; starting from the preset")
        }
        return parsed?.takeIf { it.pages.isNotEmpty() } ?: minimalPreset()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        val snapshot = dashboard
        // A process-wide scope, so a save still finishes if this screen closes right after an edit.
        saveJob = saveScope.launch {
            delay(400)
            runCatching {
                val tmp = File(file.parentFile, "dashboard.json.tmp")
                tmp.writeText(json.encodeToString(Dashboard.serializer(), snapshot))
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }.onFailure { Log.e(TAG, "Save failed", it) }
        }
    }

    private fun update(block: (Dashboard) -> Dashboard) {
        dashboard = block(dashboard)
        scheduleSave()
    }

    private fun updatePage(block: (Page) -> Page) = update { d ->
        val i = pageIndex.coerceIn(0, d.pages.lastIndex)
        d.copy(pages = d.pages.toMutableList().also { it[i] = block(it[i]) })
    }

    fun replaceDashboard(d: Dashboard) = update { d }

    /** Accent picked from the current background picture, for "Accent from background". */
    var wallpaperAccent by mutableStateOf<String?>(null)

    /** The theme with today's game-day or holiday colors and the background accent applied. */
    fun effectiveTheme(today: java.time.LocalDate = java.time.LocalDate.now()): com.canvastv.dashboard.model.ThemeSpec {
        var t = dashboard.theme
        val look = GameDayColors.getOrNull(t.gameDayColors)?.takeIf { today.dayOfWeek.value in t.gameDays }
            ?: if (t.seasonal) seasonFor(today) else null
        if (look != null) {
            val bg = if (t.background.kind == BgKind.GRADIENT || t.background.kind == BgKind.SOLID)
                t.background.copy(kind = BgKind.GRADIENT, color1 = look.color1, color2 = look.color2) else t.background
            return t.copy(accentHex = look.accent, background = bg)
        }
        if (t.autoAccent) wallpaperAccent?.let { t = t.copy(accentHex = it) }
        return t
    }

    // ------------------------------------------------------------ edit lock

    val locked: Boolean get() = dashboard.pinHash != null && !unlocked

    /** Runs [action] now, or after the PIN is entered when edit mode is locked. */
    fun guard(action: () -> Unit) { if (locked) pinRequest = action else action() }

    fun cancelPin() { pinRequest = null }

    /** Returns null if the PIN was right, otherwise a message to show. */
    fun submitPin(pin: String): String? {
        val now = System.currentTimeMillis()
        if (now < pinLockedUntil) return "Too many tries. Wait ${(pinLockedUntil - now) / 1000 + 1} s."
        if (hashPin(pin) != dashboard.pinHash) {
            failedPins++
            if (failedPins >= 5) { failedPins = 0; pinLockedUntil = now + 30_000; return "Too many tries. Wait 30 s." }
            return "Wrong PIN"
        }
        failedPins = 0
        unlocked = true
        val action = pinRequest
        pinRequest = null
        action?.invoke()
        return null
    }

    fun setPin(pin: String?) {
        update { it.copy(pinHash = pin?.let(::hashPin)) }
        unlocked = pin != null // stay unlocked for the rest of this session in Settings
        message = if (pin == null) "Edit lock removed" else "Edit lock PIN set"
    }

    fun startEditing() = guard {
        if (!editing) editStart = dashboard
        editing = true
    }

    fun stopEditing() {
        editing = false
        activeEdit = null
        unlocked = false
        val before = editStart
        editStart = null
        if (before != null && before != dashboard) History.save(getApplication(), json, before, "Before edits")
    }

    /** Called when Settings closes, so the lock comes back if we aren't editing. */
    fun relockIfIdle() { if (!editing) unlocked = false }

    // ------------------------------------------------------------ backup, restore, history

    fun setLightweight(on: Boolean) = update { it.copy(lightweight = on) }

    fun historyEntries() = History.list(getApplication())

    fun backupFiles() = Backups.list(getApplication())

    fun exportBackup() {
        val f = runCatching { Backups.export(getApplication(), json, dashboard) }.getOrNull()
        message = if (f != null) "Saved backup ${f.name}" else "Couldn't save the backup"
    }

    /** Replaces the layout with [file]'s contents, keeping the current one in history first. */
    fun restoreFrom(file: File, what: String) {
        val d = runCatching { decode(file.readText()) }.getOrNull()
        if (d == null) { message = "That file isn't a Canvas TV layout"; return }
        restore(d, what)
    }

    fun restoreFromUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val d = runCatching {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                try { decode(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
            }.getOrNull()
            launch(Dispatchers.Main) {
                if (d == null) message = "Couldn't load a layout from that address" else restore(d, "from the web")
            }
        }
    }

    private fun restore(d: Dashboard, what: String) {
        History.save(getApplication(), json, dashboard, "Before restoring $what")
        // Keep this TV's PIN: a backup shouldn't be able to lock or unlock it.
        // A layout without a Home Assistant token (like one from the phone page) keeps this TV's.
        update { cur ->
            val ha = if (d.homeAssistant.token.isBlank()) d.homeAssistant.copy(token = cur.homeAssistant.token) else d.homeAssistant
            sanitize(d).copy(pinHash = cur.pinHash, homeAssistant = ha)
        }
        com.canvastv.dashboard.service.CanvasService.sync(getApplication())
        pageIndex = 0
        message = "Layout restored"
    }

    private fun decode(text: String): Dashboard? =
        runCatching { json.decodeFromString(Dashboard.serializer(), text) }.getOrNull()?.takeIf { it.pages.isNotEmpty() }

    // ------------------------------------------------------------ pages

    /** Set when the page timer (not the person) changed the page, so the highlight isn't moved onto a tile. */
    var pageChangedByTimer = false
        private set
    /** The tile that last had the highlight, to put it back after menus and full screen. */
    var lastFocusedTileId: String? = null

    fun goToPage(i: Int, byTimer: Boolean = false) {
        pageChangedByTimer = byTimer
        pageIndex = i.mod(dashboard.pages.size)
    }

    fun addPage() {
        update { it.copy(pages = it.pages + Page(name = "Page ${it.pages.size + 1}")) }
        pageIndex = dashboard.pages.lastIndex
    }

    fun deletePage() {
        if (dashboard.pages.size <= 1) { message = "The dashboard needs at least one page"; return }
        val i = pageIndex
        History.save(getApplication(), json, dashboard, "Before deleting a page")
        update { d -> d.copy(pages = d.pages.filterIndexed { idx, _ -> idx != i }) }
        pageIndex = (i - 1).coerceAtLeast(0)
    }

    fun renamePage(name: String) = updatePage { it.copy(name = name.ifBlank { it.name }) }

    // ------------------------------------------------------------ tiles

    fun tile(id: String) = page.tiles.firstOrNull { it.id == id }

    fun addTile(type: TileType, config: Map<String, String> = emptyMap()): Boolean {
        val d = dashboard
        val spot = page.freeSpot(type.defaultW, type.defaultH, d)
            ?: page.freeSpot(1, 1, d)
            ?: run { message = "No room on this page. Delete or shrink a tile, or add a page."; return false }
        val w = if (page.freeSpot(type.defaultW, type.defaultH, d) != null) type.defaultW else 1
        val h = if (w == type.defaultW) type.defaultH else 1
        val t = Tile(type = type, x = spot.first, y = spot.second, w = w, h = h, config = config)
        updatePage { it.copy(tiles = it.tiles + t) }
        lastFocusedTileId = t.id // the highlight lands on the new tile
        return true
    }

    fun replaceTile(t: Tile) = updatePage { p -> p.copy(tiles = p.tiles.map { if (it.id == t.id) t else it }) }

    fun deleteTile(id: String) {
        val gone = tile(id)
        updatePage { p -> p.copy(tiles = p.tiles.filterNot { it.id == id }) }
        // Move the highlight to the nearest remaining tile rather than back to the top bar.
        lastFocusedTileId = gone?.let { g -> page.tiles.minByOrNull { (it.x - g.x) * (it.x - g.x) + (it.y - g.y) * (it.y - g.y) }?.id }
    }

    fun duplicateTile(id: String) {
        val t = tile(id) ?: return
        val spot = page.freeSpot(t.w, t.h, dashboard)
            ?: run { message = "No room for a copy on this page"; return }
        val copy = t.copy(id = newId(), x = spot.first, y = spot.second)
        updatePage { it.copy(tiles = it.tiles + copy) }
        lastFocusedTileId = copy.id
    }

    /** Moves or resizes the active tile by one cell. Blocked moves are ignored. */
    fun nudge(dx: Int, dy: Int) {
        val edit = activeEdit ?: return
        val t = tile(edit.tileId) ?: return
        val moved = when (edit.mode) {
            EditMode.MOVE -> t.copy(x = t.x + dx, y = t.y + dy)
            EditMode.RESIZE -> t.copy(w = t.w + dx, h = t.h + dy)
        }
        if (page.canPlace(moved, dashboard)) replaceTile(moved)
    }

    // ------------------------------------------------------------ phone setup (CompanionApi)

    fun setPhoneSetup(on: Boolean) {
        update { it.copy(phoneSetup = on) }
        if (on) CompanionServer.start(getApplication()) else CompanionServer.stop()
    }

    private val webJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The layout as the phone sees it: the PIN hash never leaves the TV. */
    override fun layoutJson(): String = webJson.encodeToString(Dashboard.serializer(), dashboard.copy(pinHash = null, homeAssistant = dashboard.homeAssistant.copy(token = "")))

    override fun replaceLayoutJson(text: String): String? {
        val d = decode(text) ?: return "That isn't a Canvas TV layout"
        restore(d, "from a phone")
        return null
    }

    private fun pageAt(i: Int) = dashboard.pages.getOrNull(i)

    private fun updatePageAt(i: Int, block: (Page) -> Page) = update { d ->
        d.copy(pages = d.pages.toMutableList().also { it[i] = block(it[i]) })
    }

    override fun patchTile(pageIndex: Int, tileId: String, config: Map<String, String>): String? {
        val p = pageAt(pageIndex) ?: return "No such page"
        if (p.tiles.none { it.id == tileId }) return "That tile isn't on the page anymore"
        // A blank field means "use the default", so it's removed rather than saved as "".
        val set = config.filterValues { it.isNotBlank() }.mapValues { it.value.take(4000) }
        val cleared = config.filterValues { it.isBlank() }.keys
        updatePageAt(pageIndex) { pg -> pg.copy(tiles = pg.tiles.map { if (it.id == tileId) it.copy(config = it.config - cleared + set) else it }) }
        return null
    }

    override fun addTileFromWeb(pageIndex: Int, type: String, config: Map<String, String>): String? {
        val t = runCatching { TileType.valueOf(type) }.getOrNull() ?: return "Unknown tile type"
        if (pageAt(pageIndex) == null) return "No such page"
        val before = pageIndex
        val old = this.pageIndex
        this.pageIndex = before
        val cfg = if (t == TileType.SHOW) config.mapValues { (k, v) -> if (k == "link") Shows.normalize(v) else v } else config
        val ok = addTile(t, cfg)
        this.pageIndex = old
        return if (ok) null else "No room on that page"
    }

    override fun deleteTileFromWeb(pageIndex: Int, tileId: String): String? {
        val p = pageAt(pageIndex) ?: return "No such page"
        if (p.tiles.none { it.id == tileId }) return "That tile isn't on the page anymore"
        History.save(getApplication(), json, dashboard, "Before a phone deleted a tile")
        updatePageAt(pageIndex) { pg -> pg.copy(tiles = pg.tiles.filterNot { it.id == tileId }) }
        return null
    }

    override fun showPage(index: Int): String? {
        if (index !in dashboard.pages.indices) return "There are ${dashboard.pages.size} pages"
        fullScreenTile = null
        goToPage(index)
        return null
    }

    override fun appsJson(): String = webJson.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer())),
        runCatching { Apps.list(getApplication()) }.getOrDefault(emptyList()).map { mapOf("pkg" to it.pkg, "label" to it.label) },
    )

    override fun setHomeAssistantFromWeb(url: String?, token: String?): String? {
        val u = url?.trim().orEmpty()
        if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) return "The address should start with http:// or https://"
        val cur = dashboard.homeAssistant
        setHomeAssistant(cur.copy(url = u.ifEmpty { cur.url }, token = token?.trim().orEmpty().ifEmpty { cur.token }))
        return null
    }

    /** Never includes the token itself. */
    override fun homeAssistantJson(): String = kotlinx.serialization.json.buildJsonObject {
        put("url", kotlinx.serialization.json.JsonPrimitive(dashboard.homeAssistant.url))
        put("hasToken", kotlinx.serialization.json.JsonPrimitive(dashboard.homeAssistant.token.isNotBlank()))
    }.toString()

    override fun inputsJson(): String = webJson.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer())),
        runCatching { Inputs.list(getApplication()) }.getOrDefault(emptyList()).map { mapOf("id" to it.id, "label" to it.label) },
    )

    // ------------------------------------------------------------ apps

    /** Opens an app and remembers it for "Recently used". */
    fun launchApp(pkg: String): Boolean {
        val ok = Apps.launch(getApplication(), pkg)
        if (ok) update { d -> d.copy(apps = d.apps.copy(recent = (listOf(pkg) + d.apps.recent.filter { it != pkg }).take(20))) }
        else message = "That app isn't installed anymore"
        return ok
    }

    fun setAppHidden(pkg: String, hidden: Boolean) = update { d ->
        val h = d.apps.hidden.filter { it != pkg } + if (hidden) listOf(pkg) else emptyList()
        d.copy(apps = d.apps.copy(hidden = h))
    }

    fun setAppSort(sort: AppSort) = update { it.copy(apps = it.apps.copy(sort = sort)) }

    /** Installed apps in the drawer's order. Hidden apps only when [includeHidden]. */
    fun drawerApps(includeHidden: Boolean = false): List<AppInfo> {
        val prefs = dashboard.apps
        val all = runCatching { Apps.list(getApplication()) }.getOrDefault(emptyList())
            .filter { includeHidden || it.pkg !in prefs.hidden }
        return when (prefs.sort) {
            AppSort.NAME -> all.sortedBy { it.label.lowercase() }
            AppSort.TV_FIRST -> all // Apps.list already puts TV apps first, then A to Z
            AppSort.RECENT -> {
                val rank = prefs.recent.withIndex().associate { it.value to it.index }
                all.sortedWith(compareBy<AppInfo> { rank[it.pkg] ?: Int.MAX_VALUE }.thenBy { it.label.lowercase() })
            }
        }
    }

    /** Adds an app tile for [pkg] to the current page. */
    fun addAppTile(pkg: String) = addTile(TileType.APP, mapOf("pkg" to pkg))

    // ------------------------------------------------------------ presets

    fun resetToPreset(preset: String = "Minimal") {
        History.save(getApplication(), json, dashboard, "Before $preset preset")
        val d = when (preset) {
            "Info Board" -> infoBoardPreset()
            "Media Hub" -> mediaHubPreset()
            else -> minimalPreset()
        }
        // Keep this TV's PIN, app preferences and look; presets only change the layout.
        update { sanitize(d).copy(pinHash = it.pinHash, apps = it.apps, theme = if (preset == "Minimal") d.theme else it.theme) }
        pageIndex = 0
        message = "$preset preset applied. Your old layout is in Settings → Restore."
    }

    /** "Info Board": clock, weather, countdown, calendar, headlines and a market ticker. */
    private fun infoBoardPreset(): Dashboard {
        val home = Page(name = "Info", tiles = listOf(
            Tile(type = TileType.CLOCK, x = 0, y = 0, w = 4, h = 2, config = mapOf("showDate" to "true")),
            Tile(type = TileType.WEATHER, x = 4, y = 0, w = 4, h = 2),
            Tile(type = TileType.COUNTDOWN, x = 8, y = 0, w = 4, h = 2, config = mapOf("label" to "New Year", "date" to "2000-01-01", "yearly" to "true")),
            Tile(type = TileType.CALENDAR, x = 0, y = 2, w = 4, h = 4),
            Tile(type = TileType.NEWS, x = 4, y = 2, w = 8, h = 3, config = mapOf("urls" to "https://feeds.npr.org/1001/rss.xml", "title" to "Headlines")),
            Tile(type = TileType.TICKER, x = 4, y = 5, w = 8, h = 1, config = mapOf("symbols" to "^SPX, AAPL, MSFT, GOOGL, BTC, ETH")),
        ))
        val apps = Page(name = "Apps", tiles = listOf(
            Tile(type = TileType.APP_DRAWER, x = 0, y = 0, w = 3, h = 1),
            Tile(type = TileType.SPEEDTEST, x = 3, y = 0, w = 3, h = 2),
            Tile(type = TileType.STATS, x = 6, y = 0, w = 3, h = 2),
        ))
        return Dashboard(pages = listOf(home, apps))
    }

    /** "Media Hub": what's playing, the console, streaming apps and the app drawer. */
    private fun mediaHubPreset(): Dashboard {
        val ctx = getApplication<Application>()
        val apps = runCatching { Apps.list(ctx) }.getOrDefault(emptyList()).filter { it.isTvApp }
        val hdmi = runCatching { Inputs.list(ctx) }.getOrDefault(emptyList())
        val input = hdmi.firstOrNull { it.label.contains("2") } ?: hdmi.firstOrNull()
        val streaming = listOf("netflix", "youtube", "disney", "hulu", "amazon", "plex", "jellyfin", "hbo", "max", "peacock", "paramount", "spotify", "twitch")
        val streamApps = apps.filter { a -> streaming.any { a.pkg.contains(it, true) || a.label.contains(it, true) } }
        val tiles = mutableListOf(
            Tile(type = TileType.NOW_PLAYING, x = 0, y = 0, w = 4, h = 2),
            Tile(type = TileType.CLOCK, x = 9, y = 0, w = 3, h = 2, config = mapOf("showDate" to "true")),
            Tile(type = TileType.APP_DRAWER, x = 9, y = 2, w = 3, h = 1),
        )
        if (input != null) tiles += Tile(type = TileType.HDMI, x = 4, y = 0, w = 5, h = 3, config = mapOf("inputId" to input.id, "label" to input.label))
        if (streamApps.size > 6) tiles += Tile(type = TileType.FOLDER, x = 0, y = 2, w = 4, h = 1, config = mapOf("name" to "More streaming", "pkgs" to streamApps.drop(6).joinToString(",") { it.pkg }))
        var page = Page(name = "Media", tiles = tiles)
        val base = Dashboard(pages = listOf(page))
        (streamApps.take(6) + apps.filter { it !in streamApps }).forEach { a ->
            val spot = page.freeSpot(2, 1, base) ?: return@forEach
            page = page.copy(tiles = page.tiles + Tile(type = TileType.APP, x = spot.first, y = spot.second, w = 2, h = 1, config = mapOf("pkg" to a.pkg)))
        }
        return Dashboard(pages = listOf(page))
    }

    /** "Minimal" preset: clock, stats, first HDMI input and the top TV apps, plus an Apps page. */
    private fun minimalPreset(): Dashboard {
        val ctx = getApplication<Application>()
        val apps = runCatching { Apps.list(ctx) }.getOrDefault(emptyList())
        val hdmi = runCatching { Inputs.list(ctx) }.getOrDefault(emptyList())
        val home = mutableListOf(
            Tile(type = TileType.CLOCK, x = 0, y = 0, w = 4, h = 2, config = mapOf("showDate" to "true")),
            Tile(type = TileType.STATS, x = 9, y = 0, w = 3, h = 2),
        )
        hdmi.firstOrNull { it.label.contains("2") }?.let { inp -> // Eric's Xbox is on HDMI 2
            home += Tile(type = TileType.HDMI, x = 4, y = 0, w = 5, h = 3, config = mapOf("inputId" to inp.id, "label" to inp.label))
        } ?: hdmi.firstOrNull()?.let { inp ->
            home += Tile(type = TileType.HDMI, x = 4, y = 0, w = 5, h = 3, config = mapOf("inputId" to inp.id, "label" to inp.label))
        }
        val slots = listOf(0 to 3, 2 to 3, 9 to 2, 0 to 4, 2 to 4, 4 to 4, 6 to 4, 8 to 4, 10 to 4, 0 to 5, 2 to 5, 4 to 5)
        val homePage = Page(name = "Home", tiles = home)
        var p = homePage
        val d0 = Dashboard(pages = listOf(p))
        apps.filter { it.isTvApp }.take(slots.size).forEach { app ->
            val spot = p.freeSpot(2, 1, d0) ?: return@forEach
            p = p.copy(tiles = p.tiles + Tile(type = TileType.APP, x = spot.first, y = spot.second, w = 2, h = 1, config = mapOf("pkg" to app.pkg)))
        }
        var appsPage = Page(name = "Apps")
        apps.forEach { app ->
            val spot = appsPage.freeSpot(2, 1, d0) ?: return@forEach
            appsPage = appsPage.copy(tiles = appsPage.tiles + Tile(type = TileType.APP, x = spot.first, y = spot.second, w = 2, h = 1, config = mapOf("pkg" to app.pkg)))
        }
        return Dashboard(
            pages = listOf(p, appsPage),
            theme = d0.theme.copy(background = Background(kind = BgKind.GRADIENT)),
        )
    }

    companion object {
        const val TAG = "CanvasTV"

        private var sharedLayout: androidx.compose.runtime.MutableState<Dashboard>? = null
        private var saveJob: Job? = null
        private val saveScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

        private val lenientJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * The saved layout for code outside the dashboard screen (the startup receiver and the
         * overlay service). Returns the live, shared copy once one exists, so changes made in
         * Settings reach the overlay right away. Null before the first save.
         */
        fun layoutState(ctx: android.content.Context): androidx.compose.runtime.MutableState<Dashboard>? = synchronized(this) {
            sharedLayout ?: run {
                val f = File(ctx.filesDir, "dashboard.json")
                val d = runCatching { lenientJson.decodeFromString(Dashboard.serializer(), f.readText()) }.getOrNull()
                    ?.takeIf { it.pages.isNotEmpty() } ?: return null
                mutableStateOf(d).also { sharedLayout = it }
            }
        }

        fun hashPin(pin: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest("canvastv:$pin".toByteArray())
                .joinToString("") { "%02x".format(it) }

        /** Pulls an imported layout into a valid shape: grid limits, tiles inside the grid, no overlaps. */
        fun sanitize(d: Dashboard): Dashboard {
            val cols = d.columns.coerceIn(4, 24)
            val rows = d.rows.coerceIn(3, 16)
            val base = d.copy(columns = cols, rows = rows)
            val pages = d.pages.map { p ->
                var kept = Page(id = p.id, name = p.name.ifBlank { "Page" })
                p.tiles.forEach { t ->
                    val w = t.w.coerceIn(1, cols); val h = t.h.coerceIn(1, rows)
                    val moved = t.copy(w = w, h = h, x = t.x.coerceIn(0, cols - w), y = t.y.coerceIn(0, rows - h))
                    if (kept.canPlace(moved, base)) kept = kept.copy(tiles = kept.tiles + moved)
                }
                kept
            }
            return base.copy(pages = pages.ifEmpty { listOf(Page(name = "Home")) })
        }
    }
}
