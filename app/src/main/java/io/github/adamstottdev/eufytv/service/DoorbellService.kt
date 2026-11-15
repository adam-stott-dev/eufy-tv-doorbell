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
import io.github.adamstottdev.eufytv.eufy.DoorbellEvent
import io.github.adamstottdev.eufytv.eufy.EufyBridge
import io.github.adamstottdev.eufytv.eufy.EventType
import io.github.adamstottdev.eufytv.eufy.NoEufyBridge
import io.github.adamstottdev.eufytv.settings.Settings
import io.github.adamstottdev.eufytv.settings.SettingsActivity

/**
 * Always-on background service. Keeps the Eufy connection up, listens for
 * ring and motion events and hands them to [EventRouter].
 */
class DoorbellService : Service(), EufyBridge.Listener {
    private lateinit var router: EventRouter
    private var bridge: EufyBridge = NoEufyBridge()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        router = EventRouter(this)
        startInForeground(getString(R.string.status_starting))
        if (Settings(this).keepAwake) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eufytv:connection")
                .apply { setReferenceCounted(false); acquire() }
        }
        bridge.start(this)
        status = "running"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST -> router.onEvent(
                DoorbellEvent("test", getString(R.string.test_device_name), EventType.TEST)
            )
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        bridge.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
        status = "stopped"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onEvent(event: DoorbellEvent) = router.onEvent(event)

    override fun onStatus(status: String) {
        Log.i(TAG, status)
        startInForeground(status)
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

        @Volatile var status: String = "stopped"
            private set

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, DoorbellService::class.java).setAction(action)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DoorbellService::class.java))
        }
    }
}
