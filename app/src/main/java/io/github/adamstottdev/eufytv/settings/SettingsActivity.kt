package io.github.adamstottdev.eufytv.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.github.adamstottdev.eufytv.service.DoorbellService

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings(this).serviceEnabled) DoorbellService.start(this)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }
}
