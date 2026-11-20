package com.canvastv.dashboard

import com.canvastv.dashboard.data.parseCoinGecko
import com.canvastv.dashboard.data.parseFeed
import com.canvastv.dashboard.data.parseIcs
import com.canvastv.dashboard.data.parseStooq
import com.canvastv.dashboard.data.seasonFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class FeedsTest {
    private val la = ZoneId.of("America/Los_Angeles")

    @Test fun `rss 2 with media thumbnails`() {
        val xml = """<?xml version="1.0"?><rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel><title>Example News</title>
            <item><title>First &amp; best</title><link>https://e.x/1</link><pubDate>Fri, 20 Nov 2026 15:00:00 GMT</pubDate><media:thumbnail url="https://e.x/1.jpg"/></item>
            <item><title><![CDATA[Second <b>story</b>]]></title><link>https://e.x/2</link></item></channel></rss>"""
        val h = parseFeed(xml)
        assertEquals(2, h.size)
        assertEquals("First & best", h[0].title)
        assertEquals("Example News", h[0].source)
        assertEquals("https://e.x/1.jpg", h[0].image)
        assertTrue(h[0].time > 0)
        assertEquals("Second story", h[1].title)
    }

    @Test fun `atom feed`() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Blog</title>
            <entry><title>Hello</title><link rel="alternate" href="https://b/1"/><updated>2026-11-19T10:00:00Z</updated></entry></feed>"""
        val h = parseFeed(xml).single()
        assertEquals("Hello", h.title); assertEquals("https://b/1", h.link); assertEquals("Blog", h.source)
    }

    @Test fun `stooq and coingecko quotes`() {
        val csv = "Symbol,Date,Time,Open,High,Low,Close,Volume\nAAPL.US,2026-11-20,22:00:09,200,210,199,210,123\nMSFT.US,N/D,N/D,N/D,N/D,N/D,N/D,N/D\n"
        val q = parseStooq(csv).single()
        assertEquals("AAPL", q.symbol); assertEquals(210.0, q.price, 0.001); assertEquals(5.0, q.changePct!!, 0.001)
        val cg = """{"bitcoin":{"usd":91234.5,"usd_24h_change":-1.25},"ethereum":{"usd":3100}}"""
        val c = parseCoinGecko(cg, listOf("BTC", "ETH", "AAPL"))
        assertEquals(listOf("BTC", "ETH"), c.map { it.symbol }); assertEquals(-1.25, c[0].changePct!!, 0.001); assertNull(c[1].changePct)
    }

    private val ics = """BEGIN:VCALENDAR
BEGIN:VEVENT
DTSTART;VALUE=DATE:20261121
DTEND;VALUE=DATE:20261122
SUMMARY:Saturday chores
END:VEVENT
BEGIN:VEVENT
DTSTART:20261120T230000Z
DTEND:20261121T000000Z
SUMMARY:Dinner\, family
LOCATION:Home
END:VEVENT
BEGIN:VEVENT
DTSTART;TZID=America/New_York:20250106T200000
DTEND;TZID=America/New_York:20250106T210000
RRULE:FREQ=WEEKLY;BYDAY=MO,TH
EXDATE;TZID=America/New_York:20261123T200000
SUMMARY:Game night
END:VEVENT
BEGIN:VEVENT
DTSTART:20261120T170000Z
RRULE:FREQ=DAILY;COUNT=2
SUMMARY:Short daily
END:VEVENT
BEGIN:VEVENT
DTSTART:20261122T170000Z
STATUS:CANCELLED
SUMMARY:Cancelled thing
END:VEVENT
BEGIN:VEVENT
DTSTART;VALUE=DATE:19900304
RRULE:FREQ=YEARLY
SUMMARY:Mom's birthday
END:VEVENT
END:VCALENDAR"""

    @Test fun `ics events in the next week`() {
        val from = LocalDateTime.of(2026, 11, 20, 0, 0)
        val ev = parseIcs(ics, from, 7, la)
        val titles = ev.map { it.title }
        assertTrue("chores" , "Saturday chores" in titles)
        val dinner = ev.first { it.title == "Dinner, family" }
        assertEquals(LocalDateTime.of(2026, 11, 20, 15, 0), dinner.start) // 23:00Z = 3 PM Pacific
        assertEquals("Home", dinner.location)
        // Game night: Mon Nov 23 is excluded, Thu Nov 26 at 8 PM New York = 5 PM Pacific
        val games = ev.filter { it.title == "Game night" }.map { it.start }
        assertEquals(listOf(LocalDateTime.of(2026, 11, 26, 17, 0)), games)
        assertEquals(2, ev.count { it.title == "Short daily" })
        assertTrue("Cancelled thing" !in titles)
        assertTrue("Mom's birthday" !in titles)
        // Yearly birthday shows up when the window covers March
        val march = parseIcs(ics, LocalDateTime.of(2027, 3, 1, 0, 0), 7, la)
        assertEquals(LocalDate.of(2027, 3, 4), march.single { it.title == "Mom's birthday" }.start.toLocalDate())
    }

    @Test fun `seasons`() {
        assertEquals("Halloween", seasonFor(LocalDate.of(2026, 10, 31))?.name)
        assertEquals("Thanksgiving", seasonFor(LocalDate.of(2026, 11, 26))?.name) // 4th Thursday 2026
        assertEquals("Holidays", seasonFor(LocalDate.of(2026, 12, 20))?.name)
        assertEquals("New Year", seasonFor(LocalDate.of(2027, 1, 1))?.name)
        assertNull(seasonFor(LocalDate.of(2026, 6, 10)))
    }
}
