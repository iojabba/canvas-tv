package com.canvastv.dashboard.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Exists only so Android lets us read the TV's active media sessions.
 * Access is granted once from a computer:
 *   adb shell cmd notification allow_listener com.canvastv.dashboard/com.canvastv.dashboard.data.MediaListener
 */
class MediaListener : NotificationListenerService()

data class NowPlayingInfo(
    val pkg: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    val positionMs: Long,
    val positionAt: Long,
    val speed: Float,
    val durationMs: Long,
    val canSkip: Boolean,
) {
    /** Playback position now, extrapolated from the last update. */
    fun positionNow(): Long {
        if (!playing) return positionMs
        val p = positionMs + ((SystemClock.elapsedRealtime() - positionAt) * speed).toLong()
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}

sealed interface NowPlayingState {
    data object NeedsAccess : NowPlayingState
    data object Nothing : NowPlayingState
    data class Playing(val info: NowPlayingInfo) : NowPlayingState
}

/** Follows whichever app is playing media on the TV. Tiles call [acquire]/[release]. */
object NowPlaying {
    var state by mutableStateOf<NowPlayingState>(NowPlayingState.Nothing)
        private set

    private var users = 0
    private var manager: MediaSessionManager? = null
    private var current: MediaController? = null
    private val main = Handler(Looper.getMainLooper())

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list.orEmpty()) }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // A different app may have started playing; re-pick so the tile follows it.
            manager?.let { m -> runCatching { pick(m.getActiveSessions(component(appContext!!))) } } ?: publish()
        }
        override fun onSessionDestroyed() { current = null; manager?.let { m -> runCatching { pick(m.getActiveSessions(component(appContext!!))) } } }
    }

    private var appContext: Context? = null

    private fun component(ctx: Context) = ComponentName(ctx, MediaListener::class.java)

    fun acquire(ctx: Context) {
        users++
        if (users > 1) return
        appContext = ctx.applicationContext
        val m = ctx.getSystemService(MediaSessionManager::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, component(ctx), main)
            manager = m
            pick(m.getActiveSessions(component(ctx)))
        } catch (e: SecurityException) {
            Log.i("CanvasTV", "Now playing needs notification access")
            state = NowPlayingState.NeedsAccess
        } catch (e: RuntimeException) {
            Log.w("CanvasTV", "Media sessions unavailable", e)
            state = NowPlayingState.Nothing
        }
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        current?.unregisterCallback(controllerCallback)
        current = null
        manager = null
    }

    /** Prefers a session that is playing; otherwise the most recent one. */
    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != current?.sessionToken) {
            current?.unregisterCallback(controllerCallback)
            current = best
            best?.registerCallback(controllerCallback, main)
        }
        publish()
    }

    private fun publish() {
        val c = current
        val md = c?.metadata
        val ps = c?.playbackState
        if (c == null || (md == null && ps == null)) { state = NowPlayingState.Nothing; return }
        val title = md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val actions = ps?.actions ?: 0L
        state = NowPlayingState.Playing(
            NowPlayingInfo(
                pkg = c.packageName,
                title = title,
                artist = artist,
                art = art,
                playing = ps?.state == PlaybackState.STATE_PLAYING,
                positionMs = ps?.position ?: 0L,
                positionAt = ps?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
                speed = ps?.playbackSpeed ?: 1f,
                durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                canSkip = actions and (PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
            ),
        )
    }

    fun playPause() {
        val c = current ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() { current?.transportControls?.skipToNext() }
    fun previous() { current?.transportControls?.skipToPrevious() }
    fun seekBy(ms: Long) {
        val info = (state as? NowPlayingState.Playing)?.info ?: return
        current?.transportControls?.seekTo((info.positionNow() + ms).coerceAtLeast(0))
    }
}
