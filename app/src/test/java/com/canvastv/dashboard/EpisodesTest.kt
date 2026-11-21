package com.canvastv.dashboard

import com.canvastv.dashboard.data.Episodes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EpisodesTest {
    private val show = """{"id":44778,"name":"Silo","image":{"medium":"https://p/m.jpg","original":"https://p/o.jpg"},
      "_embedded":{
        "previousepisode":{"season":2,"number":10,"airdate":"2025-01-17","airstamp":"2025-01-17T05:00:00+00:00"},
        "nextepisode":{"season":3,"number":1,"airstamp":"2026-11-28T05:00:00+00:00"},
        "images":[{"type":"poster","main":true,"resolutions":{"original":{"url":"https://i/poster.jpg"}}},
                  {"type":"background","main":false,"resolutions":{"original":{"url":"https://i/bg2.jpg"}}},
                  {"type":"background","main":true,"resolutions":{"original":{"url":"https://i/bg.jpg"},"medium":{"url":"https://i/bg-m.jpg"}}}]}}"""

    @Test fun parsesEpisodesAndPictures() {
        val e = Episodes.parse(show)!!
        assertEquals(44778, e.tvmazeId)
        assertEquals("https://i/bg-m.jpg", e.background)
        assertEquals("https://p/o.jpg", e.poster)
        assertEquals("S2E10", e.lastLabel)
        assertEquals(Instant.parse("2025-01-17T05:00:00Z"), e.lastAired)
        assertEquals("S3E1", e.nextLabel)
    }

    @Test fun newBadgeRules() {
        val e = Episodes.parse(show)!!
        val aired = e.lastAired!!.toEpochMilli()
        val soon = e.lastAired!!.plusSeconds(3600)
        assertTrue(Episodes.isNew(e, aired - 1000, soon))      // opened before it aired
        assertFalse(Episodes.isNew(e, aired + 1000, soon))     // opened after
        assertTrue(Episodes.isNew(e, null, soon))              // never opened, aired an hour ago
        assertFalse(Episodes.isNew(e, null, e.lastAired!!.plusSeconds(8L * 86400))) // never opened, old episode
        assertFalse(Episodes.isNew(null, null))
    }

    @Test fun picksTheShowOnTheRightService() {
        val hits = Json.parseToJsonElement("""[
          {"id":1,"name":"The Body","network":{"name":"BBC One"}},
          {"id":2,"name":"The Body","webChannel":{"name":"Netflix"}},
          {"id":3,"name":"Bodies","webChannel":{"name":"Netflix"}}]""").jsonArray.map { it.jsonObject }
        assertEquals(2, Episodes.pickShow(hits, "The Body", "Netflix"))
        assertEquals(1, Episodes.pickShow(hits, "The Body", null))
        assertEquals(1, Episodes.pickShow(hits, "The Body", "Hulu"))
        val max = Json.parseToJsonElement("""[{"id":9,"name":"The Pitt","network":{"name":"HBO"}},{"id":8,"name":"The Pitt","webChannel":{"name":"Max"}}]""").jsonArray.map { it.jsonObject }
        assertEquals(9, Episodes.pickShow(max, "The Pitt", "HBO Max"))
    }

    @Test fun badJson() = assertNull(Episodes.parse("nope"))

    @Test fun nextEpisodeDay() {
        val zone = java.time.ZoneId.of("America/Los_Angeles")
        val now = Instant.parse("2026-11-21T08:00:00Z") // Sat 12 AM in Lacey
        fun at(s: String) = Episodes.parse(show.replace("2026-11-28T05:00:00+00:00", s))
        assertEquals("Tonight", Episodes.nextLabel(at("2026-11-22T04:00:00+00:00"), now, zone))   // Sat 8 PM
        assertEquals("Tomorrow", Episodes.nextLabel(at("2026-11-23T02:00:00+00:00"), now, zone))  // Sun 6 PM
        assertEquals("Next: Thu", Episodes.nextLabel(at("2026-11-27T02:00:00+00:00"), now, zone)) // Thu 6 PM
        assertEquals("Next: Dec 4", Episodes.nextLabel(at("2026-12-05T02:00:00+00:00"), now, zone))
        assertNull(Episodes.nextLabel(at("2026-11-20T02:00:00+00:00"), now, zone))
        assertNull(Episodes.nextLabel(null, now, zone))
    }

    @Test fun searchResultsAndServices() {
        val hits = Episodes.parseSearch("""[
          {"score":0.9,"show":{"id":2,"name":"Love Is Blind","premiered":"2020-02-13","webChannel":{"name":"Netflix"},"network":null,"officialSite":"https://www.netflix.com/title/81185694"}},
          {"score":0.5,"show":{"id":5,"name":"The Pitt","premiered":"2025-01-09","webChannel":{"name":"HBO Max"},"officialSite":null}}]""")
        assertEquals(2, hits.size)
        assertEquals("2020", hits[0].year)
        assertEquals("Netflix", hits[0].channel)
        assertEquals("", hits[1].officialSite)
        assertEquals("HBO Max", com.canvastv.dashboard.data.Shows.serviceForChannel("HBO")?.name)
        assertEquals("Hulu", com.canvastv.dashboard.data.Shows.serviceForChannel("FXX")?.name)
        assertEquals("Apple TV", com.canvastv.dashboard.data.Shows.serviceForChannel("Apple TV+")?.name)
        assertEquals("Prime Video", com.canvastv.dashboard.data.Shows.serviceForChannel("Prime Video")?.name)
        assertNull(com.canvastv.dashboard.data.Shows.serviceForChannel(""))
        assertEquals(emptyList<Any>(), Episodes.parseSearch("garbage"))
    }
}

class NewShowsFirstTest {
    @Test fun newShowsTakeTheFirstSpots() {
        val a = com.canvastv.dashboard.model.Tile(id = "a", type = com.canvastv.dashboard.model.TileType.SHOW, x = 0, y = 0, w = 2, h = 1)
        val b = a.copy(id = "b", x = 2)
        val c = a.copy(id = "c", x = 4)
        val clock = com.canvastv.dashboard.model.Tile(id = "k", type = com.canvastv.dashboard.model.TileType.CLOCK, x = 0, y = 2, w = 4, h = 2)
        val out = com.canvastv.dashboard.ui.tiles.newShowsFirst(listOf(a, b, c, clock)) { it.id == "c" }.associateBy { it.id }
        assertEquals(0, out["c"]!!.x)
        assertEquals(2, out["a"]!!.x)
        assertEquals(4, out["b"]!!.x)
        assertEquals(clock, out["k"])
    }
}
