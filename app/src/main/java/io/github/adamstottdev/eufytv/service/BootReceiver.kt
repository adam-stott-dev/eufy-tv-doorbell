package io.github.adamstottdev.eufytv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.adamstottdev.eufytv.settings.Settings

/** Starts the background service when the TV boots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                if (Settings(context).serviceEnabled) DoorbellService.start(context)
        }
    }
}
