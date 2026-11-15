package io.github.adamstottdev.eufytv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import io.github.adamstottdev.eufytv.R
import io.github.adamstottdev.eufytv.eufy.AccountStore
import io.github.adamstottdev.eufytv.eufy.BridgeState
import io.github.adamstottdev.eufytv.eufy.DoorbellEvent
import io.github.adamstottdev.eufytv.eufy.EufyConnection
import io.github.adamstottdev.eufytv.eufy.EventType
import io.github.adamstottdev.eufytv.settings.Settings
import io.github.adamstottdev.eufytv.settings.SettingsActivity

/**
 * Always-on background service. Keeps the Eufy connection up, listens for
 * ring and motion events and hands them to [EventRouter].
 */
class DoorbellService : Service() {
    private lateinit var router: EventRouter
    private lateinit var alerts: ProblemAlerts
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val observer = EufyConnection.Observer { onConnectionChanged() }
    private var lastNotificationText: String? = null
    private val tick = object : Runnable {
        override fun run() {
            alerts.check()
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        router = EventRouter(this)
        alerts = ProblemAlerts(this)
        startInForeground(getString(R.string.status_starting))
        if (Settings(this).keepAwake) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eufytv:connection")
                .apply { setReferenceCounted(false); acquire() }
        }
        EufyConnection.eventHandler = { event -> handler.post { router.onEvent(event) } }
        EufyConnection.addObserver(observer)
        if (!EufyConnection.isRunning) EufyConnection.start(this)
        handler.postDelayed(tick, 60_000L)
        status = "running"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST -> {
                val delayMs = intent.getLongExtra(EXTRA_DELAY_MS, 0L)
                Log.i(TAG, "Test pop-up in ${delayMs / 1000} s")
                handler.postDelayed({
                    router.onEvent(DoorbellEvent("test", getString(R.string.test_device_name), EventType.TEST))
                }, delayMs)
            }
            ACTION_LIVE -> {
                val serial = intent.getStringExtra(EXTRA_SERIAL)
                    ?: EufyConnection.cameras.firstOrNull { it.isDoorbell }?.serial
                    ?: EufyConnection.cameras.firstOrNull()?.serial
                val delayMs = intent.getLongExtra(EXTRA_DELAY_MS, 0L)
                if (serial != null) {
                    val name = EufyConnection.cameraName(serial) ?: serial
                    handler.postDelayed({ router.onEvent(DoorbellEvent(serial, name, EventType.LIVE)) }, delayMs)
                } else {
                    Log.w(TAG, "No camera for live view (not signed in?)")
                }
            }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        EufyConnection.removeObserver(observer)
        EufyConnection.eventHandler = null
        EufyConnection.stop()
        alerts.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        status = "stopped"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onConnectionChanged() {
        val email = AccountStore(this).email ?: ""
        val text = when (val s = EufyConnection.state) {
            BridgeState.SignedOut -> getString(R.string.account_signed_out)
            is BridgeState.Connected -> getString(R.string.account_connected, email)
            BridgeState.Connecting -> getString(R.string.account_connecting, email)
            is BridgeState.Disconnected -> getString(R.string.account_offline, email) + " (${s.message})"
            else -> getString(R.string.account_action, email)
        }
        if (text != lastNotificationText) {
            lastNotificationText = text
            startInForeground(text)
        }
        alerts.check()
    }

    private fun startInForeground(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "DoorbellService"
        private const val CHANNEL = "service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_TEST = "io.github.adamstottdev.eufytv.TEST_POPUP"
        const val ACTION_STOP = "io.github.adamstottdev.eufytv.STOP"
        const val ACTION_LIVE = "io.github.adamstottdev.eufytv.LIVE_VIEW"
        const val EXTRA_DELAY_MS = "delay_ms"
        const val EXTRA_SERIAL = "serial"

        @Volatile var status: String = "stopped"
            private set

        fun start(context: Context, action: String? = null, delayMs: Long = 0, serial: String? = null) {
            val intent = Intent(context, DoorbellService::class.java)
                .setAction(action)
                .putExtra(EXTRA_DELAY_MS, delayMs)
            if (serial != null) intent.putExtra(EXTRA_SERIAL, serial)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DoorbellService::class.java))
        }
    }
}
