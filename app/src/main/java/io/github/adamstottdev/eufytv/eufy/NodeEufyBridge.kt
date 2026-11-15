package io.github.adamstottdev.eufytv.eufy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * [EufyBridge] backed by eufy-security-client running on the embedded Node.js
 * (see bridge/src/main.js and [NodeHostService]).
 *
 * We listen on a loopback port and start Node with the port and a one-time
 * token. Node connects back, proves the token, and we exchange length-prefixed
 * frames: JSON messages, and raw video and audio for the livestream. If Node
 * stops, it is restarted with a growing delay.
 */
class NodeEufyBridge(private val context: Context) : EufyBridge {
    private val app = context.applicationContext

    @Volatile private var running = false
    @Volatile private var listener: EufyBridge.Listener? = null
    @Volatile private var account: EufyAccount? = null
    @Volatile private var localOnly = true
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var liveSerial: String? = null
    @Volatile private var liveSink: LiveStreamSink? = null
    private var thread: Thread? = null

    override fun start(account: EufyAccount, localOnly: Boolean, listener: EufyBridge.Listener) {
        stop()
        this.account = account
        this.localOnly = localOnly
        this.listener = listener
        running = true
        thread = Thread(::runLoop, "EufyBridge").apply { start() }
    }

    override fun stop() {
        running = false
        send(JSONObject().put("cmd", "shutdown"))
        closeConnection()
        thread?.interrupt()
        thread = null
        endLive("Stopped")
    }

    override fun submitCode(code: String) {
        send(JSONObject().put("cmd", "verify").put("code", code))
    }

    override fun submitCaptcha(id: String, code: String) {
        send(JSONObject().put("cmd", "captcha").put("id", id).put("code", code))
    }

    override fun reconnect() {
        send(JSONObject().put("cmd", "reconnect"))
    }

    override fun startLivestream(deviceSerial: String, sink: LiveStreamSink) {
        liveSink?.takeIf { it !== sink }?.onStop(null)
        liveSerial = deviceSerial
        liveSink = sink
        if (!send(JSONObject().put("cmd", "live_start").put("serial", deviceSerial))) {
            endLive("Not connected to Eufy")
        }
    }

    override fun stopLivestream(deviceSerial: String) {
        if (liveSerial != deviceSerial) return
        liveSerial = null
        liveSink = null
        send(JSONObject().put("cmd", "live_stop").put("serial", deviceSerial))
    }

    // ------------------------------------------------------------ connection

