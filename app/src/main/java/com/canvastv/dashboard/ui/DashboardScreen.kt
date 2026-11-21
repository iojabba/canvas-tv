package com.canvastv.dashboard.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.canvastv.dashboard.data.Apps
import com.canvastv.dashboard.data.DashboardViewModel
import com.canvastv.dashboard.data.EditMode
import com.canvastv.dashboard.data.NowPlaying
import com.canvastv.dashboard.model.Background
import com.canvastv.dashboard.model.BgKind
import com.canvastv.dashboard.model.FocusEffect
import com.canvastv.dashboard.model.ThemeSpec
import com.canvastv.dashboard.model.Tile
import com.canvastv.dashboard.model.TileType
import com.canvastv.dashboard.ui.tiles.TileContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface DialogState {
    data class Actions(val tileId: String) : DialogState
    data class Configure(val tileId: String) : DialogState
    data object AddTile : DialogState
    data object Settings : DialogState
    data object Media : DialogState
    data object Drawer : DialogState
    data class Folder(val tileId: String) : DialogState
    data class HomeAssistant(val tileId: String) : DialogState
}

@Composable
fun DashboardScreen(vm: DashboardViewModel) {
    val d = vm.dashboard
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // With the Home button helper on, Canvas TV acts as the home screen too.
    val isHome = remember { isDefaultHome(ctx) || com.canvastv.dashboard.service.HomeHelper.enabled(ctx) }
    val firstTileFocus = remember { FocusRequester() }
    val pageTabFocus = remember { FocusRequester() }
    val tileFocus = remember { mutableMapOf<String, FocusRequester>() }
    /** Puts the highlight back on the last used tile on this page, else the first tile. */
    fun restoreFocus() {
        val id = vm.lastFocusedTileId?.takeIf { id -> vm.page.tiles.any { it.id == id } }
        val target = id?.let { tileFocus[it] } ?: firstTileFocus
        if (runCatching { target.requestFocus() }.isFailure) runCatching { pageTabFocus.requestFocus() }
    }

    // Rotate pages on a timer (kiosk / ambient use), paused while editing or in a menu.
    LaunchedEffect(d.pageTimerSec, vm.editing, dialog, vm.pageIndex, d.pages.size) {
        if (d.pageTimerSec > 0 && !vm.editing && dialog == null && d.pages.size > 1) {
            delay(d.pageTimerSec * 1000L)
            vm.goToPage(vm.pageIndex + 1, byTimer = true)
        }
    }
    val shownPage = remember { intArrayOf(vm.pageIndex) }
    LaunchedEffect(vm.pageIndex) {
        if (shownPage[0] == vm.pageIndex) return@LaunchedEffect // first showing: the restore below handles focus
        shownPage[0] = vm.pageIndex
        // A timed page change keeps the highlight on the page tabs, so a late OK can't open a tile you didn't pick.
        if (vm.pageChangedByTimer) runCatching { pageTabFocus.requestFocus() }
        else { vm.lastFocusedTileId = null; runCatching { firstTileFocus.requestFocus() } }
    }
    // After a menu closes or a move/resize ends (and on return from full screen), go back to where you were.
    LaunchedEffect(dialog == null && vm.activeEdit == null && vm.pinRequest == null) {
        if (dialog == null && vm.activeEdit == null && vm.pinRequest == null) { delay(50); restoreFocus() }
    }
    LaunchedEffect(vm.message) { if (vm.message != null) { delay(3500); vm.message = null } }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev ->
                val code = ev.nativeKeyEvent.keyCode
                val down = ev.type == KeyEventType.KeyDown
                val up = ev.type == KeyEventType.KeyUp
                if (vm.activeEdit != null) {
                    when (code) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (down) vm.nudge(-1, 0); true }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) vm.nudge(1, 0); true }
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> { if (down) vm.nudge(0, -1); true }
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { if (down) vm.nudge(0, 1); true }
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                        AndroidKeyEvent.KEYCODE_BACK -> { if (up) vm.activeEdit = null; true }
                        else -> false
                    }
                } else when (code) {
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> { if (down) vm.goToPage(vm.pageIndex - 1); true }
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> { if (down) vm.goToPage(vm.pageIndex + 1); true }
                    // Gamepad: bumpers switch pages, Y toggles edit mode, Start opens Settings.
                    // (A and B already act as OK and Back through Android's gamepad fallbacks.)
                    AndroidKeyEvent.KEYCODE_BUTTON_L1 -> { if (down) vm.goToPage(vm.pageIndex - 1); true }
                    AndroidKeyEvent.KEYCODE_BUTTON_R1 -> { if (down) vm.goToPage(vm.pageIndex + 1); true }
                    AndroidKeyEvent.KEYCODE_BUTTON_Y -> { if (up && dialog == null) { if (vm.editing) vm.stopEditing() else vm.startEditing() }; true }
                    AndroidKeyEvent.KEYCODE_BUTTON_START -> { if (up && dialog == null) vm.guard { dialog = DialogState.Settings }; true }
                    // Keyboard: Tab-free shortcuts that don't fight with text fields.
                    AndroidKeyEvent.KEYCODE_F2 -> { if (up && dialog == null) { if (vm.editing) vm.stopEditing() else vm.startEditing() }; true }
                    AndroidKeyEvent.KEYCODE_SEARCH -> { if (up) voiceSearch(ctx, vm); true }
                    in AndroidKeyEvent.KEYCODE_1..AndroidKeyEvent.KEYCODE_9 -> {
                        // Number keys jump straight to page 1–9 (not while the PIN pad is up; it's a dialog).
                        val i = code - AndroidKeyEvent.KEYCODE_1
                        if (i < d.pages.size) { if (down) vm.goToPage(i); true } else false
                    }
                    AndroidKeyEvent.KEYCODE_MENU -> { if (up && dialog == null) { if (vm.editing) vm.stopEditing() else vm.startEditing() }; true }
                    AndroidKeyEvent.KEYCODE_BACK -> when {
                        dialog != null -> false
                        vm.editing -> { if (up) vm.stopEditing(); true }
                        // As the home screen there is nothing to go back to: Back returns to page 1.
                        vm.pageIndex != 0 -> { if (up) vm.goToPage(0); true }
                        isHome -> true
                        else -> false
                    }
                    else -> false
                }
            },
    ) {
        BackgroundLayer(vm.effectiveTheme().background, d.lightweight) { vm.wallpaperAccent = it }

        Column(Modifier.fillMaxSize().padding(horizontal = (24 + d.overscanPct * 19.2f).dp, vertical = (14 + d.overscanPct * 10.8f).dp)) {
            TopBar(
                vm = vm,
                selectedTabFocus = pageTabFocus,
                onAdd = { dialog = DialogState.AddTile },
                onSettings = { vm.guard { dialog = DialogState.Settings } },
            )
            Spacer(Modifier.size(10.dp))
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val cellW = maxWidth / d.columns
                val cellH = maxHeight / d.rows
                if (vm.editing) GridGuides(d.columns, d.rows)
                // Shows with new episodes take the first show spots (on screen only; the saved layout doesn't change).
                var showTick by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) { while (true) { delay(5 * 60_000L); showTick++ } }
                val shown = remember(vm.page, vm.editing, showTick) {
                    if (vm.editing) vm.page.tiles
                    else com.canvastv.dashboard.ui.tiles.newShowsFirst(vm.page.tiles) { com.canvastv.dashboard.ui.tiles.hasNewEpisode(ctx, it) }
                }
                val tiles = shown.sortedWith(compareBy({ it.y }, { it.x }))
                tiles.forEachIndexed { i, tile ->
                    key(tile.id) {
                        val req = tileFocus.getOrPut(tile.id) { FocusRequester() }
                        val focusMod = (if (i == 0) Modifier.focusRequester(firstTileFocus) else Modifier)
                            .focusRequester(req)
                            .onFocusChanged { if (it.isFocused) vm.lastFocusedTileId = tile.id }
                        TileFrame(
                            tile = tile,
                            editing = vm.editing,
                            active = vm.activeEdit?.tileId == tile.id,
                            theme = d.theme,
                            lightweight = d.lightweight,
                            hiddenApps = d.apps.hidden,
                            cellHeightDp = cellH.value,
                            modifier = Modifier
                                .offset(cellW * tile.x, cellH * tile.y)
                                .size(cellW * tile.w, cellH * tile.h)
                                .padding(7.dp)
                                .then(focusMod),
                            onClick = {
                                if (vm.editing) dialog = DialogState.Actions(tile.id)
                                else when (tile.type) {
                                    TileType.APP -> vm.launchApp(tile.cfg("pkg"))
                                    TileType.APP_DRAWER -> dialog = DialogState.Drawer
                                    TileType.FOLDER -> dialog = DialogState.Folder(tile.id)
                                    TileType.HDMI, TileType.VIDEO -> vm.fullScreenTile = tile
                                    TileType.NOW_PLAYING -> NowPlaying.playPause()
                                    TileType.SPEEDTEST -> com.canvastv.dashboard.data.SpeedTest.run(ctx, scope)
                                    TileType.SHOW -> if (com.canvastv.dashboard.data.Shows.open(ctx, tile)) vm.markShowOpened(tile.id) else vm.message = "Couldn't open that show. Is its app installed?"
                                    TileType.HOME_ASSISTANT -> {
                                        val quick = com.canvastv.dashboard.ui.tiles.haQuickEntity(ctx, tile)
                                        if (quick == null) dialog = DialogState.HomeAssistant(tile.id)
                                        else scope.launch {
                                            runCatching { com.canvastv.dashboard.data.HomeAssistant.activate(d.homeAssistant, quick) }
                                                .onFailure { vm.message = "Home Assistant didn't respond (${it.message})" }
                                        }
                                    }
                                    else -> vm.message = "Long-press OK (or press Menu) to edit tiles"
                                }
                            },
                            onLongClick = {
                                if (!vm.editing && tile.type == TileType.NOW_PLAYING) dialog = DialogState.Media
                                else vm.guard { dialog = DialogState.Actions(tile.id) }
                            },
                        )
                    }
                }
                if (tiles.isEmpty()) EmptyPageHint(onAdd = { vm.guard { vm.startEditing(); dialog = DialogState.AddTile } })
            }
        }

        vm.activeEdit?.let { edit ->
            Hint(
                if (edit.mode == EditMode.MOVE) "Moving: use the arrows, then press OK"
                else "Resizing: right/down to grow, left/up to shrink, then press OK",
                Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            )
        }
        vm.message?.let { Hint(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)) }
        com.canvastv.dashboard.data.Messages.banner?.let { m -> MessageBanner(m, Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 32.dp)) }
    }

    when (val dl = dialog) {
        null -> Unit
        is DialogState.Actions -> vm.tile(dl.tileId)?.let { t ->
            TileActionsDialog(
                tile = t,
                onDismiss = { dialog = null },
                onOpen = {
                    dialog = null
                    when (t.type) {
                        TileType.APP -> vm.launchApp(t.cfg("pkg"))
                        TileType.APP_DRAWER -> dialog = DialogState.Drawer
                        TileType.FOLDER -> dialog = DialogState.Folder(t.id)
                        TileType.HDMI, TileType.VIDEO -> vm.fullScreenTile = t
                        TileType.SHOW -> if (com.canvastv.dashboard.data.Shows.open(ctx, t)) vm.markShowOpened(t.id) else vm.message = "Couldn't open that show. Is its app installed?"
                        else -> Unit
                    }
                },
                onMove = { dialog = null; vm.startEditing(); vm.activeEdit = com.canvastv.dashboard.data.ActiveEdit(t.id, EditMode.MOVE) },
                onResize = { dialog = null; vm.startEditing(); vm.activeEdit = com.canvastv.dashboard.data.ActiveEdit(t.id, EditMode.RESIZE) },
                onConfigure = { dialog = DialogState.Configure(t.id) },
                onDuplicate = { dialog = null; vm.duplicateTile(t.id) },
                onDelete = { dialog = null; vm.deleteTile(t.id) },
            )
        } ?: run { dialog = null }
        is DialogState.Configure -> vm.tile(dl.tileId)?.let { t ->
            ConfigureTileDialog(tile = t, onChange = vm::replaceTile, onDismiss = { dialog = null })
        } ?: run { dialog = null }
        DialogState.AddTile -> AddTileDialog(
            onDismiss = { dialog = null },
            onAdd = { type, cfg -> dialog = null; vm.startEditing(); vm.addTile(type, cfg) },
        )
        DialogState.Settings -> SettingsDialog(vm = vm, onDismiss = { dialog = null; vm.relockIfIdle() })
        DialogState.Media -> MediaControlsDialog(onDismiss = { dialog = null })
        DialogState.Drawer -> AppDrawerDialog(vm, onDismiss = { dialog = null })
        is DialogState.Folder -> vm.tile(dl.tileId)?.let { t -> FolderDialog(vm, t, onDismiss = { dialog = null }) } ?: run { dialog = null }
        is DialogState.HomeAssistant -> vm.tile(dl.tileId)?.let { t -> HomeAssistantDialog(t, onDismiss = { dialog = null }) } ?: run { dialog = null }
    }

    if (vm.pinRequest != null) PinDialog(title = "Enter PIN to edit", onDismiss = vm::cancelPin, onPin = vm::submitPin)
}

