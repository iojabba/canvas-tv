package com.canvastv.dashboard.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What TVmaze (free, no key) knows about a show: its pictures and its latest and next episodes. */
data class ShowEpisodes(
    val tvmazeId: Int,
    val name: String,
    /** A wide picture for the tile, if TVmaze has one. */
    val background: String?,
    val poster: String?,
    val lastAired: Instant?,
    val lastLabel: String?,
    val nextAirs: Instant?,
    val nextLabel: String?,
)

object Episodes {
    private val json = Json { ignoreUnknownKeys = true }
    private const val API = "https://api.tvmaze.com"

    /**
     * Finds the show by name (preferring the one on [service], for names like "The Body"),
     * or by TVmaze number when given, and returns TVmaze's JSON for it with episodes and pictures.
     */
    suspend fun fetch(name: String, service: String?, tvmazeId: Int?): String {
        val id = tvmazeId ?: run {
            val hits = json.parseToJsonElement(httpGet("$API/search/shows?q=${URLEncoder.encode(name, "UTF-8")}")).jsonArray
                .mapNotNull { (it as? JsonObject)?.get("show") as? JsonObject }
            pickShow(hits, name, service) ?: error("Not found on TVmaze")
        }
        return httpGet("$API/shows/$id?embed[]=previousepisode&embed[]=nextepisode&embed[]=images")
    }

    /** The search hit on the right service, else the closest name, else TVmaze's top hit. */
    fun pickShow(hits: List<JsonObject>, name: String, service: String?): Int? {
        fun channel(s: JsonObject) = listOfNotNull(
            ((s["webChannel"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content,
            ((s["network"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content,
        )
        fun sameName(s: JsonObject) = (s["name"] as? JsonPrimitive)?.content?.let { norm(it) == norm(name) } == true
        fun onService(s: JsonObject) = service != null && channel(s).any { c -> serviceMatches(c, service) }
        val best = hits.firstOrNull { sameName(it) && onService(it) }
            ?: hits.firstOrNull { onService(it) }
            ?: hits.firstOrNull { sameName(it) }
            ?: hits.firstOrNull()
        return (best?.get("id") as? JsonPrimitive)?.intOrNull
    }

    /** TVmaze calls them "Apple TV+", "HBO Max", "Prime Video", "STARZ", "FXX"… */
    private fun serviceMatches(channel: String, service: String): Boolean {
        val c = norm(channel); val s = norm(service)
        if (c.contains(s) || s.contains(c)) return true
        return when (s) {
            "hbomax" -> c in setOf("hbo", "max")
            "hulu" -> c in setOf("fx", "fxx", "fxnetworks", "abc")
            "primevideo" -> c.contains("amazon")
            "appletv" -> c.contains("apple")
            "mgm" -> c.contains("mgm") || c.contains("epix")
            else -> false
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "").removeSuffix("plus")

    fun parse(text: String?): ShowEpisodes? = runCatching {
        val o = json.parseToJsonElement(text!!).jsonObject
        val emb = o["_embedded"] as? JsonObject
        fun ep(k: String) = emb?.get(k) as? JsonObject
        fun stamp(e: JsonObject?) = (e?.get("airstamp") as? JsonPrimitive)?.content?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: (e?.get("airdate") as? JsonPrimitive)?.content?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneId.of("America/New_York")).toInstant() }.getOrNull() }
        fun label(e: JsonObject?): String? {
            e ?: return null
            val s = (e["season"] as? JsonPrimitive)?.intOrNull ?: return null
            val n = (e["number"] as? JsonPrimitive)?.intOrNull
            return if (n != null) "S${s}E$n" else "Season $s"
        }
        val images = (emb?.get("images") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        fun url(img: JsonObject, size: String) = ((img["resolutions"] as? JsonObject)?.get(size) as? JsonObject)?.get("url")?.jsonPrimitive?.content
        val wide = images.filter { it["type"]?.jsonPrimitive?.content == "background" }
            .sortedByDescending { it["main"]?.jsonPrimitive?.content == "true" }
            .firstNotNullOfOrNull { url(it, "medium") ?: url(it, "original") }
            ?: images.firstOrNull { it["type"]?.jsonPrimitive?.content == "banner" }?.let { url(it, "original") }
        val posterObj = o["image"] as? JsonObject
        ShowEpisodes(
            tvmazeId = o["id"]!!.jsonPrimitive.intOrNull!!,
            name = o["name"]?.jsonPrimitive?.content.orEmpty(),
            background = wide,
            poster = (posterObj?.get("original") ?: posterObj?.get("medium"))?.jsonPrimitive?.content,
            lastAired = stamp(ep("previousepisode")),
            lastLabel = label(ep("previousepisode")),
            nextAirs = stamp(ep("nextepisode")),
            nextLabel = label(ep("nextepisode")),
        )
    }.getOrNull()

    /**
     * True when an episode came out after the show was last opened from its tile. A show never
     * opened from Canvas TV counts as new for a week after an episode airs.
     */
    fun isNew(e: ShowEpisodes?, lastOpenedMs: Long?, now: Instant = Instant.now()): Boolean {
        val aired = e?.lastAired ?: return false
        if (aired.isAfter(now)) return false
        return if (lastOpenedMs != null) aired.toEpochMilli() > lastOpenedMs
        else aired.isAfter(now.minusSeconds(7L * 24 * 3600))
    }
}
