package com.raavivi.sysmon.ui.whatsapp

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Plays one voice note at a time. The note is downloaded once into `cacheDir/wa-vn/<id>.ogg`
 * (through the authed client) and played with [MediaPlayer]. State is Compose state so bubbles
 * read it directly; position is polled while playing.
 */
class VoiceNotePlayer(
    cacheDir: File,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val onMessage: (String) -> Unit,
) {
    private val dir = File(cacheDir, "wa-vn")

    /** Id of the note that is loaded (playing or paused), or null. */
    var currentId by mutableStateOf<String?>(null)
        private set

    /** Id of the note being downloaded / prepared, or null. */
    var loadingId by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set
    var speed by mutableFloatStateOf(1f)
        private set

    private var player: MediaPlayer? = null
    private var pollJob: Job? = null
    private var loadJob: Job? = null

    /** Play/pause the note [id]; starts (and downloads, if needed) when it is not the loaded one. */
    fun toggle(id: String, url: String) {
        if (id == loadingId) return
        if (id == currentId) {
            if (isPlaying) pause() else start()
            return
        }
        release()
        loadingId = id
        loadJob = scope.launch {
            val mp = try {
                val file = withContext(Dispatchers.IO) { fetch(id, url) }
                withContext(Dispatchers.IO) {
                    MediaPlayer().also { m ->
                        try {
                            m.setDataSource(file.absolutePath)
                            m.prepare()
                        } catch (e: Exception) {
                            m.release()
                            throw e
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadingId = null
                onMessage(
                    if (e is DownloadException) "Voice note failed: ${e.message}"
                    else "Can't play this voice note on this Android version",
                )
                return@launch
            }
            player = mp
            currentId = id
            loadingId = null
            durationMs = mp.duration.coerceAtLeast(0)
            positionMs = 0
            mp.setOnCompletionListener {
                isPlaying = false
                positionMs = 0
                pollJob?.cancel()
                runCatching { it.seekTo(0) }
            }
            mp.setOnErrorListener { _, _, _ ->
                release()
                onMessage("Voice note playback error")
                true
            }
            start()
        }
    }

    fun seekTo(ms: Int) {
        val mp = player ?: return
        val to = ms.coerceIn(0, durationMs)
        runCatching { mp.seekTo(to) }
        positionMs = to
    }

    fun cycleSpeed() {
        speed = nextSpeed(speed)
        if (isPlaying) applySpeed()
    }

    private fun start() {
        val mp = player ?: return
        runCatching {
            mp.start()
            applySpeed()
        }.onFailure {
            release()
            onMessage("Voice note playback error")
            return
        }
        isPlaying = true
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                positionMs = runCatching { mp.currentPosition }.getOrDefault(positionMs)
                delay(POLL_MS)
            }
        }
    }

    private fun pause() {
        runCatching { player?.pause() }
        isPlaying = false
        pollJob?.cancel()
    }

    /** Only called while playing: setting playback params on a paused player would start it. */
    private fun applySpeed() {
        val mp = player ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { mp.playbackParams = PlaybackParams().setSpeed(speed) }
        }
    }

    /** Stop and free everything; safe to call repeatedly. */
    fun release() {
        loadJob?.cancel()
        loadJob = null
        pollJob?.cancel()
        pollJob = null
        player?.let { runCatching { it.release() } }
        player = null
        currentId = null
        loadingId = null
        isPlaying = false
        positionMs = 0
        durationMs = 0
    }

    private class DownloadException(message: String) : IOException(message)

    private fun fetch(id: String, url: String): File {
        dir.mkdirs()
        val name = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "$name.ogg")
        if (file.isFile && file.length() > 0) return file
        val tmp = File(dir, "$name.part")
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw DownloadException("HTTP ${resp.code}")
            val body = resp.body ?: throw DownloadException("empty response")
            tmp.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        return file
    }

    private companion object {
        const val POLL_MS = 200L
    }
}