@Composable
private fun TopBar(vm: DashboardViewModel, selectedTabFocus: FocusRequester, onAdd: () -> Unit, onSettings: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val d = vm.dashboard
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Recount new episodes every few minutes (cache only, no network).
        var tick by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(5 * 60_000L); tick++ } }
        d.pages.forEachIndexed { i, page ->
            val selected = i == vm.pageIndex
            val fresh = remember(page, tick) { com.canvastv.dashboard.ui.tiles.newShowCount(ctx, page) }
            val label: @Composable () -> Unit = {
                Text(page.name)
                if (fresh > 0) Text(
                    "  $fresh new", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = if (selected) Color.Unspecified else p.accentText,
                )
            }
            if (selected) AppButton(onClick = { vm.goToPage(i) }, modifier = Modifier.focusRequester(selectedTabFocus), scale = ButtonDefaults.scale(focusedScale = 1.05f)) { label() }
            else AppOutlinedButton(onClick = { vm.goToPage(i) }, scale = ButtonDefaults.scale(focusedScale = 1.05f)) { label() }
        }
        Spacer(Modifier.weight(1f))
        if (vm.editing) {
            AppButton(onClick = onAdd) { Text("+ Tile") }
            AppOutlinedButton(onClick = { vm.addPage() }) { Text("+ Page") }
            if (d.pages.size > 1) {
                // Two presses: the first asks, the second deletes. Resets after a few seconds.
                var confirm by remember(vm.pageIndex) { mutableStateOf(false) }
                LaunchedEffect(confirm) { if (confirm) { delay(4000); confirm = false } }
                AppOutlinedButton(onClick = { if (confirm) { confirm = false; vm.deletePage() } else confirm = true }) {
                    Text(if (confirm) "Delete \"${vm.page.name}\" and its ${vm.page.tiles.size} tiles?" else "Delete page")
                }
            }
            AppButton(onClick = { vm.stopEditing() }) { Text("Done") }
        } else {
            AppOutlinedButton(onClick = { vm.startEditing() }) { Text(if (vm.dashboard.pinHash != null) "Edit 🔒" else "Edit") }
        }
        AppOutlinedButton(onClick = { voiceSearch(ctx, vm) }) { Text("Search") }
        AppOutlinedButton(onClick = onSettings) { Text("Settings") }
    }
}

