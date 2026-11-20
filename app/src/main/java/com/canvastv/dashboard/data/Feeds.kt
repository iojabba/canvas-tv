package com.canvastv.dashboard.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Pure parsing for the connected widgets, kept free of Android so it can be unit tested. */

fun splitList(s: String): List<String> = s.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }

// ---------------------------------------------------------------- RSS / Atom

data class Headline(val title: String, val source: String, val link: String, val time: Long, val image: String?)

fun parseFeed(xml: String, fallbackSource: String = ""): List<Headline> {
    val f = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
    }
    val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(xml.trim().toByteArray(Charsets.UTF_8)))
    val root = doc.documentElement
    fun Element.child(name: String): Element? {
        val nl = childNodes
        for (i in 0 until nl.length) { val n = nl.item(i); if (n is Element && (n.localName ?: n.nodeName) == name) return n }
        return null
    }
    fun Element.children(name: String): List<Element> {
        val out = mutableListOf<Element>(); val nl = childNodes
        for (i in 0 until nl.length) { val n = nl.item(i); if (n is Element && (n.localName ?: n.nodeName) == name) out += n }
        return out
    }
    fun Element.text(name: String) = child(name)?.textContent?.trim().orEmpty()
    fun image(e: Element): String? =
        e.children("content").firstOrNull { it.getAttribute("url").isNotBlank() && it.getAttribute("medium") != "video" }?.getAttribute("url")
            ?: e.children("thumbnail").firstOrNull()?.getAttribute("url")
            ?: e.children("enclosure").firstOrNull { it.getAttribute("type").startsWith("image") }?.getAttribute("url")
            ?: e.child("group")?.let { g -> g.children("content").firstOrNull()?.getAttribute("url") ?: g.children("thumbnail").firstOrNull()?.getAttribute("url") }
    return when (root.localName ?: root.nodeName) {
        "rss", "RDF" -> {
            val channel = root.child("channel")
            val source = channel?.text("title").orEmpty().ifBlank { fallbackSource }
            val items = (channel?.children("item").orEmpty() + root.children("item"))
            items.map { Headline(cleanTitle(it.text("title")), source, it.text("link"), parseFeedDate(it.text("pubDate").ifBlank { it.text("date") }), image(it)) }
        }
        "feed" -> {
            val source = root.text("title").ifBlank { fallbackSource }
            root.children("entry").map { e ->
                val link = e.children("link").firstOrNull { it.getAttribute("rel").let { r -> r.isBlank() || r == "alternate" } }?.getAttribute("href").orEmpty()
                Headline(cleanTitle(e.text("title")), source, link, parseFeedDate(e.text("updated").ifBlank { e.text("published") }), image(e))
            }
        }
        else -> emptyList()
    }.filter { it.title.isNotBlank() }
}

