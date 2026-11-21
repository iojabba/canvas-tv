package com.canvastv.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import com.canvastv.dashboard.BuildConfigInfo
import com.canvastv.dashboard.data.Apps
import com.canvastv.dashboard.data.DashboardViewModel
import com.canvastv.dashboard.data.Backups
import com.canvastv.dashboard.data.Inputs
import com.canvastv.dashboard.data.NowPlaying
import com.canvastv.dashboard.data.NowPlayingState
import com.canvastv.dashboard.data.SavedLayout
import com.canvastv.dashboard.data.describeTime
import com.canvastv.dashboard.model.FocusEffect
import com.canvastv.dashboard.model.FontChoice
import com.canvastv.dashboard.model.Background
import com.canvastv.dashboard.model.BgKind
import com.canvastv.dashboard.model.ThemeMode
import com.canvastv.dashboard.model.Tile
import com.canvastv.dashboard.model.TileType
import com.canvastv.dashboard.model.AppSort
import com.canvastv.dashboard.ui.tiles.folderApps

// ---------------------------------------------------------------- shared building blocks

@Composable
fun Panel(title: String, onDismiss: () -> Unit, wide: Boolean = false, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(if (wide) 760.dp else 520.dp)
                .heightIn(max = 620.dp)
                .background(p.surface.copy(alpha = 0.97f), RoundedCornerShape(20.dp))
                .border(1.dp, p.onSurface.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                .padding(24.dp),
        ) {
            Text(title, fontSize = 24.sp)
            Spacer(Modifier.padding(6.dp))
            content()
        }
    }
}

@Composable
private fun ActionButton(label: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    if (primary) AppButton(onClick = onClick, modifier = modifier.fillMaxWidth()) { Text(label) }
    else AppOutlinedButton(onClick = onClick, modifier = modifier.fillMaxWidth()) { Text(label) }
}

/** A settings row whose button cycles through values on each press. */
@Composable
private fun CycleRow(label: String, value: String, onNext: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp, color = p.onSurface, modifier = Modifier.weight(1f))
        AppOutlinedButton(onClick = onNext, modifier = modifier.width(260.dp)) { Text(value, maxLines = 1) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 14.sp, color = LocalPalette.current.accentText, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

/**
 * Single-line text entry. It shows as a button so scrolling past it doesn't pop up the keyboard;
 * OK starts typing, and the keyboard's Done (or Back) saves and closes the keyboard.
 */
@Composable
private fun TextRow(label: String, initial: String, hint: String = "", secret: Boolean = false, onDone: (String) -> Unit) {
    val p = LocalPalette.current
    var value by remember(initial) { mutableStateOf(initial) }
    var typing by remember { mutableStateOf(false) }
    // Only a real loss of focus ends typing, not the "not focused yet" report the field gives when it first appears.
    var fieldFocused by remember { mutableStateOf(false) }
    // The OK press that starts typing lifts its key over the new field; only an OK pressed in the field finishes it.
    var okDownInField by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val button = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun finish() {
        if (!typing) return
        typing = false
        fieldFocused = false
        okDownInField = false
        keyboard?.hide()
        // An emptied secret keeps the saved one; there's a separate button to forget it.
        if (secret && value.isBlank()) { value = initial; return }
        onDone(value.trim())
    }
    LaunchedEffect(typing) {
        if (typing) { if (secret) value = ""; runCatching { field.requestFocus() }; keyboard?.show() } else runCatching { button.requestFocus() }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 14.sp, color = p.muted)
        if (!typing) {
            androidx.tv.material3.Surface(
                onClick = { typing = true },
                modifier = Modifier.fillMaxWidth().focusRequester(button),
                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent, contentColor = p.onSurface,
                    focusedContainerColor = p.onSurface, focusedContentColor = readableOn(p.onSurface),
                ),
                border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                    border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, p.onSurface.copy(alpha = 0.3f)), shape = RoundedCornerShape(10.dp)),
                ),
                scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Text(
                    if (secret && value.isNotEmpty()) "Saved (${value.length} characters) · press OK to replace" else value.ifEmpty { hint.ifEmpty { "Press OK to type" } },
                    fontSize = 16.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = if (value.isEmpty()) p.muted else Color.Unspecified,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        } else {
            Box(
                Modifier.fillMaxWidth()
                    .border(2.dp, p.accent, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (value.isEmpty() && hint.isNotEmpty()) Text(hint, fontSize = 16.sp, color = p.muted.copy(alpha = 0.6f))
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = TextStyle(color = p.onSurface, fontSize = 16.sp),
                    cursorBrush = SolidColor(p.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { finish() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(field)
                        .onPreviewKeyEvent { ev ->
                            val code = ev.nativeKeyEvent.keyCode
                            when {
                                code == android.view.KeyEvent.KEYCODE_DPAD_CENTER && ev.type == KeyEventType.KeyDown -> { okDownInField = true; true }
                                code == android.view.KeyEvent.KEYCODE_DPAD_CENTER && ev.type == KeyEventType.KeyUp -> { if (okDownInField) finish(); true }
                                code == android.view.KeyEvent.KEYCODE_BACK && ev.type == KeyEventType.KeyUp -> { finish(); true }
                                else -> code == android.view.KeyEvent.KEYCODE_BACK
                            }
                        }
                        .onFocusChanged {
                            if (it.isFocused) fieldFocused = true
                            else if (typing && fieldFocused) finish()
                        },
                )
            }
        }
    }
}

/**
 * Settings and Configure use a plain scrolling Column (every row stays composed), so the
 * highlight stays on its row when a change re-lays out the screen, such as a new text size.
 * These keep the list-style item { } blocks readable there.
 */
@Composable
private fun item(content: @Composable () -> Unit) = content()

@Composable
private fun <T> items(list: List<T>, content: @Composable (T) -> Unit) = list.forEach { content(it) }

private fun <T> List<T>.after(current: T): T = this[(indexOf(current) + 1).mod(size)]

// ---------------------------------------------------------------- tile actions

private val OPENABLE = setOf(TileType.APP, TileType.HDMI, TileType.VIDEO, TileType.APP_DRAWER, TileType.FOLDER, TileType.SHOW)

@Composable
fun TileActionsDialog(
    tile: Tile,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onMove: () -> Unit,
    onResize: () -> Unit,
    onConfigure: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    var confirmDelete by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Panel(tileTitle(ctx, tile), onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tile.type in OPENABLE) ActionButton("Open", Modifier.focusRequester(first), primary = true, onClick = onOpen)
            ActionButton("Move", if (tile.type in OPENABLE) Modifier else Modifier.focusRequester(first), onClick = onMove)
            ActionButton("Resize", onClick = onResize)
            ActionButton("Configure", onClick = onConfigure)
            ActionButton("Duplicate", onClick = onDuplicate)
            ActionButton(if (confirmDelete) "Press again to delete" else "Delete", onClick = { if (confirmDelete) onDelete() else confirmDelete = true })
        }
    }
}

/** A tile's name as a person would say it: the app, input, note or feed rather than its type. */
fun tileTitle(ctx: android.content.Context, t: Tile): String = when (t.type) {
    TileType.APP -> t.cfg("label").ifBlank { Apps.label(ctx, t.cfg("pkg")) }
    TileType.HDMI -> t.cfg("label", "HDMI input")
    TileType.NOTE -> t.cfg("text").take(40).ifBlank { "Note" }
    TileType.VIDEO, TileType.WEB, TileType.NEWS, TileType.CALENDAR -> t.cfg("title").ifBlank { t.type.label }
    TileType.FOLDER -> t.cfg("name").ifBlank { "Folder" }
    TileType.MESSAGES -> t.cfg("title").ifBlank { "Message board" }
    TileType.COUNTDOWN -> t.cfg("label").ifBlank { "Countdown" }
    TileType.WEATHER -> t.cfg("place").ifBlank { "Weather" }
    TileType.CLOCK -> t.cfg("city").ifBlank { "Clock" }
    TileType.HOME_ASSISTANT -> t.cfg("title").ifBlank { "Home Assistant" }
    TileType.SHOW -> t.cfg("title").ifBlank { com.canvastv.dashboard.data.Shows.serviceFor(t.cfg("link"))?.name ?: "TV show" }
    else -> t.type.label
}.let { if (it == t.type.label) it else "$it · ${t.type.label}" }

