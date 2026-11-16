package io.github.adamstottdev.eufytv.service

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Lets the app put the TV back to sleep after a pop-up that woke it. Android
 * only allows that for a device admin with the "force lock" policy, which the
 * user turns on once (from the settings screen, or with adb).
 */
class SleepAdmin : DeviceAdminReceiver() {
    companion object {
        private const val TAG = "SleepAdmin"

        fun component(context: Context) = ComponentName(context, SleepAdmin::class.java)

        fun isActive(context: Context): Boolean =
            context.getSystemService(DevicePolicyManager::class.java).isAdminActive(component(context))

        /** The system screen that asks the user to allow it. */
        fun requestIntent(context: Context): Intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Lets the doorbell pop-up put the TV back to sleep after it woke it.")

        /** Puts the TV to sleep. Returns false if it isn't allowed. */
        fun sleepNow(context: Context): Boolean {
            if (!isActive(context)) return false
            return try {
                context.getSystemService(DevicePolicyManager::class.java).lockNow()
                true
            } catch (e: SecurityException) {
                Log.w(TAG, "lockNow refused", e)
                false
            }
        }
    }
}
