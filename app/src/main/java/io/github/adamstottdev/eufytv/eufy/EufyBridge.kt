package io.github.adamstottdev.eufytv.eufy

/** What happened at a camera. */
enum class EventType { RING, MOTION, PERSON, TEST }

data class DoorbellEvent(
    val deviceSerial: String,
    val deviceName: String,
    val type: EventType,
    val timeMillis: Long = System.currentTimeMillis(),
)

/** Raw elementary stream from a camera: Annex-B H.264/H.265 video and ADTS AAC audio. */
interface LiveStreamSink {
    fun onStart(codec: String, width: Int, height: Int, fps: Int)
    fun onVideo(data: ByteArray, offset: Int, length: Int)
    fun onAudio(data: ByteArray, offset: Int, length: Int)
    fun onStop()
}

/**
 * The connection to Eufy. Kept behind this interface so the implementation
 * (currently eufy-security-client, later its Eufy Mega based successor) can be
 * swapped without touching the rest of the app.
 */
interface EufyBridge {
    interface Listener {
        fun onEvent(event: DoorbellEvent)
        fun onStatus(status: String)
    }

    fun start(listener: Listener)
    fun stop()
    fun startLivestream(deviceSerial: String, sink: LiveStreamSink)
    fun stopLivestream(deviceSerial: String)
}

/** Placeholder until the embedded Eufy connection lands: never produces events. */
class NoEufyBridge : EufyBridge {
    override fun start(listener: EufyBridge.Listener) = listener.onStatus("Eufy connection not set up yet")
    override fun stop() = Unit
    override fun startLivestream(deviceSerial: String, sink: LiveStreamSink) = Unit
    override fun stopLivestream(deviceSerial: String) = Unit
}