// ---------------------------------------------------------------- add tile

@Composable
fun AddTileDialog(onDismiss: () -> Unit, onAdd: (TileType, Map<String, String>) -> Unit) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf<TileType?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(picking) { runCatching { first.requestFocus() } }
    when (picking) {
        TileType.APP -> {
            val apps = remember { Apps.list(ctx) }
            Panel("Choose an app", onDismiss) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(apps.size) { i ->
                        val a = apps[i]
                        ActionButton(a.label + if (a.isTvApp) "" else "  (phone app)", if (i == 0) Modifier.focusRequester(first) else Modifier) {
                            onAdd(TileType.APP, mapOf("pkg" to a.pkg))
                        }
                    }
                }
            }
        }
        TileType.HDMI -> {
            val inputs = remember { Inputs.list(ctx) }
            Panel("Choose an input", onDismiss) {
                if (inputs.isEmpty()) Text("This TV doesn't list any HDMI inputs for apps.", color = LocalPalette.current.muted)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    inputs.forEachIndexed { i, inp ->
                        ActionButton(inp.label, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                            onAdd(TileType.HDMI, mapOf("inputId" to inp.id, "label" to inp.label))
                        }
                    }
                }
            }
        }
        TileType.SHOW -> ShowSearchPanel(onDismiss, first) { cfg -> onAdd(TileType.SHOW, cfg) }
        else -> Panel("Add a tile", onDismiss) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The most-used kinds first.
                val order = listOf(TileType.SHOW, TileType.APP) + TileType.entries.filter { it != TileType.SHOW && it != TileType.APP }
                items(order.size) { i ->
                    val t = order[i]
                    ActionButton("${t.label}  (${t.defaultW}×${t.defaultH})", if (i == 0) Modifier.focusRequester(first) else Modifier) {
                        if (t == TileType.APP || t == TileType.HDMI || t == TileType.SHOW) picking = t else onAdd(t, emptyMap())
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- configure tile

private val Zones = listOf("" to "Local time", "America/Los_Angeles" to "Los Angeles", "America/Denver" to "Denver",
    "America/Chicago" to "Chicago", "America/New_York" to "New York", "Europe/London" to "London",
    "Europe/Paris" to "Paris", "Asia/Tokyo" to "Tokyo", "Australia/Sydney" to "Sydney")
private val TileColors = listOf<String?>(null, "#1E3A5F", "#3B1F4A", "#1F4A3B", "#4A3B1F", "#000000", "#FFFFFF")

@Composable
fun ConfigureTileDialog(tile: Tile, onChange: (Tile) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    fun set(k: String, v: String) = onChange(tile.withCfg(k, v))
    fun yesNo(b: Boolean) = if (b) "On" else "Off"
    var pickingHa by remember { mutableStateOf(false) }
    if (pickingHa) { HaPickerDialog(tile, onChange, onDismiss = { pickingHa = false }); return }
    Panel("Configure ${tile.type.label.lowercase()}", onDismiss, wide = true) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            item { SectionLabel("Content") }
            when (tile.type) {
                TileType.CLOCK -> {
                    item { CycleRow("Style", if (tile.cfg("style") == "analog") "Analog" else "Digital",
                        { set("style", if (tile.cfg("style") == "analog") "digital" else "analog") }, Modifier.focusRequester(first)) }
                    item { CycleRow("24-hour", yesNo(tile.cfgBool("24h", false)), { set("24h", (!tile.cfgBool("24h", false)).toString()) }) }
                    item { CycleRow("Show date", yesNo(tile.cfgBool("showDate", true)), { set("showDate", (!tile.cfgBool("showDate", true)).toString()) }) }
                    item {
                        val cur = Zones.firstOrNull { it.first == tile.cfg("timezone") } ?: Zones[0]
                        CycleRow("Time zone", cur.second, {
                            val next = Zones.after(cur)
                            onChange(tile.withCfg("timezone", next.first).withCfg("city", if (next.first.isBlank()) "" else next.second))
                        })
                    }
                }
                TileType.APP -> {
                    item { CycleRow("Show as", if (tile.cfg("display") == "text") "Plain text" else "Banner / icon",
                        { set("display", if (tile.cfg("display") == "text") "art" else "text") }, Modifier.focusRequester(first)) }
                    item { CycleRow("Label on banner", yesNo(tile.cfgBool("showLabel", false)), { set("showLabel", (!tile.cfgBool("showLabel", false)).toString()) }) }
                    item { TextRow("Custom label", tile.cfg("label"), Apps.label(ctx, tile.cfg("pkg"))) { set("label", it) } }
                }
                TileType.HDMI -> {
                    item {
                        val inputs = Inputs.list(ctx)
                        val cur = inputs.firstOrNull { it.id == tile.cfg("inputId") }
                        CycleRow("Input", cur?.label ?: tile.cfg("label", "Choose…"), {
                            if (inputs.isNotEmpty()) {
                                val next = if (cur == null) inputs[0] else inputs.after(cur)
                                onChange(tile.withCfg("inputId", next.id).withCfg("label", next.label))
                            }
                        }, Modifier.focusRequester(first))
                    }
                    item { CycleRow("Live preview in tile", yesNo(tile.cfgBool("preview", true)), { set("preview", (!tile.cfgBool("preview", true)).toString()) }) }
                    item { SectionLabel("Full screen (game mode)") }
                    item { TextRow("Corner video URL (IPTV, Plex/Jellyfin, camera, MP4)", tile.cfg("cornerUrl"), "Leave empty for none") { set("cornerUrl", it) } }
                    item { CycleRow("Corner clock", yesNo(tile.cfgBool("cornerClock", true)), { set("cornerClock", (!tile.cfgBool("cornerClock", true)).toString()) }) }
                    item {
                        val pos = listOf("BR" to "Bottom right", "BL" to "Bottom left", "TR" to "Top right", "TL" to "Top left")
                        val cur = pos.firstOrNull { it.first == tile.cfg("cornerPos", "BR") } ?: pos[0]
                        CycleRow("Corner position", cur.second, { set("cornerPos", pos.after(cur).first) })
                    }
                    item {
                        val sizes = listOf("S", "M", "L")
                        val cur = tile.cfg("cornerSize", "M").takeIf { it in sizes } ?: "M"
                        CycleRow("Corner size", cur, { set("cornerSize", sizes.after(cur)) })
                    }
                    item { CycleRow("Corner video sound", yesNo(!tile.cfgBool("cornerMuted", true)), { set("cornerMuted", (!tile.cfgBool("cornerMuted", true)).toString()) }) }
                }
                TileType.NOTE -> {
                    item { TextRow("Text", tile.cfg("text"), "Type a note") { set("text", it) } }
                    item {
                        val sizes = listOf("S", "M", "L", "XL")
                        val cur = tile.cfg("size", "M").takeIf { it in sizes } ?: "M"
                        CycleRow("Text size", cur, { set("size", sizes.after(cur)) }, Modifier.focusRequester(first))
                    }
                }
                TileType.VIDEO -> {
                    item { CycleRow("Sound", yesNo(!tile.cfgBool("muted", true)), { set("muted", (!tile.cfgBool("muted", true)).toString()) }, Modifier.focusRequester(first)) }
                    item { TextRow("Stream URL", tile.cfg("url"), "https://… or rtsp://…") { set("url", it) } }
                    item { TextRow("Title", tile.cfg("title"), "Optional") { set("title", it) } }
                }
                TileType.COUNTDOWN -> {
                    item { TextRow("Label", tile.cfg("label"), "Vacation, Birthday, Game day…") { set("label", it) } }
                    item { TextRow("Date (year-month-day)", tile.cfg("date"), "2026-12-25") { v ->
                        if (v.isBlank() || runCatching { java.time.LocalDate.parse(v) }.isSuccess) set("date", v) else onChange(tile) } }
                    item { TextRow("Time (24-hour, optional)", tile.cfg("time"), "18:30") { v ->
                        if (v.isBlank() || runCatching { java.time.LocalTime.parse(v) }.isSuccess) set("time", v) } }
                    item { CycleRow("Repeats every year", yesNo(tile.cfgBool("yearly", false)), { set("yearly", (!tile.cfgBool("yearly", false)).toString()) }, Modifier.focusRequester(first)) }
                }
                TileType.APP_DRAWER -> {
                    item { TextRow("Label", tile.cfg("label"), "All apps") { set("label", it) } }
                    item { Text("Sorting and hidden apps are set inside the drawer (hold OK on an app).", color = LocalPalette.current.muted, fontSize = 13.sp, modifier = Modifier.focusRequester(first)) }
                }
                TileType.FOLDER -> {
                    val pkgs = folderApps(tile)
                    item { TextRow("Folder name", tile.cfg("name"), "Games, Kids, Music…") { set("name", it) } }
                    item { SectionLabel("Apps in this folder") }
                    if (pkgs.isEmpty()) item { Text("No apps yet.", color = LocalPalette.current.muted, fontSize = 14.sp) }
                    items(pkgs) { pkg ->
                        CycleRow(Apps.label(ctx, pkg), "Remove", { set("pkgs", (pkgs - pkg).joinToString(",")) })
                    }
                    item {
                        var adding by remember { mutableStateOf(false) }
                        ActionButton("Add an app", Modifier.focusRequester(first)) { adding = true }
                        if (adding) AppPickerDialog(exclude = pkgs.toSet(), onDismiss = { adding = false }) { pkg ->
                            set("pkgs", (pkgs + pkg).joinToString(",")); adding = false
                        }
                    }
                }
                TileType.WEATHER -> {
                    item { TextRow("City", tile.cfg("place"), "Seattle, Washington") { set("place", it) } }
                    item { CycleRow("Units", if (tile.cfg("units", "F") == "F") "°F, mph" else "°C, km/h", { set("units", if (tile.cfg("units", "F") == "F") "C" else "F") }, Modifier.focusRequester(first)) }
                    item {
                        val mins = listOf("15", "30", "60", "180")
                        val cur = tile.cfg("refreshMin", "30").takeIf { it in mins } ?: "30"
                        CycleRow("Refresh every", "$cur min", { set("refreshMin", mins.after(cur)) })
                    }
                    item { Text("Weather from Open-Meteo. The last forecast is kept and shown when the TV is offline.", color = LocalPalette.current.muted, fontSize = 13.sp) }
                }
                TileType.NEWS -> {
                    item { TextRow("Feed addresses (comma separated)", tile.cfg("urls"), "https://feeds.npr.org/1001/rss.xml") { set("urls", it) } }
                    item { TextRow("Title", tile.cfg("title"), "News") { set("title", it) } }
                    item { CycleRow("Style", if (tile.cfg("style") == "ticker") "Scrolling ticker" else "Headline list", { set("style", if (tile.cfg("style") == "ticker") "list" else "ticker") }, Modifier.focusRequester(first)) }
                    item { RefreshRow(tile, listOf("5", "15", "30", "60"), "15", onChange) }
                }
                TileType.TICKER -> {
                    item { TextRow("Symbols (comma separated)", tile.cfg("symbols", "BTC, ETH, AAPL, MSFT"), "AAPL, MSFT, BTC, ETH, ^SPX") { set("symbols", it) } }
                    item { RefreshRow(tile, listOf("5", "10", "30", "60"), "10", onChange, Modifier.focusRequester(first)) }
                    item { Text("Stocks from Stooq (US symbols, delayed), crypto from CoinGecko: BTC, ETH, SOL, DOGE, XRP, ADA, LTC, DOT, AVAX, LINK. Change is since today's open for stocks and over 24 hours for crypto.", color = LocalPalette.current.muted, fontSize = 13.sp) }
                }
                TileType.CALENDAR -> {
                    item { TextRow("Private iCal address (comma separate several)", tile.cfg("icsUrls"), "https://calendar.google.com/calendar/ical/…/basic.ics") { set("icsUrls", it) } }
                    item { TextRow("Title", tile.cfg("title"), "Coming up") { set("title", it) } }
                    item {
                        val ds = listOf("1", "3", "7", "14", "30")
                        val cur = tile.cfg("days", "7").takeIf { it in ds } ?: "7"
                        CycleRow("Show the next", "$cur days", { set("days", ds.after(cur)) }, Modifier.focusRequester(first))
                    }
                    item { RefreshRow(tile, listOf("15", "30", "60"), "30", onChange) }
                    item { Text("Google Calendar: on a computer open Settings → pick the calendar → \"Secret address in iCal format\". Anyone with that address can read the calendar.", color = LocalPalette.current.muted, fontSize = 13.sp) }
                }
                TileType.PHOTOS -> {
                    item { TextRow("Picture or feed addresses (comma separated)", tile.cfg("sources"), "https://…/photo.jpg, https://…/feed.rss") { set("sources", it) } }
                    item {
                        val secs = listOf("5", "10", "15", "30", "60", "300")
                        val cur = tile.cfg("seconds", "15").takeIf { it in secs } ?: "15"
                        CycleRow("Change every", "$cur s", { set("seconds", secs.after(cur)) }, Modifier.focusRequester(first))
                    }
                    item { CycleRow("Shuffle", yesNo(tile.cfgBool("shuffle", true)), { set("shuffle", (!tile.cfgBool("shuffle", true)).toString()) }) }
                    item { CycleRow("Picture fit", if (tile.cfg("fit") == "fit") "Whole picture" else "Fill the tile", { set("fit", if (tile.cfg("fit") == "fit") "fill" else "fit") }) }
                    item { Text("Google Photos albums can't be read directly by apps; a shared album exported to a feed, a Flickr feed, or a list of picture links works.", color = LocalPalette.current.muted, fontSize = 13.sp) }
                }
                TileType.WEB -> {
                    item { TextRow("Web address", tile.cfg("url"), "https://…") { set("url", it) } }
                    item { TextRow("Title", tile.cfg("title"), "Optional") { set("title", it) } }
                    item {
                        val zs = listOf("50", "75", "100", "125", "150")
                        val cur = tile.cfg("zoom", "100").takeIf { it in zs } ?: "100"
                        CycleRow("Zoom", "$cur%", { set("zoom", zs.after(cur)) }, Modifier.focusRequester(first))
                    }
                    item { RefreshRow(tile, listOf("1", "5", "15", "60"), "15", onChange, label = "Reload every") }
                    item { CycleRow("JavaScript", yesNo(tile.cfgBool("js", true)), { set("js", (!tile.cfgBool("js", true)).toString()) }) }
                }
                TileType.MESSAGES -> {
                    item { TextRow("Title", tile.cfg("title"), "Messages") { set("title", it) } }
                    item { Text("Shows messages sent from the phone page or web commands (Settings → Phone setup).", color = LocalPalette.current.muted, fontSize = 13.sp, modifier = Modifier.focusRequester(first)) }
                }
                TileType.SHOW -> {
                    item { Box(Modifier.focusRequester(first)) { TextRow("Show link", tile.cfg("link"), "https://www.netflix.com/title/…") { set("link", com.canvastv.dashboard.data.Shows.normalize(it)) } } }
                    item { TextRow("Name", tile.cfg("title"), "Taken from the show's page") { set("title", it) } }
                    item { TextRow("Picture address", tile.cfg("image"), "Taken from the show's page") { set("image", it) } }
                    item { CycleRow("Show the name", yesNo(tile.cfgBool("showTitle", true)), { set("showTitle", (!tile.cfgBool("showTitle", true)).toString()) }) }
                    item { CycleRow("Show the app's icon", yesNo(tile.cfgBool("showApp", true)), { set("showApp", (!tile.cfgBool("showApp", true)).toString()) }) }
                    item { CycleRow("\"NEW\" badge for new episodes", yesNo(tile.cfgBool("newBadge", true)), { set("newBadge", (!tile.cfgBool("newBadge", true)).toString()) }) }
                    item { CycleRow("Dim it when there's nothing new", yesNo(tile.cfgBool("dimOld", true)), { set("dimOld", (!tile.cfgBool("dimOld", true)).toString()) }) }
                    item { TextRow("TVmaze show number (if it finds the wrong show)", tile.cfg("tvmaze"), "Found by name") { set("tvmaze", it.filter(Char::isDigit)) } }
                    item {
                        val svc = com.canvastv.dashboard.data.Shows.serviceFor(tile.cfg("link"))
                        val installed = remember { Apps.list(ctx).filter { it.isTvApp } }
                        val choices = listOf("") + installed.map { it.pkg }
                        val cur = tile.cfg("pkg").takeIf { it in choices } ?: ""
                        CycleRow("Opens in", if (cur.isEmpty()) "Automatic" + (svc?.let { " (${it.name})" } ?: "") else Apps.label(ctx, cur), { set("pkg", choices.after(cur)) })
                    }
                    item { Text("OK on the tile opens the show in its app. If the app can't open that show, the app opens on its home page instead.", color = LocalPalette.current.muted, fontSize = 13.sp) }
                }
                TileType.HOME_ASSISTANT -> {
                    item { ActionButton("Choose devices… (${com.canvastv.dashboard.ui.tiles.haIds(tile).size} chosen)", Modifier.focusRequester(first), primary = true) { pickingHa = true } }
                    item { TextRow("Entity IDs (comma separated)", tile.cfg("entities"), "light.living_room, scene.movie_time") { set("entities", it) } }
                    item { TextRow("Title", tile.cfg("title"), "Home") { set("title", it) } }
                    item {
                        val secs = listOf("10", "30", "60", "300")
                        val cur = tile.cfg("refreshSec", "30").takeIf { it in secs } ?: "30"
                        CycleRow("Refresh every", "$cur s", { set("refreshSec", secs.after(cur)) })
                    }
                    item { Text("OK on the tile opens its controls. A tile with one light, switch or scene toggles it directly. Locks and garage doors always ask twice. The server address and token are in Settings → Home Assistant.", color = LocalPalette.current.muted, fontSize = 13.sp) }
                }
                TileType.SPEEDTEST -> item { Text("Uses Cloudflare's speed test servers (about 30 MB per test). Press OK on the tile to run it.", color = LocalPalette.current.muted, modifier = Modifier.focusRequester(first)) }
                TileType.NOW_PLAYING -> item { Text("Shows what's playing on the TV. OK plays or pauses; hold OK for more controls.", color = LocalPalette.current.muted, modifier = Modifier.focusRequester(first)) }
                TileType.STATS -> item { Text("Shows memory, storage, network speed, IP address and uptime.", color = LocalPalette.current.muted, modifier = Modifier.focusRequester(first)) }
            }
            item { SectionLabel("Style") }
            item {
                val radii = listOf(0, 8, 18, 28)
                CycleRow("Corner radius", "${tile.style.cornerDp} dp", { onChange(tile.copy(style = tile.style.copy(cornerDp = radii.after(tile.style.cornerDp.takeIf { it in radii } ?: 18)))) })
            }
            item {
                val ops = listOf(0.25f, 0.45f, 0.6f, 0.72f, 0.85f, 1f)
                CycleRow("Opacity", "${(tile.style.opacity * 100).toInt()}%", { onChange(tile.copy(style = tile.style.copy(opacity = ops.after(ops.minBy { kotlin.math.abs(it - tile.style.opacity) })))) })
            }
            item { CycleRow("Border", yesNo(tile.style.border), { onChange(tile.copy(style = tile.style.copy(border = !tile.style.border))) }) }
            item {
                CycleRow("Tile color", tile.style.colorHex ?: "Theme", { onChange(tile.copy(style = tile.style.copy(colorHex = TileColors.after(tile.style.colorHex.takeIf { it in TileColors })))) })
            }
            item { Spacer(Modifier.padding(6.dp)); ActionButton("Done", primary = true, onClick = onDismiss) }
        }
    }
}

