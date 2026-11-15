package com.raavivi.sysmon.core.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.raavivi.sysmon.BuildConfig
import com.raavivi.sysmon.core.data.SettingsStore
import com.raavivi.sysmon.core.log.AppLog
import com.raavivi.sysmon.core.net.ApiProvider
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Where the download / install of an update stands. */
sealed interface UpdateStep {
    data object Idle : UpdateStep
    data class Downloading(val done: Long, val total: Long) : UpdateStep
    data object NeedsPermission : UpdateStep
    data object Installing : UpdateStep
    data object Done : UpdateStep
    data class Failed(val message: String) : UpdateStep
}

data class UpdateUi(
    val check: UpdateCheck = UpdateCheck.Unchecked,
    val step: UpdateStep = UpdateStep.Idle,
)

/**
 * App-scoped updater (lives in the container so a download keeps going when the
 * screen closes). Checks `GET /api/droid/update`, downloads the APK through the
 * authed client while hashing it, then hands it to PackageInstaller. The
 * install result comes back through [UpdateInstallReceiver] -> [onInstallResult].
 */
class UpdateManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val api: ApiProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _ui = MutableStateFlow(UpdateUi())
    val ui: StateFlow<UpdateUi> = _ui.asStateFlow()

    private val updatesDir get() = File(context.cacheDir, "updates")

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Manual check; always hits the server. */
    fun check() {
        scope.launch { runCheck() }
    }

    /** Foreground check, skipped when one succeeded in the last 12 hours. */
    fun silentCheck() {
        scope.launch {
            if (!UpdateLogic.shouldCheck(settings.lastUpdateCheckNow(), System.currentTimeMillis())) return@launch
            if (_ui.value.check == UpdateCheck.Checking) return@launch
            if (runCheck() !is UpdateCheck.Error) settings.setLastUpdateCheck(System.currentTimeMillis())
        }
    }

    private suspend fun runCheck(): UpdateCheck {
        _ui.update { it.copy(check = UpdateCheck.Checking) }
        val result = when (val r = safeCall { api.api.droidUpdate() }) {
            is ApiResult.Ok -> UpdateLogic.stateFor(r.value, BuildConfig.VERSION_CODE.toLong())
            is ApiResult.Err -> UpdateCheck.Error(r.message)
        }
        _ui.update { it.copy(check = result) }
        return result
    }

    /** Download the available release, verify it, then install it. */
    fun downloadAndInstall() {
        val release = (_ui.value.check as? UpdateCheck.Available)?.release ?: return
        if (_ui.value.step is UpdateStep.Downloading || _ui.value.step == UpdateStep.Installing) return
        if (!canInstall()) {
            _ui.update { it.copy(step = UpdateStep.NeedsPermission) }
            return
        }
        scope.launch {
            try {
                val file = download(release)
                _ui.update { it.copy(step = UpdateStep.Installing) }
                install(file)
            } catch (e: UpdateException) {
                fail(e.message ?: "Update failed")
            } catch (e: IOException) {
                fail("Update failed: ${e.message ?: "I/O error"}")
            }
        }
    }

    private class UpdateException(message: String) : Exception(message)

    private fun fail(message: String) {
        AppLog.w("Update", "failed: $message")
        _ui.update { it.copy(step = UpdateStep.Failed(message)) }
    }

    private suspend fun download(first: UpdateRelease): File = withContext(Dispatchers.IO) {
        var release = first
        _ui.update { it.copy(step = UpdateStep.Downloading(0, release.size)) }
        var resp = api.api.downloadDroidApk(release.apkPath)
        if (resp.code() == 409) {
            // The server moved to a newer release since we checked: re-check, retry once.
            val state = runCheck()
            release = (state as? UpdateCheck.Available)?.release
                ?: throw UpdateException("The update changed on the server; try again.")
            resp = api.api.downloadDroidApk(release.apkPath)
        }
        if (resp.code() == 404) throw UpdateException("No update is available on the server.")
        val body = resp.body()
        if (!resp.isSuccessful || body == null) throw UpdateException("Download failed (HTTP ${resp.code()}).")
        body.use { b ->
            val header = resp.headers()["X-Sha256"]
            if (header != null && !header.trim().equals(release.sha256, ignoreCase = true)) {
                throw UpdateException("Checksum from the server does not match the update description.")
            }
            val total = b.contentLength().takeIf { it > 0 } ?: release.size
            updatesDir.mkdirs()
            val target = File(updatesDir, UpdateLogic.apkName(release.versionCode))
            // Drop older downloads (and any half-finished one) before writing.
            updatesDir.listFiles()?.forEach { it.delete() }
            var lastReport = 0L
            val actual = try {
                target.outputStream().use { out ->
                    Sha256.copy(b.byteStream(), out) { done ->
                        val now = System.nanoTime()
                        if (now - lastReport > 100_000_000L) {
                            lastReport = now
                            _ui.update { it.copy(step = UpdateStep.Downloading(done, total)) }
                        }
                    }
                }
            } catch (e: IOException) {
                target.delete()
                throw e
            }
            if (!actual.equals(release.sha256, ignoreCase = true)) {
                target.delete()
                throw UpdateException("Downloaded file failed its SHA-256 check and was deleted.")
            }
            _ui.update { it.copy(step = UpdateStep.Downloading(total, total)) }
            target
        }
    }

    private fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(file.length())
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("sys-mon-droid.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
                // The system fills in status extras, so the intent must be mutable (always so before S).
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(context, id, intent, flags)
                session.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw UpdateException("Could not start the install: ${e.message}")
        }
    }

    /** Called by [UpdateInstallReceiver] with the final PackageInstaller status. */
    fun onInstallResult(status: Int, detail: String?) {
        AppLog.i("Update", "install status=$status")
        if (status == PackageInstaller.STATUS_SUCCESS) {
            updatesDir.listFiles()?.forEach { it.delete() }
            _ui.update { it.copy(step = UpdateStep.Done) }
        } else {
            _ui.update { it.copy(step = UpdateStep.Failed(UpdateLogic.installFailureMessage(status, detail))) }
        }
    }

    /** Leave a terminal step (Failed / NeedsPermission) so the user can retry. */
    fun resetStep() {
        _ui.update {
            if (it.step is UpdateStep.Downloading || it.step == UpdateStep.Installing) it else it.copy(step = UpdateStep.Idle)
        }
    }
}
