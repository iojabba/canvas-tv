package com.canvastv.dashboard

import com.canvastv.dashboard.model.Tile
import com.canvastv.dashboard.model.TileType
import com.canvastv.dashboard.ui.tiles.countdownTarget
import com.canvastv.dashboard.ui.tiles.folderApps
import com.canvastv.dashboard.ui.tiles.parseWeather
import com.canvastv.dashboard.ui.tiles.weatherText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class WidgetTest {
    private fun cd(vararg cfg: Pair<String, String>) = Tile(type = TileType.COUNTDOWN, x = 0, y = 0, w = 3, h = 2, config = mapOf(*cfg))

    @Test fun `one-off countdown keeps its date`() {
        val now = LocalDateTime.of(2026, 11, 20, 9, 0)
        assertEquals(LocalDateTime.of(2026, 12, 25, 0, 0), countdownTarget(cd("date" to "2026-12-25"), now))
        assertEquals(LocalDateTime.of(2026, 12, 25, 18, 30), countdownTarget(cd("date" to "2026-12-25", "time" to "18:30"), now))
    }

    @Test fun `yearly countdown rolls to next year after the day ends`() {
        val now = LocalDateTime.of(2026, 11, 20, 9, 0)
        // Birthday earlier in the year -> next year
        assertEquals(LocalDateTime.of(2027, 3, 4, 0, 0), countdownTarget(cd("date" to "1990-03-04", "yearly" to "true"), now))
        // Birthday is today -> stays today all day
        assertEquals(LocalDateTime.of(2026, 11, 20, 0, 0), countdownTarget(cd("date" to "1990-11-20", "yearly" to "true"), now))
        // Later this year -> this year
        assertEquals(LocalDateTime.of(2026, 12, 1, 0, 0), countdownTarget(cd("date" to "2001-12-01", "yearly" to "true"), now))
    }

    @Test fun `bad date gives no target`() {
        assertNull(countdownTarget(cd("date" to "next friday")))
        assertNull(countdownTarget(cd()))
    }

    @Test fun `folder list parsing ignores blanks`() {
        val t = Tile(type = TileType.FOLDER, x = 0, y = 0, w = 2, h = 1, config = mapOf("pkgs" to "a.b, c.d,,"))
        assertEquals(listOf("a.b", "c.d"), folderApps(t))
    }

    @Test fun `parses an Open-Meteo forecast`() {
        // Shape of api.open-meteo.com/v1/forecast with current + daily, wrapped with the place name.
        val text = """{"place":"Seattle, Washington","forecast":{"latitude":47.6,"longitude":-122.3,
          "current_units":{"temperature_2m":"°F"},
          "current":{"time":"2026-11-20T08:45","interval":900,"temperature_2m":44.6,"weather_code":61,"relative_humidity_2m":91,"wind_speed_10m":7.4},
          "daily_units":{"temperature_2m_max":"°F"},
          "daily":{"time":["2026-11-20","2026-11-21","2026-11-22","2026-11-23"],
                   "weather_code":[61,3,2,80],
                   "temperature_2m_max":[48.1,50.0,51.4,47.2],
                   "temperature_2m_min":[40.3,41.0,39.9,38.5]}}}"""
        val w = parseWeather(text)
        assertNotNull(w)
        w!!
        assertEquals("Seattle, Washington", w.place)
        assertEquals(45, w.tempNow)
        assertEquals("Rain", weatherText(w.codeNow).second)
        assertEquals(4, w.days.size)
        assertEquals(48 to 40, w.days[0].third)
    }

    @Test fun `garbage weather text is ignored`() {
        assertNull(parseWeather("{}"))
        assertNull(parseWeather("not json"))
    }

    @Test fun `weather extras - air quality and alerts`() {
        val text = """{"place":"Here","forecast":{"current":{"temperature_2m":70,"weather_code":0},
          "daily":{"time":["2026-11-20"],"weather_code":[0],"temperature_2m_max":[75],"temperature_2m_min":[60]}},
          "aq":{"current":{"us_aqi":132.4,"pm2_5":40}},
          "alerts":{"features":[{"properties":{"event":"Wind Advisory","severity":"Minor"}},{"properties":{"event":"Red Flag Warning","severity":"Severe"}}]}}"""
        val w = parseWeather(text)!!
        assertEquals(132, w.aqi)
        assertEquals("Unhealthy for sensitive groups", com.canvastv.dashboard.ui.tiles.aqiLabel(w.aqi!!))
        assertEquals(listOf("Red Flag Warning", "Wind Advisory"), w.alerts)
    }

    @Test fun `bing daily image address`() {
        val j = """{"images":[{"url":"/th?id=OHR.Example_EN-US123_1920x1080.jpg&rf=x&pid=hp","title":"x"}]}"""
        assertEquals("https://www.bing.com/th?id=OHR.Example_EN-US123_1920x1080.jpg&rf=x&pid=hp", com.canvastv.dashboard.ui.bingImageUrl(j))
        assertNull(com.canvastv.dashboard.ui.bingImageUrl("{}"))
    }
}
