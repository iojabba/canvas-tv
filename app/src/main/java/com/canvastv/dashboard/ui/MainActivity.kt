package com.canvastv.dashboard.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.canvastv.dashboard.data.DashboardViewModel

class MainActivity : ComponentActivity() {
    companion object {
        /** Set by the Home button helper: behave as if Home was pressed. */
        const val EXTRA_HOME = "home"
    }

    private val vm: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opened from the apps row while we're already the home screen: show the home copy
        // instead of starting a second dashboard on top of it.
        if (savedInstanceState == null && !intent.hasCategory(Intent.CATEGORY_HOME) && isDefaultHome(this)) {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        handleAdminExtras(intent)
        com.canvastv.dashboard.service.CanvasService.sync(this)
        com.canvastv.dashboard.service.HomeHelper.restoreIfWanted(this)
        setContent {
            DashboardTheme(vm.effectiveTheme()) {
                val full = vm.fullScreenTile
                if (full != null) FullScreenTile(full) else DashboardScreen(vm)
            }
        }
    }

    /** Pressing Home while we're the home screen returns to the first page. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAdminExtras(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.getBooleanExtra(EXTRA_HOME, false)) {
            vm.fullScreenTile = null
            if (vm.editing) vm.stopEditing()
            vm.goToPage(0)
        }
    }

    override fun onStart() { super.onStart(); com.canvastv.dashboard.service.DashboardVisibility.shown() }
    override fun onStop() { com.canvastv.dashboard.service.DashboardVisibility.hidden(); super.onStop() }

    /**
     * Set-up from a computer, for things that are painful to type with a remote:
     *  PIN recovery: --ez clearPin true
     *  Home Assistant: --es haUrl http://192.168.1.20:8123 --es haToken <long-lived token>
     */
    private fun handleAdminExtras(intent: Intent?) {
        // Show shortcuts: --esa showLinks <link>,<link>… [--esa showTitles <name>,…] [--esa showApps <package or ->,…] [--esa showTvmaze <TVmaze number or ->,…]
        intent?.getStringArrayExtra("showLinks")?.let { links ->
            val titles = intent.getStringArrayExtra("showTitles").orEmpty()
            val apps = intent.getStringArrayExtra("showApps").orEmpty()
            val n = vm.addShows(links.mapIndexed { i, l -> Triple(titles.getOrElse(i) { "" }.trim(), l.trim(), apps.getOrElse(i) { "" }.trim().takeIf { it != "-" }.orEmpty()) }, intent.getStringArrayExtra("showTvmaze").orEmpty().toList())
            intent.removeExtra("showLinks"); intent.removeExtra("showTitles"); intent.removeExtra("showApps"); intent.removeExtra("showTvmaze")
            vm.message = "Added $n shows to the Shows page"
        }
        // App shortcuts: --esa appPkgs <package>,<package>… [--es appPage <page name>]
        intent?.getStringArrayExtra("appPkgs")?.let { pkgs ->
            val n = vm.addApps(pkgs.toList(), intent.getStringExtra("appPage"))
            intent.removeExtra("appPkgs"); intent.removeExtra("appPage")
            vm.message = "Added $n app shortcuts"
        }
        intent?.getStringExtra("overlayCity")?.let { city ->
            vm.setOverlay(vm.dashboard.overlay.copy(weatherPlace = city.trim()))
            intent.removeExtra("overlayCity")
        }
        val haUrl = intent?.getStringExtra("haUrl")
        val haToken = intent?.getStringExtra("haToken")
        if (haUrl != null || haToken != null) {
            val cur = vm.dashboard.homeAssistant
            vm.setHomeAssistant(cur.copy(url = haUrl?.trim() ?: cur.url, token = haToken?.trim() ?: cur.token))
            intent.removeExtra("haUrl"); intent.removeExtra("haToken")
            vm.message = "Home Assistant settings saved"
        }
        if (intent?.getBooleanExtra("clearPin", false) == true && vm.dashboard.pinHash != null) {
            vm.setPin(null)
            intent.removeExtra("clearPin")
        }
    }

    // TvView can consume BACK and forward it to the HDMI device, so handle it before anything else.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (vm.fullScreenTile != null && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) vm.fullScreenTile = null
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/** Tests replace this; the real TV always uses the package manager check. */
@androidx.annotation.VisibleForTesting
var homeCheckOverride: ((android.content.Context) -> Boolean)? = null

/**
 * True when Canvas TV is set as the TV's home screen. With no default chosen, Android
 * resolves to its chooser ("android"), which counts as not ours.
 */
fun isDefaultHome(ctx: android.content.Context): Boolean = homeCheckOverride?.invoke(ctx) ?: runCatching {
    val r = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
    val pkg = r?.activityInfo?.packageName
    // Only trust the answer if another home app is visible at all; otherwise we can't tell.
    val others = ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        .any { it.activityInfo.packageName != ctx.packageName && it.activityInfo.packageName != "android" }
    pkg == ctx.packageName && others
}.getOrDefault(false)
