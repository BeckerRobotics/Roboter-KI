package de.beckerrobotics.serviceroboter.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import de.beckerrobotics.serviceroboter.core.GenerationResult
import de.beckerrobotics.serviceroboter.core.OnlineFallbackClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Letzte Stufe der Pipeline (siehe Recherche-Dokument, Abschnitt 5). Wird von
 * [de.beckerrobotics.serviceroboter.core.ServiceRoboterPipeline] nur aufgerufen, wenn:
 *  - keine vorherige Stufe eine ausreichend sichere Antwort liefern konnte,
 *  - [de.beckerrobotics.serviceroboter.core.DefaultPrivacyFilter] die Anfrage NICHT blockiert hat,
 *  - und [isNetworkAvailable] true ist.
 *
 * WICHTIG: [endpointUrl]/[apiKey] sind bewusst nicht vorbelegt – hier muss ein konkreter Dienst
 * (z. B. eine Web-Such-API oder ein Cloud-LLM-Endpunkt) eingetragen werden. Da Becker Robotics
 * hierzu noch keine Festlegung getroffen hat, ist diese Klasse ein funktionsfähiges Gerüst
 * (HTTP-Get mit Query-Parameter), kein fertig angebundener Dienst.
 */
class SimpleOnlineFallbackClient(
    private val context: Context,
    private val endpointUrl: String? = null,
    private val apiKey: String? = null,
    private val httpClient: OkHttpClient = OkHttpClient()
) : OnlineFallbackClient {

    override val isNetworkAvailable: Boolean
        get() {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            // Weniger restriktiv: nur INTERNET-Capability prüfen
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    override suspend fun search(query: String): GenerationResult? = withContext(Dispatchers.IO) {
        val key = apiKey ?: return@withContext null
        // Standardmäßig Gemini API, falls keine URL gesetzt
        val url = if (endpointUrl.isNullOrBlank()) {
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$key"
        } else {
            endpointUrl
        }

        // Einfaches JSON-Escaping für Anführungszeichen
        val escapedQuery = query.replace("\"", "\\\"").replace("\n", "\\n")
        val json = """
            {
              "contents": [{
                "parts":[{"text": "$escapedQuery"}]
              }]
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                android.util.Log.d("GeminiClient", "Response Code: ${response.code}")
                
                if (!response.isSuccessful) {
                    android.util.Log.e("GeminiClient", "Error: $body")
                    return@withContext null
                }
                
                // Extrahiere Text aus Gemini-Response {"candidates": [{"content": {"parts": [{"text": "..."}]}}]}
                val textRegex = Regex("\"text\"\\s*:\\s*\"([^\"]*)\"")
                val text = textRegex.find(body)?.groupValues?.get(1)
                    ?.replace("\\n", "\n")?.replace("\\\"", "\"")
                
                if (text.isNullOrBlank()) {
                    android.util.Log.w("GeminiClient", "Kein Text in Response gefunden: $body")
                    null
                } else {
                    GenerationResult(text = text, confidence = 0.9f)
                }
            }
        }.onFailure {
            android.util.Log.e("GeminiClient", "Netzwerkfehler: ${it.message}", it)
        }.getOrNull()
    }
}
