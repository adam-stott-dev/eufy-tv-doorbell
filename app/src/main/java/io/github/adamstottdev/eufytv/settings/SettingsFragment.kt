package io.github.adamstottdev.eufytv.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import io.github.adamstottdev.eufytv.BuildConfig
import io.github.adamstottdev.eufytv.R
import io.github.adamstottdev.eufytv.service.DoorbellService

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<SwitchPreferenceCompat>(Settings.KEY_SERVICE_ENABLED)?.setOnPreferenceChangeListener { _, on ->
            if (on == true) DoorbellService.start(requireContext()) else DoorbellService.stop(requireContext())
            view?.post { refreshStatus() }
            true
        }
        findPreference<Preference>("test_popup")?.setOnPreferenceClickListener {
            DoorbellService.start(requireContext(), DoorbellService.ACTION_TEST)
            true
        }
        findPreference<Preference>("battery")?.setOnPreferenceClickListener {
            requestBatteryExemption()
            true
        }
        findPreference<Preference>("version")?.summary = BuildConfig.VERSION_NAME
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val ctx = requireContext()
        val pkg = ctx.packageName

        findPreference<Preference>("status_service")?.summary = getString(
            if (DoorbellService.status == "running") R.string.status_running else R.string.status_stopped
        )

        findPreference<Preference>("status_overlay")?.summary =
            if (AndroidSettings.canDrawOverlays(ctx)) getString(R.string.overlay_granted)
            else getString(R.string.overlay_missing, pkg)

        val pm = ctx.getSystemService(PowerManager::class.java)
        findPreference<Preference>("battery")?.summary =
            if (pm.isIgnoringBatteryOptimizations(pkg)) getString(R.string.battery_ok)
            else getString(R.string.battery_missing, pkg)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val ctx = requireContext()
        try {
            startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            )
        } catch (_: Exception) {
            // Android TV often has no screen for this; the summary shows the adb command instead.
        }
    }
}
