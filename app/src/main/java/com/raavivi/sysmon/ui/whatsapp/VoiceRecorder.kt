package com.raavivi.sysmon.ui.whatsapp

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Records an AAC/m4a voice note into `cacheDir/wa-rec/<timestamp>.m4a`. [recording] stays true
 * from [start] until [finish] or [cancel]; at [MAX_MS] the recorder stops by itself but keeps the
 * file ([capped]), so the user can still send it.
 */
class VoiceRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onMessage: (String) -> Unit,
) {
    var recording by mutableStateOf(false)
        private set
    var capped by mutableStateOf(false)
        private set
    var elapsedMs by mutableLongStateOf(0L)
        private set

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var tickJob: Job? = null

    /** Begin recording; false (with a message) if the recorder could not start. */
    fun start(): Boolean {
        if (recording) return true
        val dir = File(context.cacheDir, "wa-rec").apply { mkdirs() }
        val out = File(dir, "${System.currentTimeMillis()}.m4a")
        val rec = newRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(SAMPLE_RATE)
            rec.setAudioChannels(1)
            rec.setAudioEncodingBitRate(BIT_RATE)
            rec.setOutputFile(out.absolutePath)
            rec.prepare()
            rec.start()
        } catch (e: Exception) {
            runCatching { rec.release() }
            out.delete()
            onMessage("Can't start recording: ${e.message ?: "recorder error"}")
            return false
        }
        recorder = rec
        file = out
        startedAt = SystemClock.elapsedRealtime()
        elapsedMs = 0
        capped = false
        recording = true
        tickJob = scope.launch {
            while (isActive) {
                elapsedMs = SystemClock.elapsedRealtime() - startedAt
                if (elapsedMs >= MAX_MS) {
                    elapsedMs = MAX_MS
                    capped = true
                    stopRecorder()
                    onMessage("Reached the 15 minute limit")
                    break
                }
                delay(TICK_MS)
            }
        }
        return true
    }

    /** Stop and return the recording, or null (file discarded) if it was too short or failed. */
    fun finish(): File? {
        if (!recording) return null
        val out = file
        val ok = if (capped) true else stopRecorder()
        val long = elapsedMs >= MIN_MS
        reset()
        if (out == null || !ok || !long) {
            out?.delete()
            onMessage("Hold on, that was too short")
            return null
        }
        return out
    }

    /** Discard the recording (if any) and free the recorder; safe to call repeatedly. */
    fun cancel() {
        if (!recording && recorder == null) return
        if (!capped) stopRecorder()
        file?.delete()
        reset()
    }

    /** Stops the MediaRecorder, which throws on near-empty recordings; true if it stopped cleanly. */
    private fun stopRecorder(): Boolean {
        val rec = recorder ?: return false
        recorder = null
        tickJob?.cancel()
        if (!capped) elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val ok = try {
            rec.stop()
            true
        } catch (e: RuntimeException) {
            false
        }
        runCatching { rec.release() }
        return ok
    }

    private fun reset() {
        tickJob?.cancel()
        tickJob = null
        recorder = null
        file = null
        recording = false
        capped = false
        elapsedMs = 0
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    companion object {
        const val MAX_MS = 15L * 60 * 1000
        const val MIN_MS = 500L
        private const val TICK_MS = 200L
        private const val SAMPLE_RATE = 44_100
        private const val BIT_RATE = 64_000
    }
}