@Composable
private fun TileFrame(
    tile: Tile,
    editing: Boolean,
    active: Boolean,
    theme: ThemeSpec,
    lightweight: Boolean,
    hiddenApps: List<String>,
    cellHeightDp: Float,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(tile.style.cornerDp.dp)
    val base = parseColor(tile.style.colorHex, p.surface)
    val opacity = if (p.highContrast) 1f else tile.style.opacity
    val container = base.copy(alpha = opacity)
    val edgeColor = when {
        active -> p.accent
        editing -> p.onSurface.copy(alpha = 0.45f)
        p.highContrast -> p.onSurface.copy(alpha = 0.6f)
        tile.style.border -> p.onSurface.copy(alpha = 0.25f)
        else -> Color.Transparent
    }
    val effect = if (lightweight) FocusEffect.OUTLINE else theme.focusEffect
    val zoom = when {
        active || lightweight -> 1f
        effect == FocusEffect.ZOOM -> maxOf(theme.focusScale, 1.08f)
        else -> theme.focusScale
    }
    val focusedBorder = when (effect) {
        FocusEffect.GLOW, FocusEffect.OUTLINE -> BorderStroke(if (p.highContrast) 5.dp else 3.dp, p.accent)
        FocusEffect.THICK -> BorderStroke(7.dp, if (p.highContrast) p.accent else p.onSurface)
        FocusEffect.ZOOM -> BorderStroke(if (p.highContrast) 4.dp else 0.dp, if (p.highContrast) p.accent else Color.Transparent)
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.semantics { contentDescription = describeTile(ctx, tile) },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            focusedContainerColor = container.copy(alpha = (opacity + 0.12f).coerceAtMost(1f)),
            pressedContainerColor = container,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = zoom),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(if (active) 3.dp else if (p.highContrast) 2.dp else 1.5.dp, edgeColor), shape = shape),
            focusedBorder = Border(focusedBorder, shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = if (effect == FocusEffect.GLOW) Glow(elevationColor = p.accent.copy(alpha = 0.6f), elevation = 14.dp) else Glow.None,
        ),
    ) {
        TileContent(tile, cellHeightDp, liveVideo = !lightweight, hiddenApps = hiddenApps)
        if (editing) {
            // Small tiles show just the size, and the badge never wraps at large text sizes.
            Text(
                if (tile.w >= 3) "${tile.type.label} · ${tile.w}×${tile.h}" else "${tile.w}×${tile.h}",
                fontSize = 11.sp, color = Color.White, maxLines = 1, softWrap = false,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** What a screen reader says for a tile. */
private fun describeTile(ctx: android.content.Context, t: Tile): String = when (t.type) {
    TileType.APP -> "${t.cfg("label").ifBlank { Apps.label(ctx, t.cfg("pkg")) }}, app"
    TileType.HDMI -> "${t.cfg("label", "HDMI")}, input. Press OK for full screen"
    TileType.CLOCK -> "Clock" + t.cfg("city").let { if (it.isBlank()) "" else ", $it" }
    TileType.NOTE -> "Note: ${t.cfg("text")}"
    TileType.VIDEO -> "${t.cfg("title").ifBlank { "Video" }}, video. Press OK for full screen"
    TileType.STATS -> "System stats"
    TileType.NOW_PLAYING -> "Now playing. Press OK to play or pause, hold OK for more controls"
    TileType.COUNTDOWN -> "Countdown: ${t.cfg("label")}"
    TileType.APP_DRAWER -> "All apps. Press OK to open"
    TileType.FOLDER -> "Folder ${t.cfg("name")}. Press OK to open"
    TileType.WEATHER -> "Weather for ${t.cfg("place")}"
    TileType.NEWS -> "News headlines"
    TileType.MESSAGES -> "Message board"
    TileType.HOME_ASSISTANT -> "Home Assistant controls. Press OK to use them"
    TileType.SHOW -> "${t.cfg("title").ifBlank { "Show" }}. Press OK to watch"
    TileType.TICKER -> "Stock and crypto prices"
    TileType.CALENDAR -> "Calendar"
    TileType.PHOTOS -> "Photo slideshow"
    TileType.WEB -> "Web page ${t.cfg("title")}"
    TileType.SPEEDTEST -> "Internet speed test. Press OK to run it"
}

@Composable
private fun GridGuides(columns: Int, rows: Int) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize()) {
        repeat(rows) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                repeat(columns) {
                    Box(Modifier.weight(1f).fillMaxSize().padding(3.dp).border(1.dp, p.onSurface.copy(alpha = 0.08f), RoundedCornerShape(8.dp)))
                }
            }
        }
    }
}

