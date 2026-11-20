package com.canvastv.dashboard.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class AppInfo(val pkg: String, val label: String, val isTvApp: Boolean)

/** Installed launchable apps, with banners and icons cached as bitmaps. */
object Apps {
    private val art = LruCache<String, ImageBitmap>(64)

    fun list(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        fun query(category: String) = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(category), 0,
        )
        val tv = query(Intent.CATEGORY_LEANBACK_LAUNCHER).map { it.activityInfo.packageName }.toSet()
        val all = (query(Intent.CATEGORY_LEANBACK_LAUNCHER) + query(Intent.CATEGORY_LAUNCHER))
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != ctx.packageName }
        return all.map {
            AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.activityInfo.packageName in tv)
        }.sortedWith(compareByDescending<AppInfo> { it.isTvApp }.thenBy { it.label.lowercase() })
    }

    fun label(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** The app's TV banner (16:9) if it has one, else its icon. */
    fun art(ctx: Context, pkg: String): Pair<ImageBitmap, Boolean>? {
        art.get("b:$pkg")?.let { return it to true }
        art.get("i:$pkg")?.let { return it to false }
        val pm = ctx.packageManager
        return runCatching {
            val banner: Drawable? = pm.getLeanbackLaunchIntentForPackage(pkg)?.let { pm.getActivityBanner(it) }
                ?: runCatching { pm.getApplicationBanner(pkg) }.getOrNull()
            if (banner != null) {
                val bmp = banner.toBitmap(320, 180).asImageBitmap()
                art.put("b:$pkg", bmp); bmp to true
            } else {
                val bmp = pm.getApplicationIcon(pkg).toBitmap(192, 192).asImageBitmap()
                art.put("i:$pkg", bmp); bmp to false
            }
        }.getOrNull()
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    fun isInstalled(ctx: Context, pkg: String) = runCatching {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    }.getOrDefault(false)

    @Suppress("unused")
    private fun PackageManager.safeLabel(pkg: String) = runCatching { getApplicationLabel(getApplicationInfo(pkg, 0)) }.getOrNull()
}
