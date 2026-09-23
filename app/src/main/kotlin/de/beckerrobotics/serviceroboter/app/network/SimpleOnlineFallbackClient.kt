package de.beckerrobotics.serviceroboter.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.Html
import de.beckerrobotics.serviceroboter.app.AppSettings
import de.beckerrobotics.serviceroboter.core.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Last stage only. Sends the question, never local document text, to a real search endpoint. */
class SimpleOnlineFallbackClient(
    private val context: Context,
    private val settings: AppSettings,
    private val localModel: OfflineLanguageModel,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).build()
) : OnlineFallbackClient {
    override val isNetworkAvailable: Boolean get() {
        if (!settings.onlineEnabled) return false
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override suspend fun search(query: String): GenerationResult? = withContext(Dispatchers.IO) {
        if (!settings.onlineEnabled) return@withContext null
        val endpoint = settings.searchEndpoint
        val sources = if (endpoint.isBlank()) wikipedia(query) else searx(query, endpoint)
        if (sources.isEmpty()) return@withContext null
        val snippets = sources.map { (source, snippet) -> KnowledgeHit(source.url, snippet, 1f) }
        if (localModel.isAvailable) {
            val answer = localModel.generate(query, snippets)
            val used = answer.evidence.flatMap { quote ->
                if (quote.trim().length < 8) emptyList() else sources.filter { (_, snippet) ->
                    normalize(snippet).contains(normalize(quote))
                }
            }.distinct()
            if (!answer.abstained && !answer.needsOnline && answer.text.isNotBlank() && used.isNotEmpty())
                return@withContext answer.copy(webSources = used.map { it.first })
        }
        // Snippets are identified as search hits, not passed off as verified generated answers.
        val first = sources.first()
        GenerationResult(
            "Ich habe online einen möglicherweise passenden Eintrag gefunden: " +
                first.first.title + ". " + first.second.take(600),
            0.6f, webSources = listOf(first.first)
        )
    }

    private suspend fun wikipedia(query: String): List<Pair<WebSource, String>> {
        val url = "https://de.wikipedia.org/w/api.php".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2").addQueryParameter("generator", "search")
            .addQueryParameter("gsrsearch", query.take(500)).addQueryParameter("gsrlimit", "3")
            .addQueryParameter("prop", "extracts|info").addQueryParameter("inprop", "url")
            .addQueryParameter("exintro", "1").addQueryParameter("explaintext", "1")
            .addQueryParameter("exsentences", "5").addQueryParameter("exlimit", "3").build()
        val json = getJson(url)
        val pages = json.optJSONObject("query")?.optJSONArray("pages") ?: return emptyList()
        return (0 until pages.length()).mapNotNull { i ->
            val item = pages.getJSONObject(i)
            val link = item.optString("fullurl").toHttpUrlOrNull()
            val extract = item.optString("extract").trim()
            if (link?.isHttps != true || extract.isBlank()) null
            else WebSource(item.optString("title"), link.toString()) to extract.take(1600)
        }
    }

    private suspend fun searx(query: String, endpoint: String): List<Pair<WebSource, String>> {
        val base = endpoint.toHttpUrlOrNull() ?: error("Ungültige Suchadresse.")
        require(base.isHttps && base.username.isEmpty() && base.password.isEmpty()) {
            "Die Suchadresse muss eine HTTPS-Adresse ohne Zugangsdaten sein."
        }
        val url = base.newBuilder().addQueryParameter("q", query.take(500))
            .addQueryParameter("format", "json").addQueryParameter("language", "de")
            .addQueryParameter("safesearch", "1").build()
        val results = getJson(url).optJSONArray("results") ?: return emptyList()
        return (0 until minOf(results.length(), 5)).mapNotNull { i ->
            val item = results.getJSONObject(i)
            val link = item.optString("url").toHttpUrlOrNull()
            val snippet = Html.fromHtml(item.optString("content"), Html.FROM_HTML_MODE_LEGACY).toString().trim()
            if (link?.isHttps != true || snippet.isBlank()) null else
                WebSource(item.optString("title"), link.toString()) to snippet.take(1600)
        }.take(3)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun getJson(url: HttpUrl): JSONObject {
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "Serviceroboter-KI/0.2 (Android; local-assistant)").build()
        val response = suspendCancellableCoroutine<Response> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { response.close() }
                }
            })
        }
        return response.use {
            check(it.isSuccessful) { "Onlinesuche ist momentan nicht erreichbar." }
            val body = it.body ?: error("Leere Suchantwort")
            require(body.contentLength() <= 2_000_000)
            val bytes = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 2_000_000)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
    }
    private fun normalize(text: String) = text.lowercase().replace(Regex("\\s+"), " ").trim()
}