@Composable
private fun BackgroundLayer(bg: Background, lightweight: Boolean, onAccent: (String?) -> Unit) {
    // In the Light theme the background is washed toward white so dark theme text stays readable.
    val light = !LocalPalette.current.dark
    fun tone(c: Color) = if (light) lerp(c, Color.White, 0.85f) else c
    val c1 = tone(parseColor(bg.color1, Color(0xFF0F2027)))
    val c2 = tone(parseColor(bg.color2, Color(0xFF2C5364)))
    val ctx = LocalContext.current
    val pictureUrl: String? = when (bg.kind) {
        BgKind.IMAGE -> bg.imageUrl.takeIf { it.isNotBlank() }
        BgKind.BING -> {
            val data by com.canvastv.dashboard.data.rememberRemoteData("bing-daily", 6 * 3600_000L) {
                com.canvastv.dashboard.data.httpGet("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=en-US")
            }
            data?.text?.let(::bingImageUrl)
        }
        else -> null
    }
    // Picture backgrounds report a matching accent color for "Accent from background".
    LaunchedEffect(pictureUrl, lightweight) {
        onAccent(if (pictureUrl == null || lightweight) null else accentFromPicture(ctx, pictureUrl))
    }
    Box(Modifier.fillMaxSize()) {
        when (bg.kind) {
            BgKind.SOLID -> Box(Modifier.fillMaxSize().background(c1))
            BgKind.GRADIENT -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c1, c2))))
            BgKind.IMAGE, BgKind.BING -> {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c1, c2))))
                if (pictureUrl != null && !lightweight) {
                    AsyncImage(model = pictureUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
        if (bg.dim > 0f) Box(Modifier.fillMaxSize().background((if (light) Color.White else Color.Black).copy(alpha = bg.dim)))
    }
}

