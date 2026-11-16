package io.github.adamstottdev.eufytv.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Lets the app put the TV back to sleep after a pop-up that woke it. Android
 * TV boxes such as the SHIELD don't support device admins, so this uses the
 * accessibility "lock screen" action, which puts the device to sleep. It reads
 * nothing on screen. The user turns it on once in Accessibility settings (or
 * with adb).
 */
class SleepService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Ready to put the TV to sleep")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "SleepService"
        @Volatile private var instance: SleepService? = null

        /** True if the user has turned the service on in Accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val list = AndroidSettings.Secure.getString(
                context.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = "${context.packageName}/"
            return list.split(':').any { it.startsWith(me) && it.endsWith("SleepService") }
        }

        /** Puts the TV to sleep. Returns false if it isn't allowed. */
        fun sleepNow(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            val s = instance ?: return false
            return s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }
}
