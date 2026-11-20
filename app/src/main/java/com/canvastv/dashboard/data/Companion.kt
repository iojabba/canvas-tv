package com.canvastv.dashboard.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A message sent to the TV from the phone page or a web command. */
@Serializable
data class TvMessage(val text: String, val from: String = "", val time: Long = System.currentTimeMillis())

/** Recent messages, kept on disk for the message board tile. */
object Messages {
    private val json = Json { ignoreUnknownKeys = true }
    var list by mutableStateOf<List<TvMessage>>(emptyList())
        private set
    /** The newest message while it's being shown as a banner. */
    var banner by mutableStateOf<TvMessage?>(null)
    private var file: File? = null

    fun load(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "messages.json")
        list = runCatching { json.decodeFromString(ListSerializer(TvMessage.serializer()), file!!.readText()) }.getOrDefault(emptyList())
    }

    fun add(m: TvMessage) {
        list = (listOf(m) + list).take(30)
        banner = m
        runCatching { file?.writeText(json.encodeToString(ListSerializer(TvMessage.serializer()), list)) }
    }

    fun clear() {
        list = emptyList()
        runCatching { file?.delete() }
    }
}

/**
 * What the phone page and web commands can do. The dashboard's view model implements it;
 * every call arrives on the main thread.
 */
interface CompanionApi {
    fun layoutJson(): String
    /** Returns null on success or an error message. */
    fun replaceLayoutJson(text: String): String?
    fun patchTile(pageIndex: Int, tileId: String, config: Map<String, String>): String?
    fun addTileFromWeb(pageIndex: Int, type: String, config: Map<String, String>): String?
    fun deleteTileFromWeb(pageIndex: Int, tileId: String): String?
    fun showPage(index: Int): String?
    fun appsJson(): String
    fun inputsJson(): String
}

/**
 * A tiny HTTP server for phones on the same Wi-Fi. Off by default. A phone pairs once with the
 * 6-digit code shown in Settings; after that it holds a random token (as a cookie or a
 * `Authorization: Bearer` header for automations). Nothing is reachable without that token
 * except the pairing page itself.
 */
