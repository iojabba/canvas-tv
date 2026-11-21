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
