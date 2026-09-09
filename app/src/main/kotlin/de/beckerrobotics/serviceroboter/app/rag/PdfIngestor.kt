package de.beckerrobotics.serviceroboter.app.rag

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import de.beckerrobotics.serviceroboter.core.InMemoryVectorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Baut die lokale Wissensbasis (höchste Priorität lt. Anforderung, siehe Recherche-Dokument
 * Abschnitt 4) aus PDF-Dateien und einfachen Text-Memos auf.
 *
 * Für den Prototyp bewusst einfach gehalten: alle Dateien in [documentsDir] werden bei jedem
 * App-Start neu indexiert (kein persistenter Embedding-Cache). Das ist für die überschaubare
 * Dokumentenmenge einer Lernanwendung unkritisch; bei deutlich größeren Dokumentmengen empfiehlt
 * sich ein persistenter Vektor-Index (z. B. ObjectBox oder Zvec, siehe Recherche-Dokument).
 *
 * [documentsDir] könnte z. B. ein per Betreuungspersonal befüllter Ordner sein
 * (`context.getExternalFilesDir("memos")`) – die konkrete Zuführung der PDFs/Memos
 * (Datei-Picker, Verwaltungs-UI, o. ä.) ist bewusst nicht Teil dieses Prototyps.
 */
class PdfIngestor(
    private val context: Context,
    private val documentsDir: File = File(context.getExternalFilesDir(null), "memos")
) {

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    suspend fun indexAllDocuments(vectorStore: InMemoryVectorStore) = withContext(Dispatchers.IO) {
        if (!documentsDir.exists()) {
            documentsDir.mkdirs()
            return@withContext
        }

        documentsDir.listFiles()?.forEach { file ->
            android.util.Log.d("PdfIngestor", "Verarbeite Datei: ${file.name}")
            val text = when (file.extension.lowercase()) {
                "pdf" -> extractPdfText(file)
                "txt", "md" -> file.readText()
                else -> null
            }
            if (!text.isNullOrBlank()) {
                android.util.Log.d("PdfIngestor", "Datei indexiert: ${file.name} (${text.length} Zeichen)")
                vectorStore.indexDocument(sourceId = file.name, fullText = text)
            } else {
                android.util.Log.w("PdfIngestor", "Konnte keinen Text aus ${file.name} extrahieren.")
            }
        }
    }

    private fun extractPdfText(file: File): String? = runCatching {
        PDDocument.load(file).use { document ->
            PDFTextStripper().getText(document)
        }
    }.onFailure {
        android.util.Log.e("PdfIngestor", "Fehler beim Lesen der PDF ${file.name}: ${it.message}", it)
    }.getOrNull()
}
