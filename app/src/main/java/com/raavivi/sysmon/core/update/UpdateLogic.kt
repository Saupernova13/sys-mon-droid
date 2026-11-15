package com.raavivi.sysmon.core.update

import android.content.pm.PackageInstaller
import com.raavivi.sysmon.core.model.DroidUpdateResponse
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** A downloadable release as described by the server. */
data class UpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val size: Long,
    val sha256: String,
    val notes: String?,
    val builtAt: String?,
    val apkPath: String,
)

/** Result of comparing the server with the installed build. */
sealed interface UpdateCheck {
    data object Unchecked : UpdateCheck
    data object Checking : UpdateCheck
    data object Disabled : UpdateCheck
    data object UpToDate : UpdateCheck
    data class Available(val release: UpdateRelease) : UpdateCheck
    data class Error(val message: String) : UpdateCheck
}

/** Pure decisions for the updater, kept free of Android state so JVM tests cover them. */
object UpdateLogic {
    const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000

    /** Maps a server response to a state for the build whose versionCode is [installed]. */
    fun stateFor(resp: DroidUpdateResponse, installed: Long): UpdateCheck {
        if (!resp.enabled) return UpdateCheck.Disabled
        if (!resp.available) return UpdateCheck.UpToDate
        val code = resp.versionCode
        val sha = resp.sha256
        val path = resp.apkPath
        if (code == null || sha.isNullOrBlank() || path.isNullOrBlank()) {
            return UpdateCheck.Error("The server sent an incomplete update description")
        }
        if (code <= installed) return UpdateCheck.UpToDate
        return UpdateCheck.Available(
            UpdateRelease(
                versionCode = code,
                versionName = resp.versionName?.takeIf { it.isNotBlank() } ?: code.toString(),
                size = resp.size ?: 0,
                sha256 = sha.lowercase(),
                notes = resp.notes?.takeIf { it.isNotBlank() },
                builtAt = resp.builtAt?.takeIf { it.isNotBlank() },
                apkPath = path,
            ),
        )
    }

    fun isNewer(serverCode: Long?, installed: Long): Boolean = serverCode != null && serverCode > installed

    /** True when a silent check is due: never run, 12 h passed, or the clock went backwards. */
    fun shouldCheck(lastCheckMs: Long, nowMs: Long, intervalMs: Long = CHECK_INTERVAL_MS): Boolean =
        lastCheckMs <= 0 || nowMs < lastCheckMs || nowMs - lastCheckMs >= intervalMs

    /** File name for a downloaded release. */
    fun apkName(versionCode: Long): String = "sys-mon-droid-$versionCode.apk"

    /** Human text for a PackageInstaller failure status. */
    fun installFailureMessage(status: Int, detail: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "This build is signed with a different key. Uninstall the app and install it once by hand; updates work after that."
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Install cancelled."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Install was blocked by the system or a device policy."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
        PackageInstaller.STATUS_FAILURE_INVALID -> "The downloaded APK was rejected as invalid."
        else -> "Install failed" + (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
    }
}

/** An FCM `type=droid_update` push, parsed from its string-only data map. */
data class DroidUpdatePush(val versionCode: Long, val versionName: String) {
    companion object {
        const val TYPE = "droid_update"

        fun from(data: Map<String, String>): DroidUpdatePush? {
            if (data["type"] != TYPE) return null
            val code = data["versionCode"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val name = data["versionName"]?.takeIf { it.isNotBlank() } ?: code.toString()
            return DroidUpdatePush(code, name)
        }
    }
}

/** SHA-256 helpers: stream-hash while copying, or hash a finished file. */
object Sha256 {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** Copies [input] to [output] hashing on the way; returns lowercase hex. [onBytes] gets the running total. */
    fun copy(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            output.write(buf, 0, n)
            total += n
            onBytes(total)
        }
        return hex(md.digest())
    }

    fun ofFile(file: File): String = file.inputStream().use { copy(it, NullOut) }

    /** Does [file] hash to [expected] (case-insensitive hex)? */
    fun matches(file: File, expected: String): Boolean = ofFile(file).equals(expected.trim(), ignoreCase = true)
}

private object NullOut : OutputStream() {
    override fun write(b: Int) {}
    override fun write(b: ByteArray, off: Int, len: Int) {}
}
