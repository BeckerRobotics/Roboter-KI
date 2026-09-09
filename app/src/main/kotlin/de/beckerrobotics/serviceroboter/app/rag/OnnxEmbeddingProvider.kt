package de.beckerrobotics.serviceroboter.app.rag

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import de.beckerrobotics.serviceroboter.core.EmbeddingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.LongBuffer

/**
 * Erzeugt semantische Embedding-Vektoren lokal auf dem Gerät über ein ONNX-exportiertes
 * Sentence-Embedding-Modell (Referenz-Setup: all-MiniLM-L6-v2, siehe Recherche-Dokument,
 * Abschnitt 4, analog zu github.com/shubham0204/Android-Document-QA).
 *
 * WICHTIG – vor dem ersten Start zu erledigen (siehe README.md):
 *  1. all-MiniLM-L6-v2 als ONNX-Modell besorgen/exportieren (z. B. via `optimum-cli export onnx`)
 *     und als `assets/all-MiniLM-L6-v2.onnx` ins Projekt legen.
 *  2. Eine passende WordPiece-Vokabeldatei (`vocab.txt`) ebenfalls unter `assets/` ablegen.
 *
 * Die Tokenisierung hier ist bewusst eine vereinfachte WordPiece-Variante als Startpunkt –
 * für produktionsreife Ergebnisse empfiehlt sich der Abgleich mit einer vollständigen
 * BERT/WordPiece-Tokenizer-Implementierung (z. B. über eine schlanke Tokenizer-Bibliothek).
 */
class OnnxEmbeddingProvider(
    private val context: Context,
    private val modelAssetPath: String = "all-MiniLM-L6-v2.onnx",
    private val vocabAssetPath: String = "vocab.txt",
    private val maxSequenceLength: Int = 128
) : EmbeddingProvider {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private var vocab: Map<String, Long>? = null

    override val isAvailable: Boolean
        get() = session != null && vocab != null

    private suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        if (session == null || vocab == null) {
            runCatching {
                if (session == null) {
                    val modelBytes = context.assets.open(modelAssetPath).use { it.readBytes() }
                    session = env.createSession(modelBytes, OrtSession.SessionOptions())
                }
                if (vocab == null) {
                    vocab = context.assets.open(vocabAssetPath).bufferedReader().useLines { lines ->
                        lines.withIndex().associate { (index, token) -> token to index.toLong() }
                    }
                }
            }.onFailure {
                android.util.Log.e("OnnxEmbeddingProvider", "Fehler beim Laden von ONNX-Assets: ${it.message}")
            }
        }
    }

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        ensureLoaded()
        val currentSession = session
        val currentVocab = vocab

        if (currentSession == null || currentVocab == null) {
            // Fallback: Wenn das Modell fehlt, geben wir einen leeren Vektor zurück statt abzustürzen.
            // Die Wissensbasis wird so keine Treffer finden, aber die App bleibt bedienbar.
            return@withContext FloatArray(0)
        }

        val tokenIds = tokenize(text, currentVocab)
        val inputIds = LongArray(maxSequenceLength)
        val attentionMask = LongArray(maxSequenceLength)
        tokenIds.forEachIndexed { i, id ->
            if (i < maxSequenceLength) {
                inputIds[i] = id
                attentionMask[i] = 1L
            }
        }

        OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1, maxSequenceLength.toLong())).use { inputTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), longArrayOf(1, maxSequenceLength.toLong())).use { maskTensor ->
                val inputs = mapOf(
                    "input_ids" to inputTensor,
                    "attention_mask" to maskTensor
                )
                currentSession.run(inputs).use { results ->
                    @Suppress("UNCHECKED_CAST")
                    val tokenEmbeddings = (results[0].value as Array<Array<FloatArray>>)[0] // [seqLen][hiddenDim]
                    meanPooling(tokenEmbeddings, attentionMask)
                }
            }
        }
    }

    /** Mean-Pooling über alle nicht-maskierten Token-Embeddings (Standardvorgehen bei Sentence-Transformers). */
    private fun meanPooling(tokenEmbeddings: Array<FloatArray>, attentionMask: LongArray): FloatArray {
        val hiddenDim = tokenEmbeddings.first().size
        val pooled = FloatArray(hiddenDim)
        var validTokens = 0
        tokenEmbeddings.forEachIndexed { i, vector ->
            if (attentionMask.getOrElse(i) { 0L } == 1L) {
                validTokens++
                for (d in 0 until hiddenDim) {
                    pooled[d] = pooled[d] + vector[d]
                }
            }
        }
        if (validTokens > 0) {
            for (d in 0 until hiddenDim) {
                pooled[d] = pooled[d] / validTokens
            }
        }
        return pooled
    }

    /**
     * Sehr einfache Tokenisierung (Kleinschreibung + Whitespace-Split + Vokabular-Lookup mit
     * Fallback auf [UNK]). Deckt keine vollständige WordPiece-Subword-Segmentierung ab – als
     * Startpunkt für den Prototyp bewusst pragmatisch gehalten (siehe Klassen-Kommentar oben).
     */
    private fun tokenize(text: String, vocab: Map<String, Long>): List<Long> {
        val clsId = vocab["[CLS]"] ?: 101L
        val sepId = vocab["[SEP]"] ?: 102L
        val unkId = vocab["[UNK]"] ?: 100L

        val words = text.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }
        val ids = mutableListOf(clsId)
        words.forEach { word -> ids.add(vocab[word] ?: unkId) }
        ids.add(sepId)
        return ids
    }
}