    private fun runLoop() {
        var delayMs = 2_000L
        while (running) {
            val startedAt = System.currentTimeMillis()
            try {
                session()
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Bridge session ended: ${e.message}")
            } finally {
                closeConnection()
                endLive("The Eufy connection restarted")
            }
            if (!running) break
            // A session that lasted a while was healthy: restart quickly.
            if (System.currentTimeMillis() - startedAt > 10 * 60_000L) delayMs = 2_000L
            val why = NodeHostService.failureFile(app).takeIf(File::exists)?.readText()
            emitState(BridgeState.Disconnected(why ?: "Restarting the Eufy connection"))
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                break
            }
            delayMs = (delayMs * 2).coerceAtMost(60_000L)
        }
    }

    private fun session() {
        val token = ByteArray(24).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        // Explicitly IPv4: on Android getLoopbackAddress() is ::1, and Node connects to 127.0.0.1.
        val srv = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server = srv
        srv.soTimeout = 60_000
        emitState(BridgeState.Connecting)
        bindNode(srv.localPort, token)

        val s = srv.accept()
        srv.close()
        server = null
        socket = s
        s.tcpNoDelay = true
        val input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
        val output = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 16 * 1024))

        // The first message must be Node proving it has our token.
        s.soTimeout = 30_000
        val hello = readJson(input) ?: throw EOFException("No hello from Node")
        if (hello.optString("token") != token) throw SecurityException("Bridge token mismatch")
        s.soTimeout = 0
        Log.i(TAG, "Bridge up: Node ${hello.optString("node")}, eufy-security-client ${hello.optString("lib")}")
        out = output

        val acc = account ?: return
        send(
            JSONObject()
                .put("cmd", "login")
                .put("username", acc.email)
                .put("password", acc.password)
                .put("country", acc.country)
                .put("persistentDir", File(app.filesDir, "eufy").absolutePath)
                .put("localOnly", localOnly)
                .put("trustedDeviceName", "Eufy TV Doorbell (${Build.MODEL})")
        )

        var buf = ByteArray(64 * 1024)
        while (running) {
            val type = input.readUnsignedByte()
            val len = input.readInt()
            if (len < 0 || len > 16 * 1024 * 1024) throw IllegalStateException("Bad frame length $len")
            if (buf.size < len) buf = ByteArray(len)
            input.readFully(buf, 0, len)
            when (type) {
                T_JSON -> handle(JSONObject(String(buf, 0, len, Charsets.UTF_8)))
                T_VIDEO -> liveSink?.onVideo(buf, 0, len)
                T_AUDIO -> liveSink?.onAudio(buf, 0, len)
            }
        }
    }

    private fun readJson(input: DataInputStream): JSONObject? {
        val type = input.readUnsignedByte()
        val len = input.readInt()
        if (type != T_JSON || len !in 0..65_536) return null
        val b = ByteArray(len)
        input.readFully(b)
        return JSONObject(String(b, Charsets.UTF_8))
    }

    private fun bindNode(port: Int, token: String) {
        val intent = Intent(app, NodeHostService::class.java)
            .putExtra(NodeHostService.EXTRA_PORT, port)
            .putExtra(NodeHostService.EXTRA_TOKEN, token)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "Node process ended")
                closeSocketOnly()
            }
            override fun onBindingDied(name: ComponentName?) = closeSocketOnly()
        }
        connection = conn
        val ok = app.bindService(intent, conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        if (!ok) throw IllegalStateException("Could not start the Node process")
    }

    private fun closeSocketOnly() {
        runCatching { socket?.close() }
        runCatching { server?.close() }
    }

    private fun closeConnection() {
        out = null
        closeSocketOnly()
        socket = null
        server = null
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
    }

    private fun send(msg: JSONObject): Boolean {
        val o = out ?: return false
        return try {
            val bytes = msg.toString().toByteArray(Charsets.UTF_8)
            synchronized(o) {
                o.writeByte(T_JSON)
                o.writeInt(bytes.size)
                o.write(bytes)
                o.flush()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Send failed: ${e.message}")
            false
        }
    }

    // ------------------------------------------------------------ messages

    private fun handle(m: JSONObject) {
        when (m.optString("ev")) {
            "state" -> emitState(
                when (m.optString("state")) {
                    "connecting" -> BridgeState.Connecting
                    "connected" -> BridgeState.Connected(m.optBoolean("push", true)).also { pushUp = it.receivingEvents }
                    "need_code" -> BridgeState.NeedCode
                    "need_captcha" -> BridgeState.NeedCaptcha(
                        m.optString("id"),
                        runCatching { Base64.decode(m.optString("image"), Base64.DEFAULT) }.getOrDefault(ByteArray(0)),
                    )
                    "login_failed" -> BridgeState.SignInFailed(m.optString("message"))
                    else -> BridgeState.Disconnected(m.optString("message", "Disconnected"))
                }
            )
            "push" -> {
                pushUp = m.optBoolean("connected")
                if (lastState is BridgeState.Connected) emitState(BridgeState.Connected(pushUp))
            }
            "event" -> {
                val type = when (m.optString("type")) {
                    "ring" -> EventType.RING
                    "motion" -> EventType.MOTION
                    "person" -> EventType.PERSON
                    else -> return
                }
                val event = DoorbellEvent(m.optString("serial"), m.optString("name"), type)
                Log.i(TAG, "Event $type from ${event.deviceName}")
                listener?.onEvent(event)
            }
            "devices" -> {
                val list = m.optJSONArray("list") ?: return
                listener?.onCameras((0 until list.length()).map { i ->
                    val d = list.getJSONObject(i)
                    Camera(d.optString("serial"), d.optString("name"), d.optString("model"), d.optBoolean("doorbell"))
                })
            }
            "live_start" -> {
                if (m.optString("serial") != liveSerial) return
                liveSink?.onStart(
                    m.optString("codec", "h264"),
                    m.optInt("width"),
                    m.optInt("height"),
                    m.optInt("fps", 15),
                    m.optString("audio") == "aac",
                )
            }
            "live_stop", "live_error" -> {
                if (m.optString("serial") == liveSerial) {
                    endLive(if (m.optString("ev") == "live_error") "Couldn't start the video: ${m.optString("message")}" else "Video ended")
                }
            }
            "station" -> Log.i(TAG, "HomeBase ${m.optString("name")} ${if (m.optBoolean("connected")) "connected" else "disconnected"}")
            "log" -> {
                val msg = "node: ${m.optString("msg")}"
                when (m.optString("level")) {
                    "error" -> Log.e(TAG, msg)
                    "warn" -> Log.w(TAG, msg)
                    else -> Log.i(TAG, msg)
                }
            }
        }
    }

    @Volatile private var pushUp = true
    @Volatile private var lastState: BridgeState? = null

    private fun emitState(state: BridgeState) {
        if (state == lastState) return
        lastState = state
        Log.i(TAG, "State: ${state.javaClass.simpleName}")
        listener?.onState(state)
    }

    private fun endLive(reason: String?) {
        val sink = liveSink
        liveSink = null
        liveSerial = null
        sink?.onStop(reason)
    }

    private companion object {
        const val TAG = "EufyBridge"
        const val T_JSON = 1
        const val T_VIDEO = 2
        const val T_AUDIO = 3
    }
}
