package io.github.adamstottdev.eufytv.popup

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import io.github.adamstottdev.eufytv.R
import io.github.adamstottdev.eufytv.eufy.DoorbellEvent
import io.github.adamstottdev.eufytv.eufy.EufyConnection
import io.github.adamstottdev.eufytv.eufy.EventType
import io.github.adamstottdev.eufytv.eufy.LiveStreamSink
import io.github.adamstottdev.eufytv.service.SleepAdmin
import io.github.adamstottdev.eufytv.settings.Settings
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Shows the doorbell pop-up over whatever is on screen, keeps it up for the
 * configured time, and hides it again.
 *
 * Uses an overlay window when the app may draw over other apps, otherwise a
 * full-screen activity.
 */
object PopupController {
    private const val TAG = "PopupController"
    /** Shape of the E340's picture (1600x2200), used until the real size is known. */
    private const val DEFAULT_ASPECT = 1600f / 2200f

    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var activity: PopupActivity? = null
    private var current: DoorbellEvent? = null
    private var player: AnnexBPlayer? = null
    private var feeder: Thread? = null
    private var live: LiveSink? = null
    private var screenLock: PowerManager.WakeLock? = null
    private var durationMs = 30_000L
    /** True when the TV was asleep and this pop-up woke it, so it goes back to sleep after. */
    private var wokeTv = false
    private var appContext: Context? = null
    private val hideRunnable = Runnable { hide() }

    fun isShowing(serial: String) = current?.deviceSerial == serial

