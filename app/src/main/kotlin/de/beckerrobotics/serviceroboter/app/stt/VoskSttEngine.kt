package de.beckerrobotics.serviceroboter.app.stt

import android.content.Context
import de.beckerrobotics.serviceroboter.core.SpeechToTextEngine
import de.beckerrobotics.serviceroboter.core.TranscriptionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

class VoskSttEngine(private val context: Context) : SpeechToTextEngine {
    @Volatile private var model: Model? = null
    override val isReady: Boolean get() = model != null
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isReady) return@withContext true
        val roots = listOfNotNull(context.filesDir, context.getExternalFilesDir(null))
        var actual = roots.firstNotNullOfOrNull { findModel(it, 3) }
        if (actual == null && context.assets.list("vosk-model-de").orEmpty().isNotEmpty()) {
            val target = File(context.filesDir, "vosk-model-de")
            copyAssets("vosk-model-de", target)
            actual = target
        }
        if (actual == null) return@withContext false
        try { model = Model(actual.absolutePath); true }
        catch (_: Exception) { false }
    }
    private fun findModel(dir: File, depth: Int): File? {
        if (File(dir, "am/final.mdl").isFile && File(dir, "conf").isDirectory) return dir
        if (depth <= 0) return null
        return dir.listFiles().orEmpty().filter { it.isDirectory }.firstNotNullOfOrNull { findModel(it, depth - 1) }
    }
    private fun copyAssets(assetPath: String, destination: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> destination.outputStream().use { input.copyTo(it) } }
        } else {
            destination.mkdirs()
            children.forEach { copyAssets("$assetPath/$it", File(destination, it)) }
        }
    }
    override suspend fun transcribe(audioSamples: ShortArray, sampleRate: Int): TranscriptionResult =
        withContext(Dispatchers.Default) {
            val active = model ?: return@withContext TranscriptionResult("", 0f)
            Recognizer(active, sampleRate.toFloat()).use { recognizer ->
                recognizer.setWords(true)
                val texts = mutableListOf<String>()
                val confidences = mutableListOf<Float>()
                fun collect(json: String) {
                    val result = JSONObject(json)
                    result.optString("text").trim().takeIf { it.isNotBlank() }?.let { texts.add(it) }
                    val words = result.optJSONArray("result")
                    if (words != null) for (i in 0 until words.length())
                        confidences.add(words.getJSONObject(i).optDouble("conf", 0.0).toFloat())
                }
                for (start in audioSamples.indices step 4000) {
                    ensureActive()
                    val size = minOf(4000, audioSamples.size - start)
                    val bytes = ByteArray(size * 2)
                    for (i in 0 until size) {
                        val sample = audioSamples[start + i].toInt()
                        bytes[2 * i] = (sample and 255).toByte()
                        bytes[2 * i + 1] = (sample shr 8).toByte()
                    }
                    if (recognizer.acceptWaveForm(bytes, bytes.size)) collect(recognizer.result)
                }
                collect(recognizer.finalResult)
                TranscriptionResult(texts.joinToString(" "),
                    if (confidences.isEmpty()) 0f else confidences.average().toFloat())
            }
        }
}
