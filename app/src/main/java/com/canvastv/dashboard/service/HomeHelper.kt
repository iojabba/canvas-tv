package com.canvastv.dashboard.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.canvastv.dashboard.ui.MainActivity

/**
 * Some Google TV builds (the Hisense U75 among them) keep their own launcher as the home screen
 * no matter which app is chosen. This optional helper, switched on by the person in the TV's
 * Accessibility settings, notices when that launcher's screen comes to the front (after startup,
 * on Home, or when backing out of an app) and brings Canvas TV up instead.
 *
 * It subscribes only to window changes of the launcher packages (see res/xml/home_helper.xml)
 * and never reads what's on screen.
 */
class HomeHelper : AccessibilityService() {
    private var lastLaunch = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() !in LAUNCHERS) return
        val now = SystemClock.elapsedRealtime()
        if (now < passUntil) return // the person asked for the Google TV home on purpose
        if (now - lastLaunch < 1500) return // the launcher fires several events per appearance
        lastLaunch = now
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_HOME, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }.onFailure { Log.w("CanvasTV", "Home helper couldn't open the dashboard", it) }
    }

    override fun onInterrupt() = Unit

    companion object {
        val LAUNCHERS = setOf("com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.google.android.leanbacklauncher")
        private var passUntil = 0L

        /** True when the person has switched the helper on in Settings → Accessibility. */
        fun enabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, HomeHelper::class.java)
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** Opens the Google TV home once without the helper bouncing back to Canvas TV. */
        fun openGoogleTvHome(ctx: Context): Boolean {
            passUntil = SystemClock.elapsedRealtime() + 10_000
            val i = LAUNCHERS.firstNotNullOfOrNull { pkg ->
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg)
                    .takeIf { it.resolveActivity(ctx.packageManager) != null }
            } ?: return false
            return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        }

        fun openAccessibilitySettings(ctx: Context) {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
