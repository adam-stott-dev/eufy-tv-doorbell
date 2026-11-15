package io.github.adamstottdev.eufytv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lets a computer show the test pop-up over adb without touching the TV:
 *
 *   adb shell am broadcast -a io.github.adamstottdev.eufytv.TEST_POPUP \
 *       -n io.github.adamstottdev.eufytv/.service.TestTriggerReceiver --el delay_ms 0
 *
 * Protected by android.permission.DUMP, which adb's shell has and normal apps cannot get.
 */
class TestTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val delayMs = intent.getLongExtra(DoorbellService.EXTRA_DELAY_MS, 0L).coerceIn(0L, 120_000L)
        DoorbellService.start(context, DoorbellService.ACTION_TEST, delayMs)
    }
}
