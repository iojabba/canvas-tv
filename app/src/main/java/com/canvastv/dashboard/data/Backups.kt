package com.canvastv.dashboard.data

import android.content.Context
import android.util.Log
import com.canvastv.dashboard.model.Dashboard
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One saved layout file, newest first in listings. */
data class SavedLayout(val file: File, val label: String, val time: Long)

private fun stamp(t: Long = System.currentTimeMillis()) = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(t))

fun describeTime(t: Long): String = SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault()).format(Date(t))

/**
 * Automatic version history inside the app's private storage: a copy of the layout is
 * kept before edits, restores and resets. The newest [KEEP] are kept.
 */
object History {
    private const val KEEP = 10
    private fun dir(ctx: Context) = File(ctx.filesDir, "history").apply { mkdirs() }

    fun save(ctx: Context, json: Json, d: Dashboard, reason: String) {
        runCatching {
            val safe = reason.replace(Regex("[^A-Za-z0-9 ]"), "").take(40)
            File(dir(ctx), "${stamp()}_$safe.json").writeText(json.encodeToString(Dashboard.serializer(), d))
            dir(ctx).listFiles().orEmpty().sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
        }.onFailure { Log.w("CanvasTV", "History save failed", it) }
    }

    fun list(ctx: Context): List<SavedLayout> = dir(ctx).listFiles().orEmpty()
        .filter { it.extension == "json" }
        .sortedByDescending { it.name }
        .map { SavedLayout(it, it.nameWithoutExtension.substringAfter('_', "Saved layout"), it.lastModified()) }
}

/**
 * Backups you can copy off the TV. They live in the app's folder on shared storage:
 *   /sdcard/Android/data/com.canvastv.dashboard/files/backups/
 * From a computer:  adb pull  or  adb push  to that folder.
 */
object Backups {
    const val ADB_PATH = "/sdcard/Android/data/com.canvastv.dashboard/files/backups/"
    private fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "backups").apply { mkdirs() }

    fun export(ctx: Context, json: Json, d: Dashboard): File {
        // The PIN stays on this TV; a backup restored elsewhere shouldn't carry it.
        val f = File(dir(ctx), "canvas-tv-${stamp()}.json")
        f.writeText(json.encodeToString(Dashboard.serializer(), d.copy(pinHash = null)))
        return f
    }

    fun list(ctx: Context): List<SavedLayout> = dir(ctx).listFiles().orEmpty()
        .filter { it.extension == "json" }
        .sortedByDescending { it.lastModified() }
        .map { SavedLayout(it, it.nameWithoutExtension, it.lastModified()) }
}