private fun cleanTitle(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").replace(Regex("\\s+"), " ").trim()

fun parseFeedDate(s: String): Long {
    if (s.isBlank()) return 0L
    return runCatching { ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(s.trim(), DateTimeFormatter.ISO_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(s.trim(), DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", Locale.US)).toInstant().toEpochMilli() }.getOrNull()
        ?: 0L
}

// ---------------------------------------------------------------- tickers

data class Quote(val symbol: String, val price: Double, val changePct: Double?)

/** Common crypto tickers → CoinGecko ids. Anything else is treated as a stock. */
val CryptoIds = mapOf(
    "BTC" to "bitcoin", "ETH" to "ethereum", "SOL" to "solana", "DOGE" to "dogecoin", "XRP" to "ripple",
    "ADA" to "cardano", "LTC" to "litecoin", "DOT" to "polkadot", "AVAX" to "avalanche-2", "LINK" to "chainlink",
)

/** Stooq's CSV: Symbol,Date,Time,Open,High,Low,Close,Volume. Change is against today's open. */
fun parseStooq(csv: String): List<Quote> = csv.lines().drop(1).mapNotNull { line ->
    val c = line.split(',')
    if (c.size < 7) return@mapNotNull null
    val close = c[6].toDoubleOrNull() ?: return@mapNotNull null
    val open = c[3].toDoubleOrNull()
    Quote(c[0].substringBefore('.').uppercase(), close, open?.takeIf { it > 0 }?.let { (close - it) / it * 100 })
}

fun parseCoinGecko(json: String, symbols: List<String>): List<Quote> {
    val root = Json.parseToJsonElement(json).jsonObject
    return symbols.mapNotNull { sym ->
        val o = root[CryptoIds[sym] ?: return@mapNotNull null]?.jsonObject ?: return@mapNotNull null
        Quote(sym, o["usd"]!!.jsonPrimitive.double, o["usd_24h_change"]?.jsonPrimitive?.doubleOrNull)
    }
}

// ---------------------------------------------------------------- iCalendar

data class CalEvent(val title: String, val start: LocalDateTime, val end: LocalDateTime, val allDay: Boolean, val location: String)

/**
 * Reads an .ics feed (for example Google Calendar's "secret address in iCal format") and returns the
 * events that overlap [from, from + days), in [zone]. Handles all-day events, TZID and UTC times,
 * folded lines, EXDATE, and the common RRULEs: DAILY, WEEKLY (with BYDAY), MONTHLY (by day of month)
 * and YEARLY, with INTERVAL, COUNT and UNTIL.
 */
fun parseIcs(text: String, from: LocalDateTime, days: Int, zone: ZoneId = ZoneId.systemDefault()): List<CalEvent> {
    val lines = text.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "").lines()
    val to = from.plusDays(days.toLong())
    val out = mutableListOf<CalEvent>()
    var props: MutableMap<String, Pair<Map<String, String>, String>>? = null
    var exdates = mutableListOf<Pair<Map<String, String>, String>>()
    for (raw in lines) {
        val line = raw.trimEnd()
        when {
            line == "BEGIN:VEVENT" -> { props = mutableMapOf(); exdates = mutableListOf() }
            line == "END:VEVENT" -> {
                props?.let { p -> runCatching { expand(p, exdates, from, to, zone, out) } }
                props = null
            }
            props != null && ':' in line -> {
                val head = line.substringBefore(':'); val value = line.substringAfter(':')
                val name = head.substringBefore(';').uppercase()
                val params = head.split(';').drop(1).associate { it.substringBefore('=').uppercase() to it.substringAfter('=') }
                if (name == "EXDATE") value.split(',').forEach { exdates += params to it } else props!![name] = params to value
            }
        }
    }
    return out.sortedBy { it.start }
}

private fun icsTime(params: Map<String, String>, v: String, zone: ZoneId): Pair<LocalDateTime, Boolean> {
    if (params["VALUE"] == "DATE" || (v.length == 8 && v.all { it.isDigit() })) {
        return LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay() to true
    }
    val base = LocalDateTime.parse(v.removeSuffix("Z"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
    val src: ZoneId = when {
        v.endsWith("Z") -> ZoneOffset.UTC
        params["TZID"] != null -> runCatching { ZoneId.of(params["TZID"]!!.trim('"')) }.getOrDefault(zone)
        else -> zone
    }
    return base.atZone(src).withZoneSameInstant(zone).toLocalDateTime() to false
}

private fun expand(
    p: Map<String, Pair<Map<String, String>, String>>,
    exdates: List<Pair<Map<String, String>, String>>,
    from: LocalDateTime, to: LocalDateTime, zone: ZoneId, out: MutableList<CalEvent>,
) {
    if (p["STATUS"]?.second.equals("CANCELLED", true)) return
    val (sp, sv) = p["DTSTART"] ?: return
    val (start, allDay) = icsTime(sp, sv, zone)
    val end = p["DTEND"]?.let { icsTime(it.first, it.second, zone).first }
        ?: if (allDay) start.plusDays(1) else start.plusHours(1)
    val length = Duration(start, end)
    val title = p["SUMMARY"]?.second?.unescapeIcs().orEmpty().ifBlank { "(No title)" }
    val location = p["LOCATION"]?.second?.unescapeIcs().orEmpty()
    val skip = exdates.map { icsTime(it.first, it.second, zone).first }.toSet()
    fun add(s: LocalDateTime) {
        val e = length.addTo(s)
        if (s !in skip && s < to && e > from) out += CalEvent(title, s, e, allDay, location)
    }
    val rule = p["RRULE"]?.second
    if (rule == null) { add(start); return }
    val r = rule.split(';').associate { it.substringBefore('=').uppercase() to it.substringAfter('=') }
    val interval = r["INTERVAL"]?.toLongOrNull()?.coerceAtLeast(1) ?: 1
    val count = r["COUNT"]?.toIntOrNull()
    val until = r["UNTIL"]?.let { icsTime(emptyMap(), it, zone).first.let { u -> if (it.length == 8) u.plusDays(1).minusSeconds(1) else u } }
    val byDay = r["BYDAY"]?.split(',')?.mapNotNull { d -> dayOf(d.takeLast(2)) }?.toSet()
    fun step(i: Long): LocalDateTime? = when (r["FREQ"]) {
        "DAILY" -> start.plusDays(i * interval)
        "WEEKLY" -> start.plusWeeks(i * interval)
        "MONTHLY" -> start.plusMonths(i * interval)
        "YEARLY" -> start.plusYears(i * interval)
        else -> null
    }
    // Without COUNT, jump close to the window instead of walking from a start years ago.
    var i = 0L
    if (count == null) {
        val unitsBetween = when (r["FREQ"]) {
            "DAILY" -> ChronoUnit.DAYS.between(start, from)
            "WEEKLY" -> ChronoUnit.WEEKS.between(start, from)
            "MONTHLY" -> ChronoUnit.MONTHS.between(start, from)
            "YEARLY" -> ChronoUnit.YEARS.between(start, from)
            else -> 0L
        }
        i = (unitsBetween / interval - 1).coerceAtLeast(0)
    }
    var n = 0
    while (i < 5000) {
        val cursor = step(i) ?: return
        val batch: List<LocalDateTime> = if (r["FREQ"] == "WEEKLY") {
            val weekStart = cursor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            (byDay ?: setOf(start.dayOfWeek)).sorted()
                .map { weekStart.with(TemporalAdjusters.nextOrSame(it)).withHour(start.hour).withMinute(start.minute).withSecond(start.second) }
                .filter { !it.isBefore(start) }
        } else listOf(cursor)
        for (s in batch) {
            if (until != null && s.isAfter(until)) return
            if (count != null && n >= count) return
            n++
            add(s)
        }
        if (cursor >= to) return
        i++
    }
}

private class Duration(a: LocalDateTime, b: LocalDateTime) {
    private val secs = ChronoUnit.SECONDS.between(a, b).coerceAtLeast(0)
    fun addTo(t: LocalDateTime): LocalDateTime = t.plusSeconds(secs)
}

private fun dayOf(code: String) = when (code.uppercase()) {
    "MO" -> DayOfWeek.MONDAY; "TU" -> DayOfWeek.TUESDAY; "WE" -> DayOfWeek.WEDNESDAY; "TH" -> DayOfWeek.THURSDAY
    "FR" -> DayOfWeek.FRIDAY; "SA" -> DayOfWeek.SATURDAY; "SU" -> DayOfWeek.SUNDAY; else -> null
}

private fun String.unescapeIcs() = replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

// ---------------------------------------------------------------- seasonal themes

data class SeasonLook(val name: String, val accent: String, val color1: String, val color2: String)

/** Built-in holiday looks for the "Seasonal themes" setting (US holidays). */
fun seasonFor(date: LocalDate): SeasonLook? {
    val y = date.year
    fun inRange(m1: Int, d1: Int, m2: Int, d2: Int): Boolean {
        val a = LocalDate.of(y, m1, d1); val b = LocalDate.of(y, m2, d2)
        return !date.isBefore(a) && !date.isAfter(b)
    }
    val thanksgiving = LocalDate.of(y, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY))
    return when {
        inRange(12, 31, 12, 31) || inRange(1, 1, 1, 1) -> SeasonLook("New Year", "#FFD54F", "#0B0B23", "#3A2C6B")
        inRange(2, 7, 2, 14) -> SeasonLook("Valentine's Day", "#FF6F91", "#2B0B1A", "#7A1F3D")
        inRange(3, 14, 3, 17) -> SeasonLook("St. Patrick's Day", "#7CD67C", "#0B2B12", "#1E6B33")
        inRange(7, 1, 7, 5) -> SeasonLook("Independence Day", "#FF5252", "#0A1A3F", "#1F3A7A")
        inRange(10, 15, 10, 31) -> SeasonLook("Halloween", "#FF9800", "#1A0B24", "#4A1F5C")
        !date.isBefore(thanksgiving.minusDays(3)) && !date.isAfter(thanksgiving.plusDays(1)) -> SeasonLook("Thanksgiving", "#FFB74D", "#2B160B", "#7A3E1F")
        inRange(12, 1, 12, 26) -> SeasonLook("Holidays", "#FF6B6B", "#0B2B1A", "#7A1F1F")
        else -> null
    }
}

/** Team-style color pairs for "Game day colors". Names describe the colors only. */
val GameDayColors = listOf(
    SeasonLook("Navy & lime", "#69BE28", "#002244", "#0B3A5C"),
    SeasonLook("Red & gold", "#FFB612", "#5A0A12", "#AA0000"),
    SeasonLook("Purple & gold", "#FDB927", "#2A1048", "#552583"),
    SeasonLook("Green & yellow", "#FFB81C", "#0F2A1D", "#203731"),
    SeasonLook("Navy & orange", "#FB4F14", "#0A1A33", "#1C2F57"),
    SeasonLook("Black & silver", "#C4CED4", "#000000", "#2B2B2B"),
    SeasonLook("Royal blue & white", "#E8F0FF", "#00205B", "#0046AD"),
    SeasonLook("Teal & black", "#00B2A9", "#04181A", "#0B3B3C"),
)
