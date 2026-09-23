package de.beckerrobotics.serviceroboter.app.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/** Pauses of 1.8 seconds are allowed; recording ends at silence or after 25 seconds. */
class AudioCapture(private val sampleRate: Int = 16000) {
    @Volatile private var stopped = false
    fun stop() { stopped = true }

    @SuppressLint("MissingPermission")
    suspend fun recordUntilSilence(): ShortArray = withContext(Dispatchers.IO) {
        stopped = false
        val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Mikrofon unterstützt das Aufnahmeformat nicht." }
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, sampleRate * 2))
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { recorder.release(); "Mikrofon nicht verfügbar." }
        val output = ShortArray(sampleRate * 25)
        val buffer = ShortArray(sampleRate / 10)
        var used = 0
        var quietSamples = 0
        var speechDetected = false
        var noiseFloor = 150.0
        try {
            recorder.startRecording()
            while (!stopped && used < output.size) {
                ensureActive()
                val read = recorder.read(buffer, 0, minOf(buffer.size, output.size - used))
                check(read >= 0) { "Audioaufnahme wurde unterbrochen." }
                if (read == 0) continue
                buffer.copyInto(output, used, 0, read)
                used += read
                val rms = sqrt((0 until read).sumOf { buffer[it].toDouble() * buffer[it] } / read)
                if (used < sampleRate / 3) noiseFloor = minOf(600.0, noiseFloor * 0.8 + rms * 0.2)
                val voice = rms > maxOf(350.0, noiseFloor * 2.5)
                if (voice) { speechDetected = true; quietSamples = 0 } else quietSamples += read
                if (speechDetected && quietSamples >= sampleRate * 1.8) break
                if (!speechDetected && used >= sampleRate * 8) break
            }
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            recorder.release()
        }
        output.copyOf(used)
    }
}