/** Bing's daily image feed: {"images":[{"url":"/th?id=...1920x1080.jpg", ...}]}. */
fun bingImageUrl(json: String): String? = runCatching {
    val url = kotlinx.serialization.json.Json.parseToJsonElement(json).let { it as kotlinx.serialization.json.JsonObject }["images"]
        .let { it as kotlinx.serialization.json.JsonArray }[0].let { it as kotlinx.serialization.json.JsonObject }["url"]
        .let { (it as kotlinx.serialization.json.JsonPrimitive).content }
    if (url.startsWith("http")) url else "https://www.bing.com$url"
}.getOrNull()

/** Picks a lively, readable color from the picture: the most common saturated hue, brightened if needed. */
private suspend fun accentFromPicture(ctx: android.content.Context, url: String): String? = runCatching {
    val req = coil.request.ImageRequest.Builder(ctx).data(url).size(48, 27).allowHardware(false).build()
    val bmp = (coil.Coil.imageLoader(ctx).execute(req).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return null
    val buckets = HashMap<Int, Pair<Int, FloatArray>>()
    val hsv = FloatArray(3)
    for (x in 0 until bmp.width) for (y in 0 until bmp.height) {
        android.graphics.Color.colorToHSV(bmp.getPixel(x, y), hsv)
        if (hsv[1] < 0.35f || hsv[2] < 0.3f) continue
        val key = (hsv[0] / 20).toInt()
        val prev = buckets[key]
        buckets[key] = ((prev?.first ?: 0) + 1) to (if (prev == null || hsv[1] * hsv[2] > prev.second[1] * prev.second[2]) hsv.copyOf() else prev.second)
    }
    val best = buckets.values.maxByOrNull { it.first }?.second ?: return null
    best[1] = best[1].coerceIn(0.45f, 0.8f); best[2] = best[2].coerceAtLeast(0.85f)
    String.format("#%06X", 0xFFFFFF and android.graphics.Color.HSVToColor(best))
}.getOrNull()

@Composable
private fun EmptyPageHint(onAdd: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("This page is empty", fontSize = 22.sp)
        Spacer(Modifier.size(12.dp))
        AppButton(onClick = onAdd) { Text("+ Add a tile") }
    }
}

