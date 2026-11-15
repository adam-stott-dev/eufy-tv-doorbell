package io.github.adamstottdev.eufytv.eufy

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import org.fellow99.nodebridge.NodeRuntime
import org.fellow99.nodebridge.NodeRuntimeOptions
import org.fellow99.nodebridge.NodeState
import java.io.File
import java.util.TimeZone

/**
 * Runs the embedded Node.js with the Eufy bridge script, in its own process
 * (":node") so that it can be restarted cleanly: Node can only start once per
 * process, so this process ends when the app unbinds or Node stops.
 *
 * The app's main process binds to this service, which keeps it as important as
 * the app's foreground service.
 */
class NodeHostService : Service() {

    override fun onBind(intent: Intent): IBinder {
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (started) {
            // A previous Node already ran in this process; only a fresh process can start another.
            Log.w(TAG, "Node already ran in this process, restarting the process")
            Process.killProcess(Process.myPid())
        }
        started = true
        Thread({ startNode(port, token) }, "NodeStarter").start()
        return Binder()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "App unbound, stopping Node")
        Process.killProcess(Process.myPid())
        return false
    }

    override fun onDestroy() {
        Process.killProcess(Process.myPid())
    }

    private fun startNode(port: Int, token: String) {
        failureFile(this).delete()
        try {
            val entry = installBridge()
            rotateLog()
            val runtime = NodeRuntime.getInstance(this)
            val result = runtime.start(
                NodeRuntimeOptions(
                    appEntry = entry.absolutePath,
                    cwd = entry.parentFile!!.absolutePath,
                    env = mapOf(
                        "BRIDGE_PORT" to port.toString(),
                        "BRIDGE_TOKEN" to token,
                        "TZ" to TimeZone.getDefault().id,
                    ),
                )
            )
            if (!result.ok) {
                fail("Could not start Node.js: ${result.message}")
                return
            }
            Log.i(TAG, "Node.js started")
            // Node runs until the process ends; if it stops on its own, end the process too.
            while (true) {
                Thread.sleep(1000)
                when (val s = runtime.state.value) {
                    is NodeState.Failed -> { fail("Node.js failed (${s.stage}): ${s.message}"); return }
                    is NodeState.Exited -> { fail("Node.js stopped"); return }
                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            fail("Could not start Node.js: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        runCatching { failureFile(this).writeText(message) }
        Process.killProcess(Process.myPid())
    }

    /** Copies the bundled bridge out of the APK's assets (once per app version). */
    private fun installBridge(): File {
        val info = packageManager.getPackageInfo(packageName, 0)
        val root = File(filesDir, "eufy-bridge")
        val dir = File(root, "${PackageInfoCompat.getLongVersionCode(info)}-${info.lastUpdateTime}")
        val main = File(dir, "main.js")
        val ready = File(dir, ".ready")
        if (!ready.exists()) {
            root.listFiles()?.forEach { it.deleteRecursively() }
            copyAssets("eufy-bridge", dir)
            ready.writeText("ok")
            Log.i(TAG, "Installed bridge to $dir")
        }
        return main
    }

    private fun copyAssets(path: String, dest: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (child in children) copyAssets("$path/$child", File(dest, child))
    }

    /** Node's output goes to a log file; keep it from growing without limit. */
    private fun rotateLog() {
        val log = File(filesDir, "node-bridge/node_bridge.log")
        if (log.length() > 1_000_000) {
            log.renameTo(File(log.parentFile, "node_bridge.old.log"))
        }
    }

    companion object {
        private const val TAG = "NodeHost"
        const val EXTRA_PORT = "port"
        const val EXTRA_TOKEN = "token"
        @Volatile private var started = false

        /** Why Node last failed to start, written by the :node process for the app to read. */
        fun failureFile(context: Context) = File(context.filesDir, "node-bridge/last-failure.txt").apply { parentFile?.mkdirs() }
    }
}
