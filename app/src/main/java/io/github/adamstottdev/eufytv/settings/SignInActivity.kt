package io.github.adamstottdev.eufytv.settings

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.adamstottdev.eufytv.R
import io.github.adamstottdev.eufytv.eufy.AccountStore
import io.github.adamstottdev.eufytv.eufy.BridgeState
import io.github.adamstottdev.eufytv.eufy.EufyAccount
import io.github.adamstottdev.eufytv.eufy.EufyConnection
import io.github.adamstottdev.eufytv.service.DoorbellService
import java.util.Locale

/**
 * Sign in to Eufy on the TV: email, password and country, then a verification
 * code or captcha if Eufy asks for one.
 */
class SignInActivity : AppCompatActivity() {
    private lateinit var form: View
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var country: EditText
    private lateinit var signIn: Button
    private lateinit var codeForm: View
    private lateinit var captcha: ImageView
    private lateinit var code: EditText
    private lateinit var submitCode: Button
    private lateinit var status: TextView
    private lateinit var done: Button
    private lateinit var signOut: Button

    /** True after the user pressed Sign in, until it succeeds or fails. */
    private var waiting = false
    private val observer = EufyConnection.Observer { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_in)
        form = findViewById(R.id.form)
        email = findViewById(R.id.email)
        password = findViewById(R.id.password)
        country = findViewById(R.id.country)
        signIn = findViewById(R.id.sign_in)
        codeForm = findViewById(R.id.code_form)
        captcha = findViewById(R.id.captcha)
        code = findViewById(R.id.code)
        submitCode = findViewById(R.id.submit_code)
        status = findViewById(R.id.status)
        done = findViewById(R.id.done)
        signOut = findViewById(R.id.sign_out)

        val store = AccountStore(this)
        email.setText(store.email.orEmpty())
        country.setText(store.country ?: Locale.getDefault().country.ifBlank { "GB" })
        if (store.email != null) password.hint = getString(R.string.sign_in_password_keep)

        signIn.setOnClickListener { doSignIn() }
        country.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { doSignIn(); true } else false
        }
        submitCode.setOnClickListener { doSubmitCode() }
        code.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { doSubmitCode(); true } else false
        }
        done.setOnClickListener { finish() }
        signOut.setOnClickListener {
            EufyConnection.signOut(this)
            password.setText("")
            password.hint = getString(R.string.sign_in_password)
            render()
        }

        // The service owns the connection; make sure it is running.
        if (Settings(this).serviceEnabled) DoorbellService.start(this)
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
        EufyConnection.addObserver(observer)
        render()
        if (form.visibility == View.VISIBLE && email.text.isEmpty()) email.requestFocus()
    }

    override fun onPause() {
        isVisible = false
        EufyConnection.removeObserver(observer)
        super.onPause()
    }

    private fun doSignIn() {
        val mail = email.text.toString().trim()
        val cc = country.text.toString().trim().uppercase(Locale.ROOT)
        var pw = password.text.toString()
        if (pw.isEmpty()) {
            // Keep the saved password when signing in again with the same email.
            val saved = AccountStore(this).load()
            if (saved != null && saved.email.equals(mail, ignoreCase = true)) pw = saved.password
        }
        when {
            !mail.contains("@") -> return showError(getString(R.string.sign_in_need_email))
            pw.isEmpty() -> return showError(getString(R.string.sign_in_need_password))
            cc.length != 2 -> return showError(getString(R.string.sign_in_need_country))
        }
        waiting = true
        password.setText("")
        EufyConnection.signIn(this, EufyAccount(mail, pw, cc))
        if (Settings(this).serviceEnabled) DoorbellService.start(this)
        render()
    }

    private fun doSubmitCode() {
        val text = code.text.toString().trim()
        if (text.isEmpty()) return
        waiting = true
        code.setText("")
        when (val s = EufyConnection.state) {
            is BridgeState.NeedCaptcha -> EufyConnection.submitCaptcha(s.id, text)
            else -> EufyConnection.submitCode(text)
        }
        status.setTextColor(Color.WHITE)
        status.setText(R.string.sign_in_checking)
    }

    private fun showError(text: String) {
        status.setTextColor(ERROR)
        status.text = text
    }

    private fun render() {
        val state = EufyConnection.state
        val saved = AccountStore(this).email
        var showForm = true
        var showCode = false
        var showCaptcha = false
        status.setTextColor(Color.WHITE)
        when (state) {
            BridgeState.SignedOut -> status.text = ""
            BridgeState.Connecting -> status.setText(if (waiting) R.string.sign_in_checking else R.string.sign_in_connecting)
            BridgeState.NeedCode -> {
                showForm = false
                showCode = true
                code.hint = getString(R.string.sign_in_code_hint)
                status.text = getString(R.string.sign_in_code_sent, saved ?: "")
            }
            is BridgeState.NeedCaptcha -> {
                showForm = false
                showCode = true
                showCaptcha = true
                code.hint = getString(R.string.sign_in_captcha_hint)
                BitmapFactory.decodeByteArray(state.png, 0, state.png.size)?.let(captcha::setImageBitmap)
                status.setText(R.string.sign_in_captcha)
            }
            is BridgeState.Connected -> {
                waiting = false
                showForm = false
                val n = EufyConnection.cameras.size
                status.text = if (n > 0) resources.getQuantityString(R.plurals.sign_in_ok_cameras, n, saved ?: "", n)
                else getString(R.string.sign_in_ok, saved ?: "")
            }
            is BridgeState.SignInFailed -> {
                waiting = false
                showError(getString(R.string.sign_in_failed, state.message))
            }
            is BridgeState.Disconnected -> showError(getString(R.string.sign_in_offline, state.message))
        }
        val signedIn = saved != null && state != BridgeState.SignedOut
        form.visibility = if (showForm) View.VISIBLE else View.GONE
        signIn.isEnabled = state != BridgeState.Connecting || !waiting
        codeForm.visibility = if (showCode) View.VISIBLE else View.GONE
        captcha.visibility = if (showCaptcha) View.VISIBLE else View.GONE
        done.visibility = if (state is BridgeState.Connected) View.VISIBLE else View.GONE
        signOut.visibility = if (signedIn) View.VISIBLE else View.GONE
        if (showCode && !code.hasFocus()) code.requestFocus()
        if (state is BridgeState.Connected && currentFocus !== signOut) done.requestFocus()
    }

    companion object {
        private const val ERROR = 0xFFFF8A80.toInt()

        /** Lets the problem alerts stay quiet while the user is already on this screen. */
        @Volatile var isVisible = false
            private set
    }
}
