package io.github.adamstottdev.eufytv

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A small event log on the TV (doorbell events, HomeBase connection, pop-up and
 * video timings), so a slow pop-up can be looked into afterwards. Android's own
 * log is too small to keep these for long. Kept under 512 KB, readable with
 * `adb shell cat /sdcard/Android/data/io.github.adamstottdev.eufytv/files/events.log`.
 */
object Diag {
    private const val MAX_BYTES = 512 * 1024L
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "Diag").apply { isDaemon = true } }
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)
    @Volatile private var file: File? = null

    fun init(context: Context) {
        if (file == null) file = context.applicationContext.getExternalFilesDir(null)?.let { File(it, "events.log") }
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        write(tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        write(tag, msg)
    }

    private fun write(tag: String, msg: String) {
        val f = file ?: return
        val line = "${fmt.format(Date())} $tag: $msg\n"
        io.execute {
            runCatching {
                if (f.length() > MAX_BYTES) {
                    val old = File(f.parentFile, "events.old.log")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText(line)
            }
        }
    }
}
