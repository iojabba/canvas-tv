package com.canvastv.dashboard.data

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The widget framework's data side: each online widget names a cache key and a refresh
 * interval, and gets back the last good result (kept on disk, so it shows offline and
 * right after a reboot) plus whether the latest refresh failed.
 */
data class RemoteData(
    val text: String?,
    val updatedAt: Long,
    val error: String? = null,
) {
    val stale: Boolean get() = error != null
}

object WidgetCache {
    private fun dir(ctx: Context) = File(ctx.cacheDir, "widgets").apply { mkdirs() }
    private fun file(ctx: Context, key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(ctx), "$h.txt")
    }

    fun read(ctx: Context, key: String): RemoteData? = runCatching {
        val f = file(ctx, key)
        if (f.exists()) RemoteData(f.readText(), f.lastModified()) else null
    }.getOrNull()

    fun write(ctx: Context, key: String, text: String) {
        runCatching { file(ctx, key).writeText(text) }
    }
}

/** Plain GET with sane timeouts. Throws on HTTP errors. */
suspend fun httpGet(url: String): String = httpRequest(url)

/** An HTTP call with optional headers and a JSON body. Throws on HTTP errors. */
suspend fun httpRequest(
    url: String,
    method: String = "GET",
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
): String = withContext(Dispatchers.IO) {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 10000
    c.requestMethod = method
    c.setRequestProperty("User-Agent", "CanvasTV/0.6")
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    try {
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        c.inputStream.bufferedReader().readText()
    } finally {
        c.disconnect()
    }
}

/**
 * Keeps [key]'s data fresh every [refreshMs] while the widget is on screen.
 * Starts from the disk cache; on failure keeps showing the old data and retries sooner.
 */
@Composable
fun rememberRemoteData(key: String, refreshMs: Long, fetch: suspend () -> String): State<RemoteData?> {
    val ctx = LocalContext.current.applicationContext
    return produceState(WidgetCache.read(ctx, key), key, refreshMs) {
        while (true) {
            val age = System.currentTimeMillis() - (value?.updatedAt ?: 0L)
            if (value?.text == null || age >= refreshMs || value?.stale == true) {
                value = try {
                    val text = fetch()
                    WidgetCache.write(ctx, key, text)
                    RemoteData(text, System.currentTimeMillis())
                } catch (e: Exception) {
                    (value ?: RemoteData(null, 0L)).copy(error = e.message ?: "offline")
                }
            }
            val wait = if (value?.stale == true) minOf(60_000L, refreshMs) else refreshMs - (System.currentTimeMillis() - value!!.updatedAt)
            delay(wait.coerceAtLeast(5_000L))
        }
    }
}