// ---------------------------------------------------------------- settings

@Composable
fun SettingsDialog(vm: DashboardViewModel, onDismiss: () -> Unit) {
    val d = vm.dashboard
    val t = d.theme
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmRemovePin by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf("Minimal") }
    var settingPin by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    if (settingPin) { SetPinDialog(onDismiss = { settingPin = false }, onSet = { vm.setPin(it); settingPin = false }); return }
    if (restoring) { RestoreDialog(vm, onDismiss = { restoring = false }); return }
    fun theme(block: (com.canvastv.dashboard.model.ThemeSpec) -> com.canvastv.dashboard.model.ThemeSpec) = vm.replaceDashboard(d.copy(theme = block(t)))
    fun bg(block: (Background) -> Background) = theme { it.copy(background = block(it.background)) }

    Panel("Settings", onDismiss, wide = true) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            item { SectionLabel("Theme") }
            item { CycleRow("Mode", t.mode.label, { theme { it.copy(mode = ThemeMode.entries.after(it.mode)) } }, Modifier.focusRequester(first)) }
            item { CycleRow("Accent color", t.accentHex, { theme { it.copy(accentHex = AccentChoices.after(it.accentHex.takeIf { h -> h in AccentChoices } ?: AccentChoices[0])) } }) }
            item {
                val names = BackgroundPresets.map { it.first } + listOf("Solid black", "Image from URL", "Bing photo of the day")
                val cur = when (t.background.kind) {
                    BgKind.IMAGE -> "Image from URL"
                    BgKind.BING -> "Bing photo of the day"
                    BgKind.SOLID -> "Solid black"
                    BgKind.GRADIENT -> BackgroundPresets.firstOrNull { it.second == t.background.color1 && it.third == t.background.color2 }?.first ?: names[0]
                }
                CycleRow("Background", cur, {
                    when (val next = names.after(cur)) {
                        "Solid black" -> bg { it.copy(kind = BgKind.SOLID, color1 = "#000000") }
                        "Image from URL" -> bg { it.copy(kind = BgKind.IMAGE) }
                        "Bing photo of the day" -> bg { it.copy(kind = BgKind.BING, dim = maxOf(it.dim, 0.25f)) }
                        else -> BackgroundPresets.first { it.first == next }.let { pr -> bg { it.copy(kind = BgKind.GRADIENT, color1 = pr.second, color2 = pr.third) } }
                    }
                })
            }
            if (t.background.kind == BgKind.IMAGE) item { TextRow("Background image URL", t.background.imageUrl, "https://…/photo.jpg") { u -> bg { it.copy(imageUrl = u) } } }
            item { CycleRow("Accent from background picture", if (t.autoAccent) "On" else "Off", { theme { it.copy(autoAccent = !it.autoAccent) } }) }
            item { CycleRow("Holiday colors (automatic)", if (t.seasonal) "On" else "Off", { theme { it.copy(seasonal = !it.seasonal) } }) }
            item {
                val names = listOf("Off") + com.canvastv.dashboard.data.GameDayColors.map { it.name }
                CycleRow("Game day colors", names[t.gameDayColors + 1], { theme { it.copy(gameDayColors = if (it.gameDayColors + 1 >= com.canvastv.dashboard.data.GameDayColors.size) -1 else it.gameDayColors + 1) } })
            }
            if (t.gameDayColors >= 0) item {
                val sets = listOf(listOf(7) to "Sundays", listOf(1) to "Mondays", listOf(4) to "Thursdays", listOf(6) to "Saturdays",
                    listOf(6, 7) to "Weekends", listOf(1, 4, 7) to "Mon, Thu, Sun", (1..7).toList() to "Every day")
                val cur = sets.firstOrNull { it.first == t.gameDays } ?: sets[0]
                CycleRow("Game days", cur.second, { theme { it.copy(gameDays = sets.after(cur).first) } })
            }
            item {
                val dims = listOf(0f, 0.25f, 0.5f, 0.7f)
                CycleRow("Background dimming", "${(t.background.dim * 100).toInt()}%", { bg { it.copy(dim = dims.after(dims.minBy { x -> kotlin.math.abs(x - it.dim) })) } })
            }
            item {
                val scales = listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 1.75f)
                CycleRow("Text size", "${(t.fontScale * 100).toInt()}%", { theme { it.copy(fontScale = scales.after(scales.minBy { x -> kotlin.math.abs(x - it.fontScale) })) } })
            }
            item {
                val zooms = listOf(1f, 1.03f, 1.06f, 1.1f)
                CycleRow("Focus zoom", if (t.focusScale <= 1f) "Off" else "${((t.focusScale - 1) * 100).toInt()}%", { theme { it.copy(focusScale = zooms.after(zooms.minBy { x -> kotlin.math.abs(x - it.focusScale) })) } })
            }
            item { CycleRow("Focus effect", t.focusEffect.label, { theme { it.copy(focusEffect = FocusEffect.entries.after(it.focusEffect)) } }) }
            item { CycleRow("Font", t.font.label, { theme { it.copy(font = FontChoice.entries.after(it.font)) } }) }
            item { CycleRow("High contrast", if (t.highContrast) "On" else "Off", { theme { it.copy(highContrast = !it.highContrast) } }) }

            item { SectionLabel("Layout") }
            item {
                val cols = listOf(8, 10, 12, 16)
                CycleRow("Grid columns", "${d.columns}", { vm.replaceDashboard(regrid(d, cols.after(d.columns.takeIf { it in cols } ?: 12), d.rows)) })
            }
            item {
                val rows = listOf(4, 5, 6, 8)
                CycleRow("Grid rows", "${d.rows}", { vm.replaceDashboard(regrid(d, d.columns, rows.after(d.rows.takeIf { it in rows } ?: 6))) })
            }
            item {
                val ov = listOf(0f, 2.5f, 5f)
                CycleRow("Overscan margin", "${d.overscanPct}%", { vm.replaceDashboard(d.copy(overscanPct = ov.after(ov.minBy { kotlin.math.abs(it - d.overscanPct) }))) })
            }
            item {
                val timers = listOf(0, 15, 30, 60, 120)
                CycleRow("Switch pages every", if (d.pageTimerSec == 0) "Off" else "${d.pageTimerSec} s", { vm.replaceDashboard(d.copy(pageTimerSec = timers.after(d.pageTimerSec.takeIf { it in timers } ?: 0))) })
            }
            item { TextRow("Name of this page", vm.page.name) { vm.renamePage(it) } }

            item { SectionLabel("Performance") }
            item { CycleRow("Lightweight mode", if (d.lightweight) "On" else "Off", { vm.setLightweight(!d.lightweight) }) }
            item { Text("Turns off live HDMI and video previews in tiles, zoom, glow and background images. Full screen still works.", color = LocalPalette.current.muted, fontSize = 13.sp) }

            item { SectionLabel("Edit lock") }
            item {
                if (d.pinHash == null) ActionButton("Set a PIN for edit mode and Settings") { settingPin = true }
                else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Change PIN") { settingPin = true }
                    ActionButton(if (confirmRemovePin) "Press again to remove the PIN" else "Remove PIN") {
                        if (confirmRemovePin) { vm.setPin(null); confirmRemovePin = false } else confirmRemovePin = true
                    }
                }
            }

            item { SectionLabel("Home button") }
            item {
                val ctx = LocalContext.current
                val p = LocalPalette.current
                var refresh by remember { mutableStateOf(0) }
                val on = remember(refresh) { com.canvastv.dashboard.service.HomeHelper.enabled(ctx) }
                val canSwitch = remember { com.canvastv.dashboard.service.HomeHelper.canSwitch(ctx) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canSwitch) {
                        CycleRow("Open Canvas TV on Home and at startup", if (on) "On" else "Off", {
                            com.canvastv.dashboard.service.HomeHelper.setEnabled(ctx, !on); refresh++
                        })
                        Text("Canvas TV keeps this on by itself after restarts and updates. Turn it off here (not in the TV's Accessibility settings) to get the Google TV home back.", color = p.muted, fontSize = 13.sp)
                    } else
                    Text(
                        if (on) "The Home button helper is on: Canvas TV opens at startup and when you press Home."
                        else "This TV keeps Google TV as its home screen. To open Canvas TV at startup and on Home, turn on \"Canvas TV Home button\" in the TV's Accessibility settings. If another launcher's helper (like Projectivy) is on there, turn it off so they don't fight over Home.",
                        color = if (on) p.onSurface else p.muted, fontSize = 13.sp,
                    )
                    if (!canSwitch) Text("To have Canvas TV switch it back on by itself after restarts, run once from a computer: adb shell pm grant com.canvastv.dashboard android.permission.WRITE_SECURE_SETTINGS", color = p.muted, fontSize = 12.sp)
                    ActionButton("Open the TV's Accessibility settings") { com.canvastv.dashboard.service.HomeHelper.openAccessibilitySettings(ctx) }
                    if (on) ActionButton("Go to the Google TV home once") { com.canvastv.dashboard.service.HomeHelper.openGoogleTvHome(ctx) }
                }
            }

            item { SectionLabel("Startup and overlay") }
            item { CycleRow("Open Canvas TV when the TV turns on", if (d.openOnStartup) "On" else "Off", { vm.setOpenOnStartup(!d.openOnStartup) }) }
            item { CycleRow("Overlay over other apps", d.overlay.mode.label, { vm.setOverlay(d.overlay.copy(mode = com.canvastv.dashboard.model.OverlayMode.entries.after(d.overlay.mode))) }) }
            if (d.overlay.mode != com.canvastv.dashboard.model.OverlayMode.OFF) {
                item { CycleRow("Overlay corner", d.overlay.corner.label, { vm.setOverlay(d.overlay.copy(corner = com.canvastv.dashboard.model.Corner.entries.after(d.overlay.corner))) }) }
                item {
                    val ops = listOf(0.5f, 0.65f, 0.8f, 0.95f)
                    CycleRow("Overlay opacity", "${(d.overlay.opacity * 100).toInt()}%", { vm.setOverlay(d.overlay.copy(opacity = ops.after(ops.minBy { kotlin.math.abs(it - d.overlay.opacity) }))) })
                }
                if (d.overlay.mode == com.canvastv.dashboard.model.OverlayMode.CLOCK_WEATHER) {
                    item { TextRow("Overlay weather city", d.overlay.weatherPlace, "Uses your weather tile's city") { vm.setOverlay(d.overlay.copy(weatherPlace = it)) } }
                    item { CycleRow("Overlay temperature", if (d.overlay.fahrenheit) "°F" else "°C", { vm.setOverlay(d.overlay.copy(fahrenheit = !d.overlay.fahrenheit)) }) }
                }
            }
            item {
                val ctx = LocalContext.current
                val p = LocalPalette.current
                val needs = d.openOnStartup || d.overlay.mode != com.canvastv.dashboard.model.OverlayMode.OFF
                val allowed = remember(d.openOnStartup, d.overlay.mode) { android.provider.Settings.canDrawOverlays(ctx) }
                Column {
                    Text("The overlay shows a small card over other apps and is hidden while the dashboard is open. New messages from your phone pop up on it.", color = p.muted, fontSize = 13.sp)
                    if (needs && !allowed) {
                        Text("Needs the \"display over other apps\" permission, which this TV only grants from a computer:", color = Color(0xFFFFB74D), fontSize = 13.sp)
                        Text("adb shell appops set com.canvastv.dashboard SYSTEM_ALERT_WINDOW allow", color = if (p.dark) Color(0xFF9EE493) else Color(0xFF1B6E2A), fontSize = 12.sp)
                    }
                }
            }

            item { SectionLabel("Home Assistant") }
            item { TextRow("Server address", d.homeAssistant.url, "http://192.168.1.20:8123") { vm.setHomeAssistant(d.homeAssistant.copy(url = it)) } }
            item { TextRow("Long-lived access token", d.homeAssistant.token, "From your Home Assistant profile → Security", secret = true) { vm.setHomeAssistant(d.homeAssistant.copy(token = it)) } }
            if (d.homeAssistant.configured) item {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                var result by remember { mutableStateOf<String?>(null) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Test connection") {
                        result = "Checking…"
                        scope.launch { result = com.canvastv.dashboard.data.HomeAssistant.test(d.homeAssistant) ?: "Connected to Home Assistant" }
                    }
                    result?.let { Text(it, color = LocalPalette.current.onSurface, fontSize = 14.sp) }
                    ActionButton("Forget the token") { vm.setHomeAssistant(d.homeAssistant.copy(token = "")) }
                }
            }
            item { Text("The token is a long string, so it's easiest to paste from the phone setup page or from a computer (see About). It stays on this TV and isn't shown to phones.", color = LocalPalette.current.muted, fontSize = 13.sp) }

            item { SectionLabel("Phone setup") }
            item { CycleRow("Set up from a phone or computer", if (d.phoneSetup) "On" else "Off", { vm.setPhoneSetup(!d.phoneSetup) }) }
            if (d.phoneSetup) item {
                val p = LocalPalette.current
                val running = com.canvastv.dashboard.data.CompanionServer.running
                Column {
                    if (running) {
                        Text("On a phone on the same Wi-Fi, open:", color = p.muted, fontSize = 13.sp)
                        Text(com.canvastv.dashboard.data.CompanionServer.address() ?: "(no network)", color = p.onSurface, fontSize = 22.sp)
                        Text("Pairing code: ${com.canvastv.dashboard.data.CompanionServer.pairingCode}", color = p.accentText, fontSize = 22.sp)
                    } else Text("Couldn't start the phone page (port ${com.canvastv.dashboard.data.CompanionServer.PORT} is busy). Try turning it off and on.", color = Color(0xFFFF8A80), fontSize = 13.sp)
                    Spacer(Modifier.padding(4.dp))
                    ActionButton("Forget paired phones and tokens") { com.canvastv.dashboard.data.CompanionServer.resetPairing(); vm.message = "Paired phones forgotten. New code shown." }
                }
            }

            item { SectionLabel("Backup and restore") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Save a backup") { vm.exportBackup() }
                    ActionButton("Restore a layout or earlier version…") { restoring = true }
                }
            }
            item { Text("Backups are saved to ${Backups.ADB_PATH} (copy them with adb pull).", color = LocalPalette.current.muted, fontSize = 13.sp) }

            item { SectionLabel("Presets") }
            item {
                val presets = listOf("Minimal", "Info Board", "Media Hub")
                CycleRow("Preset", preset, { preset = presets.after(preset); confirmReset = false })
            }
            item {
                ActionButton(if (confirmReset) "Press again to replace your layout" else "Use the $preset preset") {
                    if (confirmReset) { vm.resetToPreset(preset); onDismiss() } else confirmReset = true
                }
            }
            item {
                Text(when (preset) {
                    "Info Board" -> "Clock, weather, countdown, calendar, headlines and a market ticker, plus an Apps page."
                    "Media Hub" -> "Now playing, your console on HDMI, streaming apps, a folder for the rest and the app drawer."
                    else -> "Clock, stats, your console and your TV apps, plus an Apps page. Also resets the look."
                } + " Your current layout is kept in version history.", color = LocalPalette.current.muted, fontSize = 13.sp)
            }

            item { SectionLabel("About") }
            item {
                val p = LocalPalette.current
                val cmd = if (p.dark) Color(0xFF9EE493) else Color(0xFF1B6E2A)
                Column {
                    Text("Canvas TV ${BuildConfigInfo.VERSION}", color = p.onSurface, fontSize = 15.sp)
                    Text("Remote: Menu toggles edit mode · long-press OK edits a tile · Channel up/down switches pages.", color = p.muted, fontSize = 13.sp)
                    Text("To make this your home screen, run from a computer:", color = p.muted, fontSize = 13.sp)
                    Text("adb shell cmd package set-home-activity com.canvastv.dashboard/.ui.MainActivity", color = cmd, fontSize = 12.sp)
                    Text("To let the Now Playing tile see what's playing, run once:", color = p.muted, fontSize = 13.sp)
                    Text("adb shell cmd notification allow_listener com.canvastv.dashboard/com.canvastv.dashboard.data.MediaListener", color = cmd, fontSize = 12.sp)
                    Text("To set up Home Assistant from a computer, run:", color = p.muted, fontSize = 13.sp)
                    Text("adb shell am start -n com.canvastv.dashboard/.ui.MainActivity --es haUrl http://IP:8123 --es haToken TOKEN", color = cmd, fontSize = 12.sp)
                    Text("Forgot the edit PIN? Run:", color = p.muted, fontSize = 13.sp)
                    Text("adb shell am start -n com.canvastv.dashboard/.ui.MainActivity --ez clearPin true", color = cmd, fontSize = 12.sp)
                }
            }
            item { Spacer(Modifier.padding(6.dp)); ActionButton("Close", primary = true, onClick = onDismiss) }
        }
    }
}

