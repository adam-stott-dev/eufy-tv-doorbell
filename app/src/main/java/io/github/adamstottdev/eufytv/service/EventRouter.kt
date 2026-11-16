package io.github.adamstottdev.eufytv.service

import android.content.Context
import android.media.AudioManager
import android.util.Log
import io.github.adamstottdev.eufytv.eufy.DoorbellEvent
import io.github.adamstottdev.eufytv.eufy.EventType
import io.github.adamstottdev.eufytv.popup.PopupController
import io.github.adamstottdev.eufytv.settings.Settings

/** Applies the user's settings to each event and decides whether to show the pop-up. */
class EventRouter(private val context: Context) {
    private val lastShown = mutableMapOf<String, Long>()

    fun onEvent(event: DoorbellEvent) {
        val settings = Settings(context)
        val reason = rejectReason(event, settings)
        if (reason != null) {
            Log.i(TAG, "Ignoring ${event.type} from ${event.deviceName}: $reason")
            return
        }
        // Motion while the pop-up is already up for the same camera just keeps it open.
        if (PopupController.isShowing(event.deviceSerial)) {
            if (event.type == EventType.RING || settings.extendOnMotion) PopupController.extend()
            return
        }
        lastShown[event.deviceSerial] = event.timeMillis
        PopupController.show(context, event)
    }

    private fun rejectReason(event: DoorbellEvent, s: Settings): String? {
        if (event.type == EventType.TEST || event.type == EventType.LIVE) return null
        if (!s.isWatched(event.deviceSerial)) return "camera not chosen for pop-ups"
        when (event.type) {
            EventType.RING -> if (!s.triggerRing) return "rings turned off"
            EventType.MOTION -> if (!s.triggerMotion) return "motion turned off"
            EventType.PERSON -> if (!s.triggerPerson) return "person detection turned off"
            EventType.TEST, EventType.LIVE -> Unit
        }
        if (s.isQuietNow() && !(event.type == EventType.RING && s.quietAllowsRings)) return "quiet hours"
        if (s.showWhen == Settings.ShowWhen.IDLE_ONLY && isPlayingMedia()) return "something is playing"
        val last = lastShown[event.deviceSerial]
        if (event.type != EventType.RING && last != null &&
            event.timeMillis - last < s.cooldownSeconds * 1000L &&
            !PopupController.isShowing(event.deviceSerial)
        ) return "cooldown"
        return null
    }

    private fun isPlayingMedia(): Boolean =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive

    private companion object { const val TAG = "EventRouter" }
}
