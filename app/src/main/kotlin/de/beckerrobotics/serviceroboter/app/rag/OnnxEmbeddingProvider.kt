package de.beckerrobotics.serviceroboter.app.rag

import android.content.Context
import ai.onnxruntime.*
import de.beckerrobotics.serviceroboter.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

class OnnxEmbeddingProvider(private val context: Context) : EmbeddingProvider {
    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var encode: ((String) -> LongArray)? = null
    @Volatile var modelName: String = "Stichwortsuche"
        private set
    override val isAvailable: Boolean get() = session != null

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            val german = context.assets.list("embeddings").orEmpty().contains("model.onnx")
            val asset = if (german) "embeddings/model.onnx" else "all-MiniLM-L6-v2.onnx"
            val filename = if (german) "jina-de-v2-3f9eede-int8.onnx" else "minilm-l6-v2.onnx"
            val model = File(context.filesDir, filename)
            if (!model.isFile) {
                val temporary = File(model.parentFile, filename + ".partial")
                context.assets.open(asset).use { input -> temporary.outputStream().use { input.copyTo(it) } }
                check(temporary.renameTo(model)) { "Suchmodell konnte nicht gespeichert werden." }
            }
            if (german) {
                val vocabJson = JSONObject(context.assets.open("embeddings/vocab.json").bufferedReader().use { it.readText() })
                val vocab = vocabJson.keys().asSequence().associateWith { vocabJson.getLong(it) }
                val merges = context.assets.open("embeddings/merges.txt").bufferedReader().useLines { lines ->
                    lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
                        val parts = line.split(' ')
                        require(parts.size == 2)
                        parts[0] to parts[1]
                    }.toList()
                }
                val tokenizer = ByteBpeTokenizer(vocab, merges)
                encode = { tokenizer.encode(it, 256) }
            } else {
                val vocab = context.assets.open("vocab.txt").bufferedReader().useLines {
                    it.withIndex().associate { line -> line.value to line.index.toLong() }
                }
                val tokenizer = WordPieceTokenizer(vocab)
                encode = { tokenizer.encode(it, 256) }
            }
            val options = OrtSession.SessionOptions()
            try {
                options.setIntraOpNumThreads(2)
                session = env.createSession(model.absolutePath, options)
            } finally { options.close() }
            modelName = if (german) "Deutsche Dokumentensuche" else "MiniLM-Dokumentensuche"
        } catch (_: Exception) {
            session?.close(); session = null; encode = null
            modelName = "Stichwortsuche (Suchmodell konnte nicht geladen werden)"
        }
    }

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        val active = session ?: return@withContext FloatArray(0)
        val ids = encode?.invoke(text) ?: return@withContext FloatArray(0)
        val mask = LongArray(ids.size) { 1L }
        val tensors = mutableMapOf<String, OnnxTensor>()
        try {
            val shape = longArrayOf(1, ids.size.toLong())
            tensors["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
            if ("attention_mask" in active.inputNames)
                tensors["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)
            if ("token_type_ids" in active.inputNames)
                tensors["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size)), shape)
            active.run(tensors).use { results ->
                val value = results.get("sentence_embedding").orElseGet { results[0] }
                val dimensions = (value.info as TensorInfo).shape.size
                @Suppress("UNCHECKED_CAST")
                val pooled = if (dimensions == 2) (value.value as Array<FloatArray>)[0].copyOf()
                    else {
                        val tokens = (value.value as Array<Array<FloatArray>>)[0]
                        val mean = FloatArray(tokens[0].size)
                        tokens.forEach { token -> for (d in mean.indices) mean[d] += token[d] / tokens.size }
                        mean
                    }
                val norm = sqrt(pooled.sumOf { it.toDouble() * it.toDouble() }).toFloat()
                if (norm > 0 && norm.isFinite()) for (d in pooled.indices) pooled[d] /= norm
                pooled
            }
        } catch (_: Exception) { FloatArray(0) }
        finally { tensors.values.forEach { it.close() } }
    }
}
