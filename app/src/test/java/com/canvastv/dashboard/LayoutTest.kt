package com.canvastv.dashboard

import com.canvastv.dashboard.data.DashboardViewModel
import com.canvastv.dashboard.model.Dashboard
import com.canvastv.dashboard.model.FocusEffect
import com.canvastv.dashboard.model.Page
import com.canvastv.dashboard.model.Tile
import com.canvastv.dashboard.model.TileType
import com.canvastv.dashboard.model.canPlace
import com.canvastv.dashboard.model.freeSpot
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun `layout saved by 0_1_x still loads`() {
        // Shape written by Phase 1a, including the removed focusGlow field.
        val old = """{"version":1,"columns":12,"rows":6,"theme":{"mode":"DARK","accentHex":"#FFC857","focusGlow":false},
            "pages":[{"id":"a","name":"Home","tiles":[{"id":"t","type":"CLOCK","x":0,"y":0,"w":4,"h":2}]}]}"""
        val d = json.decodeFromString(Dashboard.serializer(), old)
        assertEquals(1, d.pages.single().tiles.size)
        assertFalse(d.lightweight)
        assertNull(d.pinHash)
        assertEquals(FocusEffect.GLOW, d.theme.focusEffect)
    }

    @Test fun `round trip keeps everything`() {
        val d = Dashboard(lightweight = true, pinHash = "x", pages = listOf(Page(name = "P", tiles = listOf(Tile(type = TileType.NOW_PLAYING, x = 1, y = 1, w = 4, h = 2)))))
        assertEquals(d, json.decodeFromString(Dashboard.serializer(), json.encodeToString(Dashboard.serializer(), d)))
    }

    @Test fun `sanitize pulls tiles inside and drops overlaps`() {
        val d = Dashboard(columns = 12, rows = 6, pages = listOf(Page(name = "", tiles = listOf(
            Tile(id = "a", type = TileType.NOTE, x = 11, y = 5, w = 3, h = 2),   // hangs off the corner
            Tile(id = "b", type = TileType.NOTE, x = 9, y = 4, w = 3, h = 2),    // same spot once pulled in
            Tile(id = "c", type = TileType.NOTE, x = -4, y = 0, w = 40, h = 1),  // too wide
        ))))
        val s = DashboardViewModel.sanitize(d)
        val tiles = s.pages.single().tiles
        assertEquals(listOf("a", "c"), tiles.map { it.id })
        tiles.forEach { assertTrue(s.pages.single().canPlace(it, s)) }
        assertEquals("Page", s.pages.single().name)
    }

    @Test fun `sanitize never returns zero pages`() {
        assertEquals(1, DashboardViewModel.sanitize(Dashboard(pages = emptyList())).pages.size)
    }

    @Test fun `pin hash is stable and differs per pin`() {
        assertEquals(DashboardViewModel.hashPin("1234"), DashboardViewModel.hashPin("1234"))
        assertNotEquals(DashboardViewModel.hashPin("1234"), DashboardViewModel.hashPin("4321"))
        assertEquals(64, DashboardViewModel.hashPin("0000").length)
    }

    @Test fun `free spot finds room and reports a full page`() {
        val d = Dashboard(columns = 4, rows = 2)
        val full = Page(name = "x", tiles = listOf(Tile(type = TileType.NOTE, x = 0, y = 0, w = 4, h = 2)))
        assertNull(full.freeSpot(1, 1, d))
        assertEquals(0 to 0, Page(name = "y").freeSpot(4, 2, d))
    }
}
