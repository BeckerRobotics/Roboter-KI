package de.beckerrobotics.serviceroboter.app.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Einfache, zeitbasierte Audioaufnahme (kein Voice-Activity-Detection/Silence-Stop) als
 * Startpunkt für den Prototyp. Für ein natürlicheres "Roboter hört zu, bis ich fertig bin"
 * sollte das später gegen eine VAD-gestützte Aufnahme (z. B. auf Basis der Vosk-eigenen
 * Streaming-Erkennung oder eines separaten VAD wie WebRTC-VAD/Silero-VAD) getauscht werden.
 *
 * Benötigt die Berechtigung android.permission.RECORD_AUDIO (siehe AndroidManifest.xml) –
 * die Abfrage der Laufzeit-Berechtigung erfolgt in [de.beckerrobotics.serviceroboter.app.MainActivity].
 */
class AudioCapture(private val sampleRate: Int = 16000) {

    @SuppressLint("MissingPermission") // Berechtigungsprüfung erfolgt vor dem Aufruf in der UI-Schicht.
    suspend fun recordFixedDuration(durationMs: Long = 4000): ShortArray = withContext(Dispatchers.IO) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, sampleRate * 2) // mind. 1 Sekunde Puffer

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        val totalSamples = (sampleRate * (durationMs / 1000.0)).toInt()
        val output = ShortArray(totalSamples)

        try {
            audioRecord.startRecording()
            var offset = 0
            while (offset < totalSamples) {
                val read = audioRecord.read(output, offset, totalSamples - offset)
                if (read <= 0) break
                offset += read
            }
        } finally {
            audioRecord.stop()
            audioRecord.release()
        }

        output
    }
}
