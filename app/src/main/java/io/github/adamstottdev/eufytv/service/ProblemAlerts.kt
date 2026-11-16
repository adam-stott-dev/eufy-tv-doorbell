package io.github.adamstottdev.eufytv.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import io.github.adamstottdev.eufytv.R
import io.github.adamstottdev.eufytv.eufy.BridgeState
import io.github.adamstottdev.eufytv.eufy.EufyConnection
import io.github.adamstottdev.eufytv.settings.Settings
import io.github.adamstottdev.eufytv.settings.SignInActivity

/**
 * Tells the user, on screen and over whatever is playing, when something stops
 * the pop-ups from working (signed out by Eufy, a code needed, no connection,
 * no events, no overlay permission), and keeps reminding them until it is fixed.
 *
 * Problems that need the user show at once. Connection drops are retried
 * quietly for a few minutes first. Once fixed, a short "connected again" note.
 */
class ProblemAlerts(private val context: Context) {
    data class Problem(val message: String, val needsSignIn: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private var activeMessage: String? = null
    private var lastShownAt = 0L
    private var alerted = false
    private var banner: View? = null
    private val removeBanner = Runnable { hideBanner() }

    /** Called every minute and whenever the connection state changes. */
    fun check() {
        val problem = currentProblem(context)
        val now = System.currentTimeMillis()
        if (problem == null) {
            if (alerted) show(context.getString(R.string.problem_fixed), error = false)
            alerted = false
            activeMessage = null
            return
        }
        if (problem.message != activeMessage) {
            activeMessage = problem.message
            lastShownAt = 0L
        }
        val s = Settings(context)
        val repeatMs = s.problemReminderMinutes * 60_000L
        val due = lastShownAt == 0L || (repeatMs > 0 && now - lastShownAt >= repeatMs)
        if (!due) return
        if (s.isQuietNow()) return
        if (!(context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) return
        if (problem.needsSignIn && SignInActivity.isVisible) return // they're already fixing it
        Log.w(TAG, "Problem: ${problem.message}")
        show(problem.message, error = true)
        lastShownAt = now
        alerted = true
    }

    private fun show(text: String, error: Boolean) {
        val app = context.applicationContext
        if (!AndroidSettings.canDrawOverlays(app)) {
            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
            return
        }
        hideBanner()
        val dp = app.resources.displayMetrics.density
        val view = TextView(app).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding((20 * dp).toInt(), (14 * dp).toInt(), (20 * dp).toInt(), (14 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(if (error) 0xEEB3261E.toInt() else 0xEE1E6B32.toInt())
                cornerRadius = 12 * dp
            }
            maxWidth = (app.resources.displayMetrics.widthPixels * 0.6f).toInt()
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (24 * dp).toInt()
            title = "Doorbell problem"
        }
        try {
            (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(view, lp)
            banner = view
            main.postDelayed(removeBanner, if (error) 15_000L else 6_000L)
        } catch (e: Exception) {
            Log.w(TAG, "Banner failed, using a toast", e)
            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        }
    }

    fun release() = hideBanner()

    private fun hideBanner() {
        main.removeCallbacks(removeBanner)
        banner?.let { v ->
            runCatching { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
        }
        banner = null
    }

    companion object {
        private const val TAG = "ProblemAlerts"
        /** How long a dropped connection is retried quietly before telling the user. */
        private const val GRACE_MS = 5 * 60_000L

        /** What is wrong right now, or null. Also used by the settings screen. */
        fun currentProblem(context: Context, now: Long = System.currentTimeMillis()): Problem? {
            val state = EufyConnection.state
            val since = now - EufyConnection.stateSince
            val ctx = context.applicationContext
            return when (state) {
                is BridgeState.SignedOut -> null
                is BridgeState.SignInFailed ->
                    Problem(ctx.getString(R.string.problem_sign_in_failed, state.message), needsSignIn = true)
                is BridgeState.NeedCode, is BridgeState.NeedCaptcha ->
                    Problem(ctx.getString(R.string.problem_needs_sign_in), needsSignIn = true)
                is BridgeState.Disconnected ->
                    if (since >= GRACE_MS) Problem(ctx.getString(R.string.problem_offline, state.message), false) else null
                is BridgeState.Connecting ->
                    if (since >= GRACE_MS) Problem(ctx.getString(R.string.problem_offline, "still connecting"), false) else null
                is BridgeState.Connected ->
                    if (!state.receivingEvents && since >= GRACE_MS) Problem(ctx.getString(R.string.problem_no_events), false)
                    else if (!AndroidSettings.canDrawOverlays(ctx)) Problem(ctx.getString(R.string.problem_no_overlay), false)
                    else null
            }
        }
    }
}
