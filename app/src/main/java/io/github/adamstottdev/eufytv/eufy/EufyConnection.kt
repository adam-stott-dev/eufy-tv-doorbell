package io.github.adamstottdev.eufytv.eufy

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.adamstottdev.eufytv.settings.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's single connection to Eufy, shared by the background service, the
 * sign-in screen, the settings screen and the pop-up. Owned (started and
 * stopped) by the background service. Observers are called on the main thread.
 */
object EufyConnection {
    fun interface Observer { fun onChanged() }

    private val main = Handler(Looper.getMainLooper())
    private val observers = mutableSetOf<Observer>()
    private var bridge: EufyBridge? = null

    /** Receives ring and motion events; set by the background service. */
    @Volatile var eventHandler: ((DoorbellEvent) -> Unit)? = null

    @Volatile var state: BridgeState = BridgeState.SignedOut
        private set

    /** When [state] last changed, for the problem alerts. */
    @Volatile var stateSince: Long = System.currentTimeMillis()
        private set

    @Volatile var cameras: List<Camera> = emptyList()
        private set

    val isRunning get() = bridge != null

    fun addObserver(o: Observer) { observers += o }
    fun removeObserver(o: Observer) { observers -= o }

    /** Connects with the saved account, if there is one. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (cameras.isEmpty()) cameras = loadCameras(app)
        val account = AccountStore(app).load()
        if (account == null) {
            stop()
            setState(BridgeState.SignedOut)
            return
        }
        bridge?.stop()
        val b = NodeEufyBridge(app)
        bridge = b
        setState(BridgeState.Connecting)
        b.start(account, Settings(app).localOnly, object : EufyBridge.Listener {
            override fun onEvent(event: DoorbellEvent) {
                eventHandler?.invoke(event)
            }
            override fun onState(state: BridgeState) {
                if (bridge === b) setState(state)
            }
            override fun onCameras(cameras: List<Camera>) {
                if (bridge !== b) return
                saveCameras(app, cameras)
                main.post {
                    this@EufyConnection.cameras = cameras
                    notifyObservers()
                }
            }
        })
    }

    fun stop() {
        bridge?.stop()
        bridge = null
    }

    fun signIn(context: Context, account: EufyAccount) {
        val store = AccountStore(context)
        // A different account starts from a clean Eufy session.
        if (store.email != null && !store.email.equals(account.email, ignoreCase = true)) {
            store.clear()
            saveCameras(context, emptyList())
            cameras = emptyList()
        }
        store.save(account)
        start(context)
    }

    fun signOut(context: Context) {
        stop()
        AccountStore(context).clear()
        saveCameras(context, emptyList())
        cameras = emptyList()
        setState(BridgeState.SignedOut)
    }

    fun submitCode(code: String) = bridge?.submitCode(code)
    fun submitCaptcha(id: String, code: String) = bridge?.submitCaptcha(id, code)
    fun reconnect() = bridge?.reconnect()

    fun startLivestream(serial: String, sink: LiveStreamSink) {
        val b = bridge
        if (b == null) sink.onStop("Not signed in to Eufy") else b.startLivestream(serial, sink)
    }

    fun stopLivestream(serial: String) = bridge?.stopLivestream(serial)

    fun cameraName(serial: String) = cameras.firstOrNull { it.serial == serial }?.name

    private fun setState(s: BridgeState) {
        main.post {
            if (s != state) {
                state = s
                stateSince = System.currentTimeMillis()
            }
            notifyObservers()
        }
    }

    private fun notifyObservers() = observers.toList().forEach { it.onChanged() }

    // Remember the camera list so settings can show it while offline.
    private fun saveCameras(context: Context, list: List<Camera>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("serial", it.serial).put("name", it.name).put("model", it.model).put("doorbell", it.isDoorbell))
        }
        context.getSharedPreferences("eufy", Context.MODE_PRIVATE).edit().putString("cameras", arr.toString()).apply()
    }

    private fun loadCameras(context: Context): List<Camera> {
        val raw = context.getSharedPreferences("eufy", Context.MODE_PRIVATE).getString("cameras", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Camera(o.getString("serial"), o.optString("name"), o.optString("model"), o.optBoolean("doorbell"))
            }
        }.getOrDefault(emptyList())
    }
}