/** Changes the grid size and pulls tiles back inside it. */
private fun regrid(d: com.canvastv.dashboard.model.Dashboard, cols: Int, rows: Int) = d.copy(
    columns = cols, rows = rows,
    pages = d.pages.map { p ->
        p.copy(tiles = p.tiles.map { t ->
            val w = t.w.coerceAtMost(cols); val h = t.h.coerceAtMost(rows)
            t.copy(w = w, h = h, x = t.x.coerceAtMost(cols - w), y = t.y.coerceAtMost(rows - h))
        })
    },
)

// ---------------------------------------------------------------- PIN pad

/**
 * A 4-digit PIN pad. Works with the on-screen keys or the remote's number buttons.
 * [onPin] returns null when the PIN is accepted, or a message to show and start over.
 */
@Composable
fun PinDialog(title: String, onDismiss: () -> Unit, onPin: (String) -> String?) {
    val p = LocalPalette.current
    var digits by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    fun press(c: Char) {
        if (digits.length >= 4) return
        digits += c
        error = null
        if (digits.length == 4) {
            val err = onPin(digits)
            digits = ""
            if (err != null) error = err
        }
    }
    Panel(title, onDismiss) {
        Column(
            Modifier.onPreviewKeyEvent { ev ->
                val code = ev.nativeKeyEvent.keyCode
                if (code in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9) {
                    if (ev.type == KeyEventType.KeyDown) press('0' + (code - android.view.KeyEvent.KEYCODE_0))
                    true
                } else false
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text((0 until 4).joinToString("  ") { if (it < digits.length) "●" else "○" }, fontSize = 34.sp, color = p.onSurface)
            Text(error ?: "Use the number buttons on your remote, or the keys below", fontSize = 14.sp,
                color = if (error != null) Color(0xFFFF8A80) else p.muted, modifier = Modifier.padding(vertical = 8.dp))
            val rows = listOf("123", "456", "789")
            rows.forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    row.forEachIndexed { i, c ->
                        AppOutlinedButton(onClick = { press(c) }, modifier = Modifier.width(96.dp).then(if (r == 0 && i == 0) Modifier.focusRequester(first) else Modifier)) {
                            Text("$c", fontSize = 20.sp)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppOutlinedButton(onClick = onDismiss, modifier = Modifier.width(96.dp)) { Text("Cancel", fontSize = 14.sp) }
                AppOutlinedButton(onClick = { press('0') }, modifier = Modifier.width(96.dp)) { Text("0", fontSize = 20.sp) }
                AppOutlinedButton(onClick = { digits = digits.dropLast(1) }, modifier = Modifier.width(96.dp)) { Text("⌫", fontSize = 20.sp) }
            }
        }
    }
}

/** Asks for a new PIN twice. */
@Composable
private fun SetPinDialog(onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var firstPin by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.key(firstPin) {
        PinDialog(if (firstPin == null) "Choose a 4-digit PIN" else "Enter the same PIN again", onDismiss) { pin ->
            when {
                firstPin == null -> { firstPin = pin; null }
                pin == firstPin -> { onSet(pin); null }
                else -> { firstPin = null; "The PINs didn't match. Start again." }
            }
        }
    }
}

// ---------------------------------------------------------------- media controls

@Composable
fun MediaControlsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val info = (NowPlaying.state as? NowPlayingState.Playing)?.info
    Panel(info?.title?.ifBlank { null } ?: "Now playing", onDismiss) {
        if (info == null) {
            Text("Nothing is playing right now.", color = LocalPalette.current.muted)
            Spacer(Modifier.padding(6.dp))
            ActionButton("Close", Modifier.focusRequester(first), primary = true, onClick = onDismiss)
            return@Panel
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(if (info.playing) "Pause" else "Play", Modifier.focusRequester(first), primary = true) { NowPlaying.playPause() }
            if (info.canSkip) {
                ActionButton("Next") { NowPlaying.next() }
                ActionButton("Previous") { NowPlaying.previous() }
            }
            ActionButton("Back 10 seconds") { NowPlaying.seekBy(-10_000) }
            ActionButton("Forward 30 seconds") { NowPlaying.seekBy(30_000) }
            ActionButton("Open ${Apps.label(ctx, info.pkg)}") { onDismiss(); Apps.launch(ctx, info.pkg) }
        }
    }
}

// ---------------------------------------------------------------- backup & history

@Composable
private fun RestoreDialog(vm: DashboardViewModel, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val history = remember { vm.historyEntries() }
    val backups = remember { vm.backupFiles() }
    var confirm by remember { mutableStateOf<SavedLayout?>(null) }
    Panel("Restore a layout", onDismiss, wide = true) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { SectionLabel("Version history (kept automatically)") }
            if (history.isEmpty()) item { Text("No earlier versions yet. One is saved each time you finish editing.", color = p.muted, fontSize = 14.sp) }
            items(history) { h ->
                val idx = history.indexOf(h)
                ActionButton(
                    if (confirm == h) "Press again to restore this version" else "${describeTime(h.time)} · ${h.label}",
                    if (idx == 0) Modifier.focusRequester(first) else Modifier,
                ) { if (confirm == h) { vm.restoreFrom(h.file, "an earlier version"); onDismiss() } else confirm = h }
            }
            item { SectionLabel("Backup files") }
            if (backups.isEmpty()) item { Text("No backup files yet. Use \"Save a backup\" first, or copy one to ${Backups.ADB_PATH}", color = p.muted, fontSize = 14.sp) }
            items(backups) { b ->
                ActionButton(
                    if (confirm == b) "Press again to restore this backup" else b.label,
                    if (history.isEmpty() && backups.indexOf(b) == 0) Modifier.focusRequester(first) else Modifier,
                ) { if (confirm == b) { vm.restoreFrom(b.file, "a backup"); onDismiss() } else confirm = b }
            }
            item { SectionLabel("From the web") }
            item { TextRow("Layout file address", "", "https://…/canvas-tv.json") { url -> if (url.isNotBlank()) { vm.restoreFromUrl(url); onDismiss() } } }
            item { Spacer(Modifier.padding(6.dp)); ActionButton("Close", if (history.isEmpty() && backups.isEmpty()) Modifier.focusRequester(first) else Modifier, primary = true, onClick = onDismiss) }
        }
    }
}


