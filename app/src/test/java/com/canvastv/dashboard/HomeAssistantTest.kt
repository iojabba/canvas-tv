package com.canvastv.dashboard

import com.canvastv.dashboard.data.HaEntity
import com.canvastv.dashboard.data.HomeAssistant
import com.canvastv.dashboard.data.parseHaStates
import com.canvastv.dashboard.model.Dashboard
import com.canvastv.dashboard.model.HaServer
import com.canvastv.dashboard.model.OverlayMode
import com.canvastv.dashboard.service.CanvasService
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantTest {
    private val states = """[
      {"entity_id":"light.living_room","state":"on","attributes":{"friendly_name":"Living room"}},
      {"entity_id":"sensor.outside","state":"71.0","attributes":{"friendly_name":"Outside","unit_of_measurement":"°F"}},
      {"entity_id":"lock.front_door","state":"locked","attributes":{"friendly_name":"Front door"}},
      {"entity_id":"scene.movie_time","state":"2025-01-01T00:00:00","attributes":{}}
    ]"""

    @Test fun parsesInTileOrderAndMarksMissing() {
        val list = parseHaStates(states, listOf("sensor.outside", "light.living_room", "switch.gone"))
        assertEquals(listOf("Outside", "Living room", "switch.gone"), list.map { it.name })
        assertEquals("71 °F", list[0].display)
        assertTrue(list[1].isOn)
        assertTrue(list[2].missing)
        assertEquals("Not found", list[2].display)
    }

    @Test fun actionsAndSafety() {
        val list = parseHaStates(states, listOf("light.living_room", "sensor.outside", "lock.front_door", "scene.movie_time"))
        assertEquals("Turn off", list[0].action)
        assertNull(list[1].action)
        assertEquals("Unlock", list[2].action)
        assertTrue(list[2].needsConfirm)
        assertFalse(list[0].needsConfirm)
        assertEquals("movie time", list[3].name)
        assertEquals("scene" to "turn_on", HomeAssistant.serviceFor(list[3]))
        assertEquals("lock" to "unlock", HomeAssistant.serviceFor(list[2]))
        assertEquals("homeassistant" to "toggle", HomeAssistant.serviceFor(list[0]))
    }

    @Test fun badJsonGivesMissingRows() {
        val list = parseHaStates("not json", listOf("light.a"))
        assertTrue(list.single().missing)
    }

    @Test fun serviceOnlyRunsWhenSomethingNeedsIt() {
        assertFalse(CanvasService.needed(null))
        assertFalse(CanvasService.needed(Dashboard()))
        assertTrue(CanvasService.needed(Dashboard(openOnStartup = true)))
        assertTrue(CanvasService.needed(Dashboard(overlay = com.canvastv.dashboard.model.OverlaySpec(mode = OverlayMode.MESSAGES))))
    }

    @Test fun oldLayoutsLoadWithNewDefaults() {
        val d = Json { ignoreUnknownKeys = true }.decodeFromString(Dashboard.serializer(), """{"pages":[{"name":"Home"}]}""")
        assertFalse(d.openOnStartup)
        assertEquals(OverlayMode.OFF, d.overlay.mode)
        assertEquals(HaServer(), d.homeAssistant)
        assertFalse(d.homeAssistant.configured)
    }

    @Test fun entityDisplayText() {
        assertEquals("Unavailable", HaEntity("light.x", "X", "unavailable").display)
        assertEquals("Heat cool", HaEntity("climate.x", "X", "heat_cool").display)
        assertEquals("21.5 °C", HaEntity("sensor.t", "T", "21.5", "°C").display)
    }
}
