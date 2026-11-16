package io.github.adamstottdev.eufytv.eufy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the Eufy account on the TV. The password is encrypted with a key held
 * by the Android Keystore, which never leaves the device's secure hardware.
 */
class AccountStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("account", Context.MODE_PRIVATE)

    fun load(): EufyAccount? {
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val sealed = prefs.getString(KEY_PASSWORD, null) ?: return null
        val password = try {
            decrypt(sealed)
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt the saved password", e)
            return null
        }
        return EufyAccount(email, password, prefs.getString(KEY_COUNTRY, "GB") ?: "GB")
    }

    /** The email address of the saved account, without decrypting anything. */
    val email: String? get() = prefs.getString(KEY_EMAIL, null)
    val country: String? get() = prefs.getString(KEY_COUNTRY, null)

    fun save(account: EufyAccount) {
        prefs.edit()
            .putString(KEY_EMAIL, account.email)
            .putString(KEY_COUNTRY, account.country)
            .putString(KEY_PASSWORD, encrypt(account.password))
            .apply()
    }

    /** Forgets the account and Eufy's saved session for it. */
    fun clear() {
        prefs.edit().clear().apply()
        File(app.filesDir, "eufy").deleteRecursively()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(sealed: String): String {
        val (iv, ct) = sealed.split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private companion object {
        const val TAG = "AccountStore"
        const val ALIAS = "eufy_account"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val KEY_EMAIL = "email"
        const val KEY_COUNTRY = "country"
        const val KEY_PASSWORD = "password"
    }
}
