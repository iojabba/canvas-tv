package com.canvastv.dashboard

import com.canvastv.dashboard.data.Shows
import com.canvastv.dashboard.data.cleanTitle
import com.canvastv.dashboard.data.parseOpenGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShowsTest {
    @Test fun recognizesServices() {
        assertEquals("Netflix", Shows.serviceFor("https://www.netflix.com/title/80117401")?.name)
        assertEquals("YouTube", Shows.serviceFor("youtu.be/abc")?.name)
        assertEquals("Disney+", Shows.serviceFor("https://www.disneyplus.com/series/bluey/abc")?.name)
        assertEquals("HBO Max", Shows.serviceFor("https://play.max.com/show/123")?.name)
        assertEquals("MGM+", Shows.serviceFor("https://www.mgmplus.com/series/from")?.name)
        assertEquals("Starz", Shows.serviceFor("https://www.starz.com/series/69342")?.name)
        assertEquals("Prime Video", Shows.serviceFor("https://www.amazon.com/gp/video/detail/B0B8")?.name)
        assertNull(Shows.serviceFor("https://example.com/show"))
        assertNull(Shows.serviceFor("https://notnetflix.com/title/1"))
    }

    @Test fun netflixIds() {
        assertEquals("80117401", Shows.netflixId("https://www.netflix.com/title/80117401?s=a&trkid=1"))
        assertEquals("80117401", Shows.netflixId("https://www.netflix.com/us-en/title/80117401"))
        assertEquals("81234", Shows.netflixId("netflix.com/watch/81234"))
        assertNull(Shows.netflixId("https://www.netflix.com/browse"))
    }

    @Test fun normalizesLinks() {
        assertEquals("https://netflix.com/title/1", Shows.normalize("  netflix.com/title/1 "))
        assertEquals("http://x.com", Shows.normalize("http://x.com"))
    }

    @Test fun readsOpenGraph() {
        val html = """<html><head><title>ignored</title>
            <meta content="https://img.example/show.jpg?a=1&amp;b=2" property="og:image">
            <meta property='og:title' content='Watch Stranger Things | Netflix Official Site'>
            </head></html>"""
        val (t, i) = parseOpenGraph(html)
        assertEquals("Stranger Things", t)
        assertEquals("https://img.example/show.jpg?a=1&b=2", i)
    }

    @Test fun fallsBackToTitleTag() {
        val (t, i) = parseOpenGraph("<title>Bluey &#8211; Disney+</title><meta name=\"twitter:image\" content=\"https://i/x.png\">")
        assertEquals("Bluey", t)
        assertEquals("https://i/x.png", i)
    }

    @Test fun cleansTitles() {
        assertEquals("Severance", cleanTitle("Watch Severance | Apple TV+"))
        assertEquals("The Bear", cleanTitle("The Bear - Hulu"))
        assertEquals("Spider-Man", cleanTitle("Spider-Man"))
    }
}
