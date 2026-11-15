package io.github.adamstottdev.eufytv.popup

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

/** Full-screen pop-up, used when the overlay permission has not been granted. */
class PopupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = PopupController.attachActivity(this)
        if (view == null) {
            finish()
            return
        }
        setContentView(view)
        view.requestFocus()
    }

    override fun onDestroy() {
        PopupController.activityClosed(this)
        super.onDestroy()
    }
}
