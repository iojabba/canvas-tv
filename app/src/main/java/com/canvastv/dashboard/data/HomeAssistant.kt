package com.canvastv.dashboard.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.canvastv.dashboard.model.HaServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder

/** One Home Assistant entity as the tile shows it. */
data class HaEntity(
    val id: String,
    val name: String,
    val state: String,
    val unit: String = "",
    val missing: Boolean = false,
) {
    val domain: String get() = id.substringBefore('.')

    val isOn: Boolean get() = state in ON_STATES

    /** What OK does for this entity, or null if it's read-only (sensors and the like). */
    val action: String? get() = when (domain) {
        "scene", "script" -> "Run"
        "button", "input_button" -> "Press"
        "lock" -> if (state == "locked") "Unlock" else "Lock"
        "cover" -> if (state == "open" || state == "opening") "Close" else "Open"
        "media_player" -> if (state == "playing") "Pause" else "Play"
        in TOGGLES -> if (isOn) "Turn off" else "Turn on"
        else -> null
    }

    /** Locks and garage doors ask for a second press. */
    val needsConfirm: Boolean get() = domain == "lock" || domain == "cover" || domain == "alarm_control_panel"

    /** The state in words: "On", "72 °F", "Locked". */
    val display: String get() = when {
        missing -> "Not found"
        state == "unavailable" -> "Unavailable"
        state == "unknown" -> "—"
        unit.isNotEmpty() -> "${state.toDoubleOrNull()?.let { trim(it) } ?: state} $unit"
        else -> state.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    companion object {
        private val ON_STATES = setOf("on", "open", "opening", "unlocked", "playing", "home", "heat", "cool", "heat_cool", "auto", "cleaning")
        private val TOGGLES = setOf("light", "switch", "fan", "input_boolean", "automation", "group", "humidifier", "siren", "climate", "vacuum")
        private fun trim(d: Double) = if (d == Math.floor(d) && kotlin.math.abs(d) < 1e9) d.toLong().toString() else "%.1f".format(d)
    }
}

/** Parses one /api/states/<id> object (or an element of /api/states). */
fun parseHaEntity(obj: JsonObject): HaEntity? = runCatching {
    val id = obj["entity_id"]!!.jsonPrimitive.content
    val attrs = obj["attributes"] as? JsonObject
    HaEntity(
        id = id,
        name = (attrs?.get("friendly_name") as? JsonPrimitive)?.content ?: id.substringAfter('.').replace('_', ' '),
        state = obj["state"]?.jsonPrimitive?.content ?: "unknown",
        unit = (attrs?.get("unit_of_measurement") as? JsonPrimitive)?.content.orEmpty(),
    )
}.getOrNull()

/** Parses the tile's cached text: a JSON array of state objects, in the tile's order. */
fun parseHaStates(text: String, ids: List<String>): List<HaEntity> {
    val byId = runCatching { HomeAssistant.json.parseToJsonElement(text).jsonArray }.getOrDefault(JsonArray(emptyList()))
        .mapNotNull { (it as? JsonObject)?.let(::parseHaEntity) }.associateBy { it.id }
    return ids.map { byId[it] ?: HaEntity(it, it, "unknown", missing = true) }
}

object HomeAssistant {
    internal val json = Json { ignoreUnknownKeys = true }

    /** Goes up after every button press, so tiles refresh right away instead of on their timer. */
    var revision by mutableIntStateOf(0)
        private set

    private fun base(s: HaServer) = s.url.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }
    private fun auth(s: HaServer) = mapOf("Authorization" to "Bearer ${s.token.trim()}")

    /** Fetches [ids] one by one (a whole-house /api/states can be megabytes) and returns them as one JSON array. */
    suspend fun fetchStates(s: HaServer, ids: List<String>): String {
        var anyOk = false
        var lastError: Exception? = null
        val parts = ids.mapNotNull { id ->
            try {
                httpRequest("${base(s)}/api/states/${URLEncoder.encode(id, "UTF-8")}", headers = auth(s)).also { anyOk = true }
            } catch (e: Exception) {
                // A wrong entity id is a 404 for that row only; anything else (offline, bad token) fails the tile.
                if (e.message != "HTTP 404") lastError = e
                null
            }
        }
        if (!anyOk && lastError != null) throw lastError!!
        return "[" + parts.joinToString(",") + "]"
    }

    /** Everything Home Assistant knows about, for the device picker: (id, name, state), sorted by area-ish domain. */
    suspend fun listAll(s: HaServer): List<HaEntity> {
        val text = httpRequest("${base(s)}/api/states", headers = auth(s))
        return json.parseToJsonElement(text).jsonArray.mapNotNull { (it as? JsonObject)?.let(::parseHaEntity) }
            .filter { it.domain in PICKABLE }
            .sortedWith(compareBy({ PICKABLE.indexOf(it.domain) }, { it.name.lowercase() }))
    }

    /** Checks the address and token. Returns null when it works, or what went wrong in plain words. */
    suspend fun test(s: HaServer): String? = try {
        val r = json.parseToJsonElement(httpRequest("${base(s)}/api/", headers = auth(s))).jsonObject
        if (r["message"] != null) null else "Unexpected answer from that address"
    } catch (e: Exception) {
        when (e.message) {
            "HTTP 401" -> "Home Assistant refused the token"
            "HTTP 404" -> "That address isn't Home Assistant's API"
            else -> "Couldn't reach Home Assistant (${e.message ?: e.javaClass.simpleName})"
        }
    }

    /** Runs the entity's main action (toggle, run, press, lock/unlock…). */
    suspend fun activate(s: HaServer, e: HaEntity) {
        val (domain, service) = serviceFor(e)
        val body = buildJsonObject { put("entity_id", e.id) }.toString()
        try {
            httpRequest("${base(s)}/api/services/$domain/$service", method = "POST", headers = auth(s), body = body)
        } finally {
            revision++
        }
    }

    fun serviceFor(e: HaEntity): Pair<String, String> = when (e.domain) {
        "scene", "script" -> e.domain to "turn_on"
        "button", "input_button" -> e.domain to "press"
        "lock" -> "lock" to if (e.state == "locked") "unlock" else "lock"
        "cover" -> "cover" to "toggle"
        "media_player" -> "media_player" to "media_play_pause"
        else -> "homeassistant" to "toggle"
    }

    private val PICKABLE = listOf(
        "light", "switch", "fan", "scene", "script", "lock", "cover", "climate", "media_player",
        "input_boolean", "button", "input_button", "vacuum", "sensor", "binary_sensor", "weather", "person",
    )
}
