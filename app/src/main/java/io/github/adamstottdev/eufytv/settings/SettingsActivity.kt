package io.github.adamstottdev.eufytv.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import io.github.adamstottdev.eufytv.service.DoorbellService

class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings(this).serviceEnabled) DoorbellService.start(this)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    /** Opens a sub-menu; Back returns to the main list. */
    override fun onPreferenceStartScreen(caller: PreferenceFragmentCompat, pref: PreferenceScreen): Boolean {
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment.forScreen(pref.key))
            .addToBackStack(pref.key)
            .commit()
        return true
    }
}
