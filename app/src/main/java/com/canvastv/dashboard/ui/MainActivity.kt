package com.canvastv.dashboard.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.canvastv.dashboard.data.DashboardViewModel

class MainActivity : ComponentActivity() {
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
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            vm.fullScreenTile = null
            if (vm.editing) vm.stopEditing()
            vm.goToPage(0)
        }
    }

    /** PIN recovery from a computer: adb shell am start -n …/.ui.MainActivity --ez clearPin true */
    private fun handleAdminExtras(intent: Intent?) {
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

/** True when Canvas TV is set as the TV's home screen. */
fun isDefaultHome(ctx: android.content.Context): Boolean = runCatching {
    val r = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
    r?.activityInfo?.packageName == ctx.packageName
}.getOrDefault(false)
