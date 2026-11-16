package io.github.adamstottdev.eufytv.eufy

/** What happened at a camera. */
enum class EventType { RING, MOTION, PERSON, LIVE, TEST }

data class DoorbellEvent(
    val deviceSerial: String,
    val deviceName: String,
    val type: EventType,
    val timeMillis: Long = System.currentTimeMillis(),
)

/** A camera or doorbell on the user's Eufy account. */
data class Camera(
    val serial: String,
    val name: String,
    val model: String,
    val isDoorbell: Boolean,
)

/** Where the connection to Eufy is up to. */
sealed interface BridgeState {
    /** No Eufy account saved. */
    data object SignedOut : BridgeState
    data object Connecting : BridgeState
    data class Connected(val receivingEvents: Boolean) : BridgeState
    /** Eufy emailed a verification code that the user must type in. */
    data object NeedCode : BridgeState
    data class NeedCaptcha(val id: String, val png: ByteArray) : BridgeState
    /** Eufy rejected the sign-in: the user has to sign in again. */
    data class SignInFailed(val message: String) : BridgeState
    /** Temporarily unreachable; retrying by itself. */
    data class Disconnected(val message: String) : BridgeState
}

/** Raw elementary stream from a camera: Annex-B H.264/H.265 video and ADTS AAC audio. */
interface LiveStreamSink {
    fun onStart(codec: String, width: Int, height: Int, fps: Int, hasAudio: Boolean)
    fun onVideo(data: ByteArray, offset: Int, length: Int)
    fun onAudio(data: ByteArray, offset: Int, length: Int)
    fun onStop(reason: String?)
}

data class EufyAccount(
    val email: String,
    val password: String,
    val country: String,
)

/**
 * The connection to Eufy. Kept behind this interface so the implementation
 * (currently eufy-security-client, later its Eufy Mega based successor) can be
 * swapped without touching the rest of the app.
 */
interface EufyBridge {
    interface Listener {
        fun onEvent(event: DoorbellEvent)
        fun onState(state: BridgeState)
        fun onCameras(cameras: List<Camera>)
    }

    fun start(account: EufyAccount, localOnly: Boolean, listener: Listener)
    fun stop()
    fun submitCode(code: String)
    fun submitCaptcha(id: String, code: String)
    /** Ask for a reconnect now, e.g. after the network comes back. */
    fun reconnect()
    fun startLivestream(deviceSerial: String, sink: LiveStreamSink)
    fun stopLivestream(deviceSerial: String)
}
