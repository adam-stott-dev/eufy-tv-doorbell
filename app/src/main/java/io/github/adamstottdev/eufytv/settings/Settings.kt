package io.github.adamstottdev.eufytv.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import io.github.adamstottdev.eufytv.eufy.EufyConnection
import java.util.Calendar

/** Typed access to everything the user can change on the settings screen. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    enum class PopupMode { PIP, FULLSCREEN }
    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    enum class Audio { OFF, ON, RING_ONLY }
    enum class ShowWhen { ALWAYS, IDLE_ONLY }

    val serviceEnabled get() = prefs.getBoolean(KEY_SERVICE_ENABLED, true)

    val triggerRing get() = prefs.getBoolean("trigger_ring", true)
    val triggerMotion get() = prefs.getBoolean("trigger_motion", true)
    val triggerPerson get() = prefs.getBoolean("trigger_person", true)

    val popupMode get() = enumPref("popup_mode", PopupMode.PIP)
    val corner get() = enumPref("pip_corner", Corner.TOP_RIGHT)

    /** Pop-up height as a fraction of the screen height (picture-in-picture only). */
    val pipHeightFraction: Float
        get() = popupSizePercent / 100f

    /** Picture-in-picture height in % of the screen height, set with a slider. */
    val popupSizePercent: Int
        get() = if (prefs.contains(KEY_POPUP_SIZE)) prefs.getInt(KEY_POPUP_SIZE, 50).coerceIn(20, 100)
        else when (prefs.getString("pip_size", "medium")) { // the older Small/Medium/Large choice
            "small" -> 35
            "large" -> 70
            else -> 50
        }

    val durationSeconds get() = prefs.getInt("popup_duration", 30).coerceIn(5, 300)
    val extendOnMotion get() = prefs.getBoolean("extend_on_motion", true)
    val showLabel get() = prefs.getBoolean("show_label", true)
    val backCloses get() = prefs.getBoolean("back_closes", true)
    val audio get() = enumPref("audio", Audio.RING_ONLY)
    val showWhen get() = enumPref("show_when", ShowWhen.ALWAYS)
    val wakeScreen get() = prefs.getBoolean("wake_screen", true)
    val sleepAfter get() = prefs.getBoolean("sleep_after", true)
    val cooldownSeconds get() = (prefs.getString("cooldown", "10") ?: "10").toIntOrNull() ?: 10

    val quietHoursEnabled get() = prefs.getBoolean("quiet_enabled", false)
    val quietStartHour get() = (prefs.getString("quiet_start", "23") ?: "23").toInt()
    val quietEndHour get() = (prefs.getString("quiet_end", "7") ?: "7").toInt()
    val quietAllowsRings get() = prefs.getBoolean("quiet_allow_ring", true)

    val localOnly get() = prefs.getBoolean("local_only", true)

    /** Minutes between on-screen reminders while something is wrong; 0 = remind once. */
    val problemReminderMinutes get() = (prefs.getString("problem_reminder", "30") ?: "30").toIntOrNull() ?: 30

    /**
     * The one camera that pops up. Until the user picks one, the doorbell (or
     * the first camera if there is no doorbell).
     */
    val popupCamera: String?
        get() = prefs.getString(KEY_CAMERA, null)
            ?: EufyConnection.cameras.let { cams -> (cams.firstOrNull { it.isDoorbell } ?: cams.firstOrNull())?.serial }

    fun isWatched(serial: String) = serial == popupCamera
    val keepAwake get() = prefs.getBoolean("keep_awake", true)

    fun isQuietNow(now: Calendar = Calendar.getInstance()): Boolean {
        if (!quietHoursEnabled) return false
        val h = now.get(Calendar.HOUR_OF_DAY)
        val start = quietStartHour
        val end = quietEndHour
        return if (start == end) false
        else if (start < end) h in start until end
        else h >= start || h < end // spans midnight
    }

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T {
        val raw = prefs.getString(key, null) ?: return default
        return enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: default
    }

    companion object {
        const val KEY_SERVICE_ENABLED = "service_enabled"
        const val KEY_CAMERA = "popup_camera"
        const val KEY_POPUP_SIZE = "popup_size"
    }
}