/** A message from a phone or a web command, shown for 20 seconds. */
@Composable
private fun MessageBanner(m: com.canvastv.dashboard.data.TvMessage, modifier: Modifier) {
    val p = LocalPalette.current
    LaunchedEffect(m) { delay(20_000); if (com.canvastv.dashboard.data.Messages.banner == m) com.canvastv.dashboard.data.Messages.banner = null }
    Column(
        modifier.width(460.dp).background(Color(0xEE10161E), RoundedCornerShape(16.dp))
            .border(2.dp, p.accent, RoundedCornerShape(16.dp)).padding(18.dp),
    ) {
        Text(if (m.from.isBlank()) "Message" else "Message from ${m.from}", fontSize = 14.sp, color = p.accent)
        Text(m.text, fontSize = 24.sp, color = Color.White)
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = 16.sp, color = Color.White,
        modifier = modifier.background(Color(0xDD10161E), RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

@Suppress("unused")
private val unusedWidth = Modifier.width(0.dp)

/** Opens Google Assistant search, falling back to the system search screen. */
fun voiceSearch(ctx: android.content.Context, vm: DashboardViewModel) {
    val tries = listOf(
        android.content.Intent(android.content.Intent.ACTION_ASSIST),
        android.content.Intent(android.app.SearchManager.INTENT_ACTION_GLOBAL_SEARCH),
        android.content.Intent(android.speech.RecognizerIntent.ACTION_WEB_SEARCH),
    )
    val ok = tries.any { i -> runCatching { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false) }
    if (!ok) vm.message = "Search isn't available on this TV. Try the microphone button on your remote."
}