// ---------------------------------------------------------------- apps: picker, drawer, folder

@Composable
private fun AppPickerDialog(exclude: Set<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val apps = remember { Apps.list(ctx).filter { it.pkg !in exclude } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Panel("Choose an app", onDismiss) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(apps.size) { i ->
                val a = apps[i]
                ActionButton(a.label, if (i == 0) Modifier.focusRequester(first) else Modifier) { onPick(a.pkg) }
            }
        }
    }
}

/** Every installed app. OK opens it; holding OK offers hide, add to page and app info. */
@Composable
fun AppDrawerDialog(vm: DashboardViewModel, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var showHidden by remember { mutableStateOf(false) }
    val prefs = vm.dashboard.apps
    val apps = remember(prefs, showHidden) { vm.drawerApps(includeHidden = showHidden) }
    var menuFor by remember { mutableStateOf<com.canvastv.dashboard.data.AppInfo?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Panel("All apps", onDismiss, wide = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            AppOutlinedButton(onClick = { vm.setAppSort(AppSort.entries.after(prefs.sort)) }, modifier = Modifier.focusRequester(first)) { Text("Sort: ${prefs.sort.label}") }
            AppOutlinedButton(onClick = { showHidden = !showHidden }) { Text(if (showHidden) "Hide hidden apps" else "Show hidden apps (${prefs.hidden.size})") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(apps.size) { i ->
                val a = apps[i]
                val hidden = a.pkg in prefs.hidden
                androidx.tv.material3.Surface(
                    onClick = { onDismiss(); vm.launchApp(a.pkg) },
                    onLongClick = { menuFor = a },
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent, contentColor = p.onSurface,
                        focusedContainerColor = p.onSurface, focusedContentColor = readableOn(p.onSurface),
                    ),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val art = remember(a.pkg) { Apps.art(ctx, a.pkg) }
                        Box(Modifier.width(72.dp).heightIn(max = 40.dp)) {
                            if (art != null) androidx.compose.foundation.Image(art.first, null, Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(a.label + (if (hidden) "  (hidden)" else "") + (if (!a.isTvApp) "  · phone app" else ""), fontSize = 16.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Text("Hold OK on an app to hide it, add it to this page, or open its settings.", fontSize = 12.sp, color = p.muted, modifier = Modifier.padding(top = 8.dp))
    }
    menuFor?.let { a ->
        val hidden = a.pkg in prefs.hidden
        Panel(a.label, onDismiss = { menuFor = null }) {
            val f2 = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { f2.requestFocus() } }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Open", Modifier.focusRequester(f2), primary = true) { menuFor = null; onDismiss(); vm.launchApp(a.pkg) }
                ActionButton(if (hidden) "Show in the drawer again" else "Hide from the drawer") { vm.setAppHidden(a.pkg, !hidden); menuFor = null }
                ActionButton("Add to this page") { vm.guard { if (vm.addAppTile(a.pkg)) vm.message = "Added ${a.label} to this page" }; menuFor = null }
                ActionButton("App info (storage, permissions, uninstall)") {
                    menuFor = null
                    runCatching {
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${a.pkg}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { vm.message = "This TV doesn't allow opening app info from here" }
                }
            }
        }
    }
}

@Composable
fun FolderDialog(vm: DashboardViewModel, tile: Tile, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val pkgs = folderApps(tile).filter { Apps.isInstalled(ctx, it) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Panel(tile.cfg("name").ifBlank { "Folder" }, onDismiss) {
        if (pkgs.isEmpty()) {
            Text("This folder is empty. Add apps with Configure in edit mode.", color = LocalPalette.current.muted)
            Spacer(Modifier.padding(6.dp))
            ActionButton("Close", Modifier.focusRequester(first), primary = true, onClick = onDismiss)
            return@Panel
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(pkgs.size) { i ->
                ActionButton(Apps.label(ctx, pkgs[i]), if (i == 0) Modifier.focusRequester(first) else Modifier) { onDismiss(); vm.launchApp(pkgs[i]) }
            }
        }
    }
}


@Composable
private fun RefreshRow(tile: Tile, options: List<String>, default: String, onChange: (Tile) -> Unit, modifier: Modifier = Modifier, label: String = "Refresh every") {
    val cur = tile.cfg("refreshMin", default).takeIf { it in options } ?: default
    CycleRow(label, "$cur min", { onChange(tile.withCfg("refreshMin", options.after(cur))) }, modifier)
}

// ---------------------------------------------------------------- Home Assistant

/** Every chosen device as a button: OK runs its action. Locks and doors need a second press. */
@Composable
fun HomeAssistantDialog(tile: Tile, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val server = com.canvastv.dashboard.ui.tiles.haServer()
    val ids = com.canvastv.dashboard.ui.tiles.haIds(tile)
    val data by com.canvastv.dashboard.ui.tiles.rememberHaStates(server, ids, 5_000L)
    val list = data?.text?.let { com.canvastv.dashboard.data.parseHaStates(it, ids) }.orEmpty()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirm by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(list.isNotEmpty()) { runCatching { first.requestFocus() } }
    Panel(tile.cfg("title").ifBlank { "Home" }, onDismiss) {
        if (!server.configured) Text("Add your Home Assistant address and token in Settings first.", color = p.muted)
        else if (list.isEmpty()) Text(if (data?.error != null) "Couldn't reach Home Assistant (${data?.error})" else "Loading…", color = p.muted)
        error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 14.sp) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list.size) { i ->
                val e = list[i]
                val action = e.action
                val label = when {
                    action == null -> "${e.name}: ${e.display}"
                    confirm == e.id -> "Press again to ${action.lowercase()} ${e.name}"
                    else -> "${e.name}: ${e.display}  ·  $action"
                }
                ActionButton(label, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                    if (action == null) return@ActionButton
                    if (e.needsConfirm && confirm != e.id) { confirm = e.id; return@ActionButton }
                    confirm = null
                    scope.launch {
                        error = runCatching { com.canvastv.dashboard.data.HomeAssistant.activate(server, e) }.exceptionOrNull()?.let { "Didn't work: ${it.message}" }
                    }
                }
            }
        }
    }
}

/** Lists what Home Assistant has, so devices can be chosen with the remote instead of typed. */
@Composable
private fun HaPickerDialog(tile: Tile, onChange: (Tile) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val server = com.canvastv.dashboard.ui.tiles.haServer()
    val all by androidx.compose.runtime.produceState<Result<List<com.canvastv.dashboard.data.HaEntity>>?>(null, server) {
        value = if (!server.configured) Result.failure(IllegalStateException("Add your Home Assistant address and token in Settings first."))
        else runCatching { com.canvastv.dashboard.data.HomeAssistant.listAll(server) }
    }
    val chosen = com.canvastv.dashboard.ui.tiles.haIds(tile)
    val first = remember { FocusRequester() }
    LaunchedEffect(all != null) { runCatching { first.requestFocus() } }
    Panel("Choose devices", onDismiss, wide = true) {
        val r = all
        when {
            r == null -> Text("Loading from Home Assistant…", color = p.muted)
            r.isFailure -> { Text(r.exceptionOrNull()?.message ?: "Couldn't load", color = Color(0xFFFF8A80)); ActionButton("Back", Modifier.focusRequester(first), onClick = onDismiss) }
            else -> {
                val list = r.getOrThrow()
                Text("${chosen.size} chosen. OK adds or removes; they show in the order you add them.", color = p.muted, fontSize = 13.sp)
                Spacer(Modifier.padding(4.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { ActionButton("Done", Modifier.focusRequester(first), primary = true, onClick = onDismiss) }
                    items(list.size) { i ->
                        val e = list[i]
                        val on = e.id in chosen
                        ActionButton((if (on) "✓  " else "     ") + "${e.name}  (${e.id})") {
                            val next = if (on) chosen - e.id else chosen + e.id
                            onChange(tile.withCfg("entities", next.joinToString(", ")))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- show search

/**
 * Add a show by name: search (TVmaze), pick the right one, and the tile is set up to open it in
 * its streaming app. When the service isn't clear, it asks which app. A link can still be typed.
 */
@Composable
private fun ShowSearchPanel(onDismiss: () -> Unit, first: FocusRequester, onAdd: (Map<String, String>) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<com.canvastv.dashboard.data.ShowHit?>(null) }
    var byLink by remember { mutableStateOf(false) }
    val results by androidx.compose.runtime.produceState<Result<List<com.canvastv.dashboard.data.ShowHit>>?>(null, query) {
        value = if (query.isBlank()) null else runCatching { com.canvastv.dashboard.data.Episodes.search(query) }
    }
    val resultFocus = remember { FocusRequester() }
    LaunchedEffect(results) { if (results?.getOrNull()?.isNotEmpty() == true) runCatching { resultFocus.requestFocus() } }

    val hit = picked
    if (hit != null) {
        // The service wasn't clear from TVmaze: ask which app opens it.
        val apps = remember { Apps.list(ctx).filter { it.isTvApp } }
        val f = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { f.requestFocus() } }
        Panel("Which app has ${hit.name}?", onDismiss) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(apps.size) { i ->
                    ActionButton(apps[i].label, if (i == 0) Modifier.focusRequester(f) else Modifier) {
                        onAdd(com.canvastv.dashboard.data.Shows.configFor(ctx, hit, pkgOverride = apps[i].pkg))
                    }
                }
            }
        }
        return
    }
    Panel("Add a show", onDismiss, wide = true) {
        Column {
            if (!byLink) {
                Box(Modifier.focusRequester(first)) {
                    TextRow("Show name", query, "Type the name, or use the keyboard's microphone") { query = it }
                }
                val r = results
                when {
                    r == null -> Text("Press OK on the box above to type. You'll get a list to pick from.", color = p.muted, fontSize = 13.sp)
                    r.isFailure -> Text("Couldn't search right now (${r.exceptionOrNull()?.message}). Check the TV's internet connection.", color = Color(0xFFFF8A80), fontSize = 14.sp)
                    r.getOrThrow().isEmpty() -> Text("No shows called \"$query\". Try a shorter name.", color = p.muted, fontSize = 14.sp)
                    else -> {
                        val list = r.getOrThrow().take(12)
                        Spacer(Modifier.padding(4.dp))
                        LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(list.size) { i ->
                                val h = list[i]
                                val cfg = remember(h) { com.canvastv.dashboard.data.Shows.configFor(ctx, h) }
                                val opensIn = cfg["link"]?.let { com.canvastv.dashboard.data.Shows.serviceFor(it)?.name }
                                    ?: cfg["pkg"]?.let { Apps.label(ctx, it) }
                                val label = h.name + (if (h.year.isNotEmpty()) " (${h.year})" else "") +
                                    (if (h.channel.isNotEmpty()) " · ${h.channel}" else "") + (opensIn?.let { "  →  opens in $it" } ?: "  →  choose the app")
                                ActionButton(label, if (i == 0) Modifier.focusRequester(resultFocus) else Modifier) {
                                    if (cfg["link"] != null || cfg["pkg"] != null) onAdd(cfg) else picked = h
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.padding(4.dp))
                ActionButton("Enter a link instead") { byLink = true }
            } else {
                Text("Paste or type the show's address from the streaming service's website.", color = p.muted, fontSize = 13.sp)
                TextRow("Show link", "", "https://www.netflix.com/title/80117401") { link ->
                    if (link.isNotBlank()) onAdd(mapOf("link" to com.canvastv.dashboard.data.Shows.normalize(link)))
                }
                ActionButton("Search by name instead") { byLink = false }
            }
        }
    }
}
