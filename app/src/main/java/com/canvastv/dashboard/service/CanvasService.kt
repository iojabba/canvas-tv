package com.canvastv.dashboard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.canvastv.dashboard.data.CompanionServer
import com.canvastv.dashboard.data.DashboardViewModel
import com.canvastv.dashboard.data.Messages
import com.canvastv.dashboard.model.Corner
import com.canvastv.dashboard.model.Dashboard
import com.canvastv.dashboard.model.OverlayMode
import com.canvastv.dashboard.ui.MainActivity
import com.canvastv.dashboard.ui.isDefaultHome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Whether a dashboard screen is showing. The overlay hides itself while it is. */
object DashboardVisibility {
    var count by mutableIntStateOf(0)
        private set
    val visible: Boolean get() = count > 0
    fun shown() { count++ }
    fun hidden() { count = (count - 1).coerceAtLeast(0) }
}

/**
 * Runs in the background only while it has a job: showing the overlay over other apps, or
 * reopening the dashboard when the TV wakes from standby (TVs rarely do a full boot, so the
 * boot broadcast alone would almost never fire). Both need "display over other apps".
 */
class CanvasService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: View? = null
    private var owner: OverlayOwner? = null
    private var shownSpec: Pair<OverlayMode, Corner>? = null

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d = layout() ?: return
            if (!d.openOnStartup) return
            // Give the TV's own home screen a moment to come up, then open on top of it.
            main.postDelayed({ if (!DashboardVisibility.visible) openDashboard(this@CanvasService, "wake") }, WAKE_DELAY_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        ContextCompat.registerReceiver(this, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
        Messages.load(this)
        // Keep the phone page reachable while the dashboard isn't open (it posts the messages the overlay shows).
        if (layout()?.phoneSetup == true) CompanionServer.start(this)
        scope.launch {
            snapshotFlow { Triple(layout(), DashboardVisibility.visible, Messages.banner) }
                .distinctUntilChanged()
                .collect { (d, dashboardShowing, _) -> refresh(d, dashboardShowing) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (!needed(layout())) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOn) }
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    private fun layout(): Dashboard? = DashboardViewModel.layoutState(this)?.value

    private fun refresh(d: Dashboard?, dashboardShowing: Boolean) {
        if (!needed(d)) { stopSelf(); return }
        val spec = d!!.overlay
        val want = spec.mode != OverlayMode.OFF && !dashboardShowing && Settings.canDrawOverlays(this) &&
            // "Messages only" is just a pop-up while a message is fresh.
            (spec.mode != OverlayMode.MESSAGES || Messages.banner != null)
        if (!want) { removeOverlay(); return }
        if (overlay != null && shownSpec == (spec.mode to spec.corner)) return
        removeOverlay()
        addOverlay(d)
    }

    private fun addOverlay(d: Dashboard) {
        val wm = getSystemService(WindowManager::class.java)
        val o = OverlayOwner().also { it.start() }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewTreeViewModelStoreOwner(o)
            setContent { OverlayCard() }
        }
        val margin = (36 * resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Never takes the remote's focus or touches: the app underneath keeps working as normal.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = when (d.overlay.corner) {
                Corner.TOP_RIGHT -> Gravity.TOP or Gravity.END
                Corner.TOP_LEFT -> Gravity.TOP or Gravity.START
                Corner.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
                Corner.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            }
            x = margin; y = margin
        }
        try {
            wm.addView(view, lp)
            overlay = view; owner = o; shownSpec = d.overlay.mode to d.overlay.corner
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't show the overlay", e)
            o.stop()
        }
    }

    private fun removeOverlay() {
        overlay?.let { v -> runCatching { getSystemService(WindowManager::class.java).removeView(v) } }
        owner?.stop()
        overlay = null; owner = null; shownSpec = null
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Canvas TV in the background", NotificationManager.IMPORTANCE_MIN))
        }
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Canvas TV")
            .setContentText("Showing the overlay or waiting for the TV to wake")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type) }
            .onFailure { Log.w(TAG, "Couldn't run in the foreground", it); stopSelf() }
    }

    companion object {
        private const val TAG = "CanvasTV"
        private const val CHANNEL = "background"
        private const val NOTIFICATION_ID = 7
        const val WAKE_DELAY_MS = 2500L

        fun needed(d: Dashboard?): Boolean = d != null && (d.openOnStartup || d.overlay.mode != OverlayMode.OFF)

        /** Starts or stops the service to match the saved settings. Safe to call any time. */
        fun sync(ctx: Context) {
            val d = DashboardViewModel.layoutState(ctx)?.value
            val i = Intent(ctx, CanvasService::class.java)
            if (needed(d)) runCatching { ContextCompat.startForegroundService(ctx, i) }.onFailure { Log.w(TAG, "Service start refused", it) }
            else ctx.stopService(i)
        }

        /**
         * Brings the dashboard to the front. Android only allows this from the background when
         * the app may display over other apps; without it the call is quietly ignored.
         */
        fun openDashboard(ctx: Context, why: String) {
            if (isDefaultHome(ctx)) return // the TV opens the home screen by itself
            if (!Settings.canDrawOverlays(ctx)) { Log.i(TAG, "Not opening on $why: needs display over other apps"); return }
            runCatching {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }.onFailure { Log.w(TAG, "Couldn't open the dashboard on $why", it) }
        }
    }
}

/** Starts the service (and opens the dashboard) after a full power-on, and after an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val d = DashboardViewModel.layoutState(ctx)?.value ?: return
        CanvasService.sync(ctx)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && d.openOnStartup) {
            val pending = goAsync()
            // The TV's launcher starts at the same time; opening a few seconds later lands on top of it.
            Handler(Looper.getMainLooper()).postDelayed({
                CanvasService.openDashboard(ctx, "boot")
                pending.finish()
            }, 5000L)
        }
    }
}

/** The lifecycle a ComposeView needs when it lives in a window that isn't an activity. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    fun start() {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
