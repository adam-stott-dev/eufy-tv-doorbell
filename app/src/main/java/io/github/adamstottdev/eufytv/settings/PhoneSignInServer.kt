package io.github.adamstottdev.eufytv.settings

import android.content.Context
import android.net.ConnectivityManager
import android.util.Base64
import android.util.Log
import io.github.adamstottdev.eufytv.R
import io.github.adamstottdev.eufytv.eufy.EufyAccount
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A one-time sign-in page the TV serves on the home network, so the user can
 * type (or paste) their Eufy details on their phone instead of with the remote.
 *
 * The QR code holds the page address and, after the '#', a random key. Browsers
 * never send the part after '#', so only the phone that scanned the code has the
 * key; the page encrypts the details with it (AES-GCM) before sending them. The
 * page accepts one sign-in, from the home network only, for [LIFETIME_MS], and
 * only while the sign-in screen is open.
 */
class PhoneSignInServer(
    private val context: Context,
    private val onAccount: (EufyAccount) -> Unit,
) {
    private val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private val path = "/s/" + ByteArray(9).also { SecureRandom().nextBytes(it) }.toB64Url()
    private val startedAt = System.currentTimeMillis()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var used = false

    /** The address to put in the QR code, or null if the TV has no home network address. */
    var url: String? = null
        private set

    fun start(): String? {
        val ip = lanAddress() ?: return null
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(ip, 0), 4)
        server = s
        url = "http://${ip.hostAddress}:${s.localPort}$path#${key.toB64Url()}"
        Thread({ acceptLoop(s) }, "PhoneSignIn").apply { isDaemon = true; start() }
        return url
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client = try { s.accept() } catch (_: Exception) { break }
            Thread({ client.use { handle(it) } }, "PhoneSignInClient").apply { isDaemon = true; start() }
            if (expired()) break
        }
        stop()
    }

    private fun expired() = used || System.currentTimeMillis() - startedAt > LIFETIME_MS

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 15_000
            val addr = c.inetAddress
            if (!(addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress)) {
                return respond(c, 403, "text/plain", "Only devices on your home network can use this page.")
            }
            val input = BufferedInputStream(c.getInputStream())
            val requestLine = readLine(input) ?: return
            var contentLength = 0
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0 && line.substring(0, i).trim().equals("Content-Length", true)) {
                    contentLength = line.substring(i + 1).trim().toIntOrNull() ?: 0
                }
            }
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0)
            val target = parts.getOrNull(1)?.substringBefore('?')
            when {
                target != path -> respond(c, 404, "text/plain", "Not found")
                expired() -> respond(c, 410, "text/plain", "This sign-in link has expired. Open the sign-in screen on the TV again and scan the new QR code.")
                method == "GET" -> respond(c, 200, "text/html; charset=utf-8", page())
                method == "POST" && contentLength in 1..8192 -> {
                    val body = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(body, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    val account = decrypt(String(body, 0, read, Charsets.US_ASCII))
                    if (account == null) {
                        respond(c, 400, "text/plain", "The TV couldn't read that. Scan the QR code on the TV again.")
                    } else {
                        used = true
                        respond(c, 200, "text/plain", "OK")
                        onAccount(account)
                        stop()
                    }
                }
                else -> respond(c, 405, "text/plain", "Not allowed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Request failed: ${e.message}")
        }
    }

    private fun decrypt(body: String): EufyAccount? = try {
        val (iv, sealed) = body.trim().split(":", limit = 2).map { Base64.decode(it, Base64.DEFAULT) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(sealed), Charsets.UTF_8))
        val email = json.getString("email").trim()
        val password = json.getString("password")
        val country = json.optString("country", "GB").trim().uppercase()
        if (email.contains("@") && password.isNotEmpty() && country.length == 2) EufyAccount(email, password, country) else null
    } catch (e: Exception) {
        Log.w(TAG, "Could not decrypt the sign-in from the phone: ${e.javaClass.simpleName}")
        null
    }

    private fun page(): String =
        context.resources.openRawResource(R.raw.phone_sign_in).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun respond(c: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 410 -> "Gone"; else -> "Error" }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n" +
            "Connection: close\r\n\r\n"
        c.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() > 0) out.toString("US-ASCII") else null
            if (b == '\n'.code) return out.toString("US-ASCII").trimEnd('\r')
            if (out.size() > 8192) return null
            out.write(b)
        }
    }

    /** The TV's IPv4 address on the home network (Wi-Fi or Ethernet). */
    private fun lanAddress(): InetAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    }

    private companion object {
        const val TAG = "PhoneSignIn"
        const val LIFETIME_MS = 10 * 60_000L

        fun ByteArray.toB64Url(): String =
            Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
