package com.canvastv.dashboard.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class SpeedResult(val downMbps: Double, val upMbps: Double, val pingMs: Int, val time: Long)

data class SpeedState(
    val running: Boolean = false,
    val phase: String = "",
    val liveMbps: Double? = null,
    val last: SpeedResult? = null,
    val error: String? = null,
)

/**
 * Measures download, upload and latency against Cloudflare's public speed test endpoints
 * (speed.cloudflare.com), the same ones its web test uses. About 25 MB down and 5 MB up.
 */
object SpeedTest {
    private const val BASE = "https://speed.cloudflare.com"
    var state by mutableStateOf(SpeedState())
        private set
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val sp = ctx.getSharedPreferences("speedtest", Context.MODE_PRIVATE)
        if (sp.contains("t")) state = state.copy(
            last = SpeedResult(sp.getFloat("d", 0f).toDouble(), sp.getFloat("u", 0f).toDouble(), sp.getInt("p", 0), sp.getLong("t", 0)),
        )
    }

    fun run(ctx: Context, scope: CoroutineScope) {
        if (state.running) return
        load(ctx)
        val app = ctx.applicationContext
        scope.launch {
            state = state.copy(running = true, phase = "Checking ping…", liveMbps = null, error = null)
            try {
                val ping = withContext(Dispatchers.IO) { (1..5).map { timeMs { get("$BASE/__down?bytes=0") } }.sorted()[2] }
                state = state.copy(phase = "Testing download…")
                val down = withContext(Dispatchers.IO) { download(25_000_000) }
                state = state.copy(phase = "Testing upload…", liveMbps = null)
                val up = withContext(Dispatchers.IO) { upload(5_000_000) }
                val r = SpeedResult(down, up, ping.toInt(), System.currentTimeMillis())
                app.getSharedPreferences("speedtest", Context.MODE_PRIVATE).edit()
                    .putFloat("d", down.toFloat()).putFloat("u", up.toFloat()).putInt("p", r.pingMs).putLong("t", r.time).apply()
                state = SpeedState(last = r)
            } catch (e: Exception) {
                state = state.copy(running = false, phase = "", liveMbps = null, error = e.message ?: "network error")
            }
        }
    }

    private inline fun timeMs(block: () -> Unit): Long { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000 }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000; readTimeout = 15000; setRequestProperty("User-Agent", "CanvasTV")
    }

    private fun get(url: String) { val c = open(url); try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() } }

    private fun download(bytes: Int): Double {
        val c = open("$BASE/__down?bytes=$bytes")
        try {
            val buf = ByteArray(64 * 1024)
            var total = 0L
            val t0 = System.nanoTime()
            var lastShown = t0
            c.inputStream.use { s ->
                while (true) {
                    val n = s.read(buf); if (n < 0) break
                    total += n
                    val now = System.nanoTime()
                    if (now - lastShown > 300_000_000) { lastShown = now; state = state.copy(liveMbps = mbps(total, now - t0)) }
                }
            }
            return mbps(total, System.nanoTime() - t0)
        } finally { c.disconnect() }
    }

    private fun upload(bytes: Int): Double {
        val c = open("$BASE/__up")
        try {
            c.doOutput = true
            c.requestMethod = "POST"
            c.setFixedLengthStreamingMode(bytes)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            val chunk = ByteArray(64 * 1024)
            val t0 = System.nanoTime()
            c.outputStream.use { o ->
                var left = bytes
                while (left > 0) { val n = minOf(left, chunk.size); o.write(chunk, 0, n); left -= n }
            }
            c.inputStream.use { it.readBytes() }
            return mbps(bytes.toLong(), System.nanoTime() - t0)
        } finally { c.disconnect() }
    }

    private fun mbps(bytes: Long, nanos: Long) = bytes * 8.0 / (nanos / 1e9) / 1e6
}