    /** Shows the pop-up for [event]; [durationOverrideMs] replaces the user's duration (used by tests). */
    fun show(context: Context, event: DoorbellEvent, durationOverrideMs: Long? = null) = main.post {
        val app = context.applicationContext
        val settings = Settings(app)
        appContext = app
        hide(replacing = true)
        current = event
        durationMs = durationOverrideMs ?: (settings.durationSeconds * 1000L)

        Log.i(TAG, "Showing ${event.type} from ${event.deviceName} for ${durationMs / 1000}s " +
            "(overlay=${AndroidSettings.canDrawOverlays(app)}, mode=${settings.popupMode}, wake=${settings.wakeScreen})")
        val asleep = !(app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (settings.wakeScreen) {
            // A pop-up that replaces one which woke the TV keeps that flag.
            wokeTv = wokeTv || asleep
            wakeScreen(app)
        }

        val label = if (settings.showLabel) labelFor(app, event) else null
        if (AndroidSettings.canDrawOverlays(app)) {
            showOverlay(app, settings, event, label)
        } else {
            Log.i(TAG, "No overlay permission, using full-screen activity")
            app.startActivity(
                Intent(app, PopupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
        main.postDelayed(hideRunnable, durationMs)
    }

    /** Keeps the pop-up up for another full duration. */
    fun extend() = main.post {
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, durationMs)
    }

    fun hide() = hide(replacing = false)

    private fun hide(replacing: Boolean) {
        main.removeCallbacks(hideRunnable)
        if (current != null) Log.i(TAG, "Hiding pop-up")
        feeder?.interrupt()
        feeder = null
        player?.release()
        player = null
        live?.let { l ->
            live = null
            EufyConnection.stopLivestream(l.serial)
            l.release()
        }
        overlay?.let { v ->
            try {
                (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            } catch (e: Exception) {
                Log.w(TAG, "removeView failed", e)
            }
        }
        overlay = null
        activity?.finish()
        activity = null
        screenLock?.takeIf { it.isHeld }?.release()
        screenLock = null
        val app = appContext
        if (!replacing && wokeTv && app != null) {
            wokeTv = false
            if (Settings(app).sleepAfter) {
                val ok = SleepAdmin.sleepNow(app)
                Log.i(TAG, if (ok) "Putting the TV back to sleep" else "Can't put the TV to sleep (not allowed)")
            }
        }
        current = null
    }

    /** Called by [PopupActivity] to put the pop-up content in its window. */
    internal fun attachActivity(a: PopupActivity): View? {
        val event = current ?: return null
        activity = a
        val settings = Settings(a)
        return buildView(a, event, if (settings.showLabel) labelFor(a, event) else null)
    }

    internal fun activityClosed(a: PopupActivity) {
        if (activity === a) {
            activity = null
            closedByUser()
        }
    }

    /** Someone closed it with the remote, so they're watching: leave the TV on. */
    private fun closedByUser() {
        wokeTv = false
        hide()
    }

    private fun showOverlay(app: Context, s: Settings, event: DoorbellEvent, label: String?) {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val screen = app.resources.displayMetrics
        val sw = screen.widthPixels
        val sh = screen.heightPixels

        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        flags = if (s.backCloses) {
            flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        )
        lp.title = "Doorbell pop-up"
        if (s.popupMode == Settings.PopupMode.PIP) {
            val h = (sh * s.pipHeightFraction).toInt()
            lp.height = h
            lp.width = (h * DEFAULT_ASPECT).toInt()
            val margin = (sw * 0.03f).toInt()
            lp.x = margin
            lp.y = margin
            lp.gravity = when (s.corner) {
                Settings.Corner.TOP_LEFT -> Gravity.TOP or Gravity.START
                Settings.Corner.TOP_RIGHT -> Gravity.TOP or Gravity.END
                Settings.Corner.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
                Settings.Corner.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
            }
        }

        val view = buildView(app, event, label)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (overlay === v) Log.w(TAG, "Overlay removed by the system")
            }
        })
        wm.addView(view, lp)
        Log.i(TAG, "Overlay added ${lp.width}x${lp.height}")
        overlay = view
        if (s.backCloses) view.requestFocus()
    }

    private fun buildView(ctx: Context, event: DoorbellEvent, label: String?): PopupView {
        lateinit var view: PopupView
        view = PopupView(ctx, label, onSurface = { surface -> startVideo(ctx, event, view, surface) }, onClose = { closedByUser() })
        return view
    }

    private fun startVideo(ctx: Context, event: DoorbellEvent, view: PopupView, surface: Surface) {
        if (event.type == EventType.TEST) {
            val file = File(ctx.getExternalFilesDir(null), "test.h264")
            if (!file.exists()) {
                view.showMessage(ctx.getString(R.string.popup_test_no_video))
                return
            }
            val p = AnnexBPlayer(surface, paceFps = 15, onVideoSize = view::setVideoSize)
            player = p
            feeder = Thread({
                try {
                    file.inputStream().use { input ->
                        val buf = ByteArray(16 * 1024)
                        while (!Thread.currentThread().isInterrupted) {
                            val n = input.read(buf)
                            if (n < 0) break
                            p.feed(buf, 0, n)
                        }
                    }
                } catch (_: InterruptedException) {
                } catch (e: Exception) {
                    Log.w(TAG, "Test video failed", e)
                }
            }, "TestVideoFeeder").apply { start() }
            return
        }
        val audio = when (Settings(ctx).audio) {
            Settings.Audio.OFF -> false
            Settings.Audio.ON -> true
            Settings.Audio.RING_ONLY -> event.type == EventType.RING || event.type == EventType.LIVE
        }
        val sink = LiveSink(event.deviceSerial, surface, view, audio)
        live = sink
        EufyConnection.startLivestream(event.deviceSerial, sink)
        // Say so if the camera never sends video, rather than "Connecting…" forever.
        main.postDelayed({
            if (live === sink && !sink.started) view.showMessage(ctx.getString(R.string.popup_no_video))
        }, 12_000L)
    }

    /** Receives the camera's livestream and plays it in the pop-up. */
    private class LiveSink(
        val serial: String,
        private val surface: Surface,
        private val view: PopupView,
        private val audioOn: Boolean,
    ) : LiveStreamSink {
        @Volatile private var video: AnnexBPlayer? = null
        @Volatile private var sound: AacPlayer? = null
        @Volatile private var released = false
        @Volatile var started = false
            private set

        override fun onStart(codec: String, width: Int, height: Int, fps: Int, hasAudio: Boolean) {
            if (released) return
            started = true
            video?.release()
            if (width > 0 && height > 0) view.setVideoSize(width, height)
            video = AnnexBPlayer(surface, hevc = codec == "h265", onVideoSize = view::setVideoSize)
            sound?.release()
            sound = if (audioOn && hasAudio) AacPlayer() else null
        }

        override fun onVideo(data: ByteArray, offset: Int, length: Int) {
            video?.feed(data, offset, length)
        }

        override fun onAudio(data: ByteArray, offset: Int, length: Int) {
            sound?.feed(data, offset, length)
        }

        override fun onStop(reason: String?) {
            if (released) return
            if (reason != null) view.showMessage(reason)
            release()
        }

        fun release() {
            released = true
            video?.release()
            video = null
            sound?.release()
            sound = null
        }
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun wakeScreen(app: Context) {
        // Waking the screen also ends the screensaver (Daydream).
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        screenLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "eufytv:popup",
        ).apply { acquire(durationMs + 5_000) }
    }

    private fun labelFor(ctx: Context, e: DoorbellEvent): String {
        val what = when (e.type) {
            EventType.RING -> ctx.getString(R.string.event_ring)
            EventType.MOTION -> ctx.getString(R.string.event_motion)
            EventType.PERSON -> ctx.getString(R.string.event_person)
            EventType.LIVE -> ctx.getString(R.string.event_live)
            EventType.TEST -> ctx.getString(R.string.event_test)
        }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.timeMillis))
        return if (e.type == EventType.TEST) "$what · $time" else "${e.deviceName} · $what · $time"
    }
}
