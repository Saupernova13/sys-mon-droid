package com.raavivi.sysmon.core.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.raavivi.sysmon.BuildConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * App-wide on-device log. Call [init] first thing in `Application.onCreate`; before that
 * (and in unit tests) every call is a no-op. Files live in `Download/sys-mon/logs/`
 * (API 29+) or the app's external files dir, one per hour, kept 24 hours.
 *
 * The on/off switch is mirrored in SharedPreferences and an in-memory flag so every write
 * can check it synchronously. When off, nothing is written (crashes included); old files
 * are still pruned.
 */
object AppLog {
    @Volatile private var writer: LogWriter? = null
    @Volatile private var location: String = "Download/sys-mon/logs"
    @Volatile private var enabled: Boolean = true
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (writer != null) return
        val app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        enabled = p.getBoolean(KEY_ENABLED, true)
        val store: LogStore
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            store = MediaStoreLogStore(app)
        } else {
            val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "logs")
            store = DirLogStore(dir)
            location = dir.absolutePath
        }
        val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "applog").apply { isDaemon = true } }
        val w = LogWriter(store, exec, enabled = { enabled })
        writer = w
        w.prune()
        installCrashHandler(w)
        i(
            "App",
            "start v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                "${Build.MODEL} Android ${Build.VERSION.RELEASE} SDK ${Build.VERSION.SDK_INT}",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            exec.execute { logExitReasons(app, p, w) }
        }
    }

    fun i(tag: String, msg: String) { writer?.log('I', tag, msg) }
    fun w(tag: String, msg: String) { writer?.log('W', tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) { writer?.log('E', tag, msg, t) }

    fun isEnabled(): Boolean = enabled

    /** Flips the switch (persisted); logs one line when turned on. */
    fun setEnabled(on: Boolean) {
        val was = enabled
        enabled = on
        prefs?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
        if (on && !was) i("AppLog", "logging enabled")
    }

    /** Human-readable folder shown in Settings. */
    fun locationLabel(): String = location

    /** Writes all log files to a zip at [dest]; returns false when there was nothing to share. */
    fun exportZip(dest: File): Boolean {
        val w = writer ?: return false
        dest.parentFile?.mkdirs()
        val n = dest.outputStream().use { w.zipTo(it) }
        return n > 0
    }

    fun clear() {
        writer?.clear()
    }

    private fun installCrashHandler(w: LogWriter) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                w.logSync('E', "Crash", "Uncaught exception on thread ${thread.name}", throwable)
            } catch (_: Throwable) {
                // never mask the real crash
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun logExitReasons(context: Context, p: SharedPreferences, w: LogWriter) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val last = p.getLong(KEY_LAST_EXIT, 0L)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .filter { it.timestamp > last }
                .sortedBy { it.timestamp }
            // Watermark advances even when logging is off, so re-enabling does not dump old exits.
            if (enabled) {
                for (x in exits) logExit(w, x)
            }
            exits.maxOfOrNull { it.timestamp }?.let { p.edit().putLong(KEY_LAST_EXIT, it).apply() }
        } catch (_: Throwable) {
            // best effort
        }
    }

    private fun logExit(w: LogWriter, x: ApplicationExitInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        w.logSync(
            'I', "ExitInfo",
            "previous exit: reason=${reasonName(x.reason)} status=${x.status} " +
                "importance=${x.importance} desc=${x.description}",
        )
        if (x.reason == ApplicationExitInfo.REASON_ANR || x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
            val trace = runCatching {
                x.traceInputStream?.use { s ->
                    val buf = ByteArray(TRACE_MAX)
                    var n = 0
                    while (n < buf.size) {
                        val r = s.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                    String(buf, 0, n, Charsets.UTF_8)
                }
            }.getOrNull()
            if (!trace.isNullOrBlank()) w.logSync('I', "ExitInfo", "trace:\n$trace")
        }
    }

    private const val PREFS = "applog"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_EXIT = "last_exit_ts"
    private const val TRACE_MAX = 64 * 1024

    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "OTHER($r)"
    }
}
