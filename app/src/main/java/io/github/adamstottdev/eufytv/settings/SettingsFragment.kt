package io.github.adamstottdev.eufytv.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import io.github.adamstottdev.eufytv.BuildConfig
import io.github.adamstottdev.eufytv.R
import io.github.adamstottdev.eufytv.eufy.AccountStore
import io.github.adamstottdev.eufytv.eufy.BridgeState
import io.github.adamstottdev.eufytv.eufy.EufyConnection
import io.github.adamstottdev.eufytv.service.DoorbellService
import io.github.adamstottdev.eufytv.service.ProblemAlerts

class SettingsFragment : PreferenceFragmentCompat() {
    private val observer = EufyConnection.Observer { refreshStatus() }

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
        findPreference<Preference>("test_popup_delayed")?.setOnPreferenceClickListener {
            DoorbellService.start(requireContext(), DoorbellService.ACTION_TEST, delayMs = 15_000)
            Toast.makeText(requireContext(), R.string.test_in_15, Toast.LENGTH_LONG).show()
            true
        }
        findPreference<Preference>("battery")?.setOnPreferenceClickListener {
            requestBatteryExemption()
            true
        }
        findPreference<Preference>("account")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), SignInActivity::class.java))
            true
        }
        findPreference<Preference>("problem")?.setOnPreferenceClickListener {
            val p = ProblemAlerts.currentProblem(requireContext())
            if (p?.needsSignIn == true || p == null) startActivity(Intent(requireContext(), SignInActivity::class.java))
            else EufyConnection.reconnect()
            true
        }
        findPreference<Preference>("live_view")?.setOnPreferenceClickListener {
            DoorbellService.start(requireContext(), DoorbellService.ACTION_LIVE_ALL)
            true
        }
        findPreference<SwitchPreferenceCompat>("local_only")?.setOnPreferenceChangeListener { _, _ ->
            // Reconnect with the new setting once it is saved.
            view?.post { if (EufyConnection.isRunning) EufyConnection.start(requireContext()) }
            true
        }
        findPreference<MultiSelectListPreference>(Settings.KEY_CAMERAS)?.setOnPreferenceChangeListener { _, _ ->
            view?.post { refreshStatus() }
            true
        }
        findPreference<androidx.preference.SeekBarPreference>(Settings.KEY_POPUP_SIZE)?.apply {
            // Carry over the older Small/Medium/Large choice.
            value = Settings(requireContext()).popupSizePercent
            updatesContinuously = false
        }
        findPreference<Preference>("version")?.summary = BuildConfig.VERSION_NAME
    }

    override fun onResume() {
        super.onResume()
        EufyConnection.addObserver(observer)
        refreshStatus()
    }

    override fun onPause() {
        EufyConnection.removeObserver(observer)
        super.onPause()
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

        refreshAccount()
    }

    private fun refreshAccount() {
        val ctx = context ?: return
        val email = AccountStore(ctx).email
        val state = EufyConnection.state
        findPreference<Preference>("account")?.summary = when {
            email == null || state == BridgeState.SignedOut -> getString(R.string.account_signed_out)
            state is BridgeState.Connected -> getString(R.string.account_connected, email)
            state is BridgeState.Connecting -> getString(R.string.account_connecting, email)
            state is BridgeState.Disconnected -> getString(R.string.account_offline, email)
            else -> getString(R.string.account_action, email)
        }

        val problem = ProblemAlerts.currentProblem(ctx)
        findPreference<PreferenceCategory>("problem_category")?.isVisible = problem != null
        findPreference<Preference>("problem")?.apply {
            title = problem?.message
            summary = getString(R.string.problem_fix_it)
        }

        val cams = EufyConnection.cameras
        findPreference<MultiSelectListPreference>(Settings.KEY_CAMERAS)?.apply {
            isEnabled = cams.isNotEmpty()
            entries = cams.map { if (it.model.isNotBlank()) "${it.name} (${it.model})" else it.name }.toTypedArray()
            entryValues = cams.map { it.serial }.toTypedArray()
            val watched = Settings(ctx).watchedCameras
            if (watched == null && cams.isNotEmpty()) values = cams.map { it.serial }.toSet()
            summary = when {
                cams.isEmpty() -> getString(R.string.cameras_none)
                values.isEmpty() -> getString(R.string.cameras_none_selected)
                values.containsAll(cams.map { it.serial }) -> getString(R.string.cameras_all)
                else -> cams.filter { it.serial in values }.joinToString(", ") { it.name }
            }
        }
        findPreference<Preference>("live_view")?.apply {
            val s = Settings(ctx)
            val chosen = cams.filter { s.isWatched(it.serial) }
            isEnabled = chosen.isNotEmpty()
            summary = when {
                cams.isEmpty() -> getString(R.string.live_view_none)
                chosen.isEmpty() -> getString(R.string.live_test_none)
                chosen.size == 1 -> getString(R.string.live_test_one, chosen[0].name)
                else -> getString(R.string.live_test_many, chosen.joinToString(", ") { it.name })
            }
        }
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