object CompanionServer {
    const val PORT = 8765
    private const val TAG = "CanvasTV"
    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }
    private var socket: ServerSocket? = null
    private var pool = Executors.newFixedThreadPool(4)
    private var api: CompanionApi? = null
    private lateinit var prefs: android.content.SharedPreferences

    var running by mutableStateOf(false)
        private set
    var pairingCode by mutableStateOf("")
        private set
    private var failedPairs = 0
    private var pairLockedUntil = 0L

    fun attach(a: CompanionApi) { api = a }

    fun start(ctx: Context) {
        if (running) return
        prefs = ctx.getSharedPreferences("companion", Context.MODE_PRIVATE)
        Messages.load(ctx)
        if (pairingCode.isEmpty()) pairingCode = newCode()
        if (pool.isShutdown) pool = Executors.newFixedThreadPool(4)
        val ss = runCatching { ServerSocket(PORT) }.getOrElse { Log.w(TAG, "Companion server couldn't start", it); return }
        socket = ss
        running = true
        Thread({
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(c) }.onFailure { Log.w(TAG, "companion request failed", it) }; runCatching { c.close() } }
            }
        }, "companion-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        running = false
    }

    /** Forgets every paired phone and makes a new pairing code. */
    fun resetPairing() {
        if (::prefs.isInitialized) prefs.edit().remove("tokens").apply()
        pairingCode = newCode()
    }

    fun address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()?.let { "http://$it:$PORT" }

    private fun newCode() = (100000 + SecureRandom().nextInt(900000)).toString()
    private fun newToken() = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private fun tokens(): Set<String> = prefs.getStringSet("tokens", emptySet()).orEmpty()

    // ------------------------------------------------------------ HTTP

    private class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: String)

    private fun read(c: Socket): Req? {
        c.soTimeout = 10_000
        val inp = BufferedInputStream(c.getInputStream())
        fun line(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = inp.read(); if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar()); if (sb.length > 8192) return null
            }
        }
        val start = line() ?: return null
        val parts = start.split(' ')
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val h = line() ?: break
            if (h.isEmpty()) break
            headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
        }
        val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 2_000_000) ?: 0
        val body = ByteArray(len)
        var got = 0
        while (got < len) { val n = inp.read(body, got, len - got); if (n < 0) break; got += n }
        val target = parts[1]
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        return Req(parts[0].uppercase(), target.substringBefore('?'), query, headers, String(body, 0, got, Charsets.UTF_8))
    }

    private fun send(c: Socket, code: Int, type: String, body: String, extra: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = mapOf(200 to "OK", 302 to "Found", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found", 429 to "Too Many Requests", 500 to "Error")[code] ?: "OK"
        val out: OutputStream = c.getOutputStream()
        val head = buildString {
            append("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n")
            extra.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8)); out.write(bytes); out.flush()
    }

    private fun ok(c: Socket, text: String = """{"ok":true}""") = send(c, 200, "application/json; charset=utf-8", text)
    private fun err(c: Socket, code: Int, msg: String) = send(c, code, "application/json; charset=utf-8", """{"ok":false,"error":${kotlinx.serialization.json.JsonPrimitive(msg)}}""")

    /** Runs [block] on the main thread and waits for its result. */
    private fun <T> onMain(block: () -> T): T? {
        var r: T? = null
        val latch = CountDownLatch(1)
        main.post { try { r = block() } finally { latch.countDown() } }
        latch.await(5, TimeUnit.SECONDS)
        return r
    }

    private fun authed(r: Req): Boolean {
        val bearer = r.headers["authorization"]?.removePrefix("Bearer ")?.trim()
        val cookie = r.headers["cookie"]?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("ctv=") }?.removePrefix("ctv=")
        val t = tokens()
        return (bearer != null && bearer in t) || (cookie != null && cookie in t)
    }

    private fun handle(c: Socket) {
        val r = read(c) ?: return
        // Pairing doesn't need a token.
        if (r.path == "/pair" && r.method == "POST") return pair(c, r)
        if (r.path == "/" || r.path == "/index.html") {
            return send(c, 200, "text/html; charset=utf-8", if (authed(r)) CompanionPage.APP else CompanionPage.PAIR)
        }
        if (!authed(r)) return err(c, 401, "Not paired. Open the page and enter the code from the TV's Settings.")
        val a = api ?: return err(c, 500, "The dashboard isn't open")
        fun body(): JsonObject? = runCatching { json.parseToJsonElement(r.body).jsonObject }.getOrNull()
        fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
        fun JsonObject.cfg(): Map<String, String> = (this["config"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        when {
            r.method == "GET" && r.path == "/api/layout" -> {
                val text = onMain { a.layoutJson() } ?: return err(c, 500, "busy")
                if (r.query["download"] == "1") send(c, 200, "application/json", text, mapOf("Content-Disposition" to "attachment; filename=\"canvas-tv-layout.json\""))
                else ok(c, text)
            }
            r.method == "PUT" && r.path == "/api/layout" -> result(c, onMain { a.replaceLayoutJson(r.body) })
            r.method == "GET" && r.path == "/api/apps" -> ok(c, onMain { a.appsJson() } ?: "[]")
            r.method == "GET" && r.path == "/api/inputs" -> ok(c, onMain { a.inputsJson() } ?: "[]")
            r.method == "POST" && r.path == "/api/tile" -> {
                val b = body() ?: return err(c, 400, "Expected JSON")
                val page = b.str("page")?.toIntOrNull() ?: 0
                val id = b.str("id")
                result(c, onMain {
                    when (b.str("action")) {
                        "delete" -> a.deleteTileFromWeb(page, id ?: "")
                        "add" -> a.addTileFromWeb(page, b.str("type") ?: "", b.cfg())
                        else -> a.patchTile(page, id ?: "", b.cfg())
                    }
                })
            }
            // Web commands for automations (Home Assistant, Tasker, shortcuts, curl).
            r.path == "/api/message" && (r.method == "POST" || r.method == "GET") -> {
                val b = body()
                val text = (b?.str("text") ?: r.query["text"] ?: r.body.takeIf { b == null }).orEmpty().trim().take(500)
                if (text.isEmpty()) return err(c, 400, "Message text is empty")
                val from = (b?.str("from") ?: r.query["from"]).orEmpty().take(40)
                main.post { Messages.add(TvMessage(text, from)) }
                ok(c)
            }
            r.method == "POST" && r.path == "/api/token" -> {
                val token = newToken()
                prefs.edit().putStringSet("tokens", (tokens() + token).toList().takeLast(10).toSet()).apply()
                ok(c, """{"ok":true,"token":"$token"}""")
            }
            r.method == "DELETE" && r.path == "/api/message" -> { main.post { Messages.clear() }; ok(c) }
            r.path == "/api/page" && (r.method == "POST" || r.method == "GET") -> {
                val n = (body()?.str("page") ?: r.query["page"])?.toIntOrNull() ?: return err(c, 400, "Give a page number, starting at 1")
                result(c, onMain { a.showPage(n - 1) })
            }
            else -> err(c, 404, "Unknown address")
        }
    }

    private fun result(c: Socket, error: String?) = if (error == null) ok(c) else err(c, 400, error)

    private fun pair(c: Socket, r: Req) {
        val now = System.currentTimeMillis()
        if (now < pairLockedUntil) return err(c, 429, "Too many wrong codes. Try again in a minute.")
        val code = runCatching { json.parseToJsonElement(r.body).jsonObject["code"]?.jsonPrimitive?.content }.getOrNull()?.trim()
        if (code == null || code != pairingCode) {
            if (++failedPairs >= 5) { failedPairs = 0; pairLockedUntil = now + 60_000 }
            return err(c, 401, "That code doesn't match the one in the TV's Settings")
        }
        failedPairs = 0
        val token = newToken()
        prefs.edit().putStringSet("tokens", (tokens() + token).toList().takeLast(10).toSet()).apply()
        send(c, 200, "application/json", """{"ok":true,"token":"$token"}""",
            mapOf("Set-Cookie" to "ctv=$token; Max-Age=31536000; Path=/; HttpOnly; SameSite=Strict"))
    }
}
