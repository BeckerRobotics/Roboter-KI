package de.beckerrobotics.serviceroboter.app.rag

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import de.beckerrobotics.serviceroboter.core.InMemoryVectorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Private local document storage; PDF page references survive extraction and retrieval. */
class PdfIngestor(private val context: Context) {
    val documentsDir = File(context.filesDir, "memos").apply { mkdirs() }
    init { PDFBoxResourceLoader.init(context.applicationContext) }

    suspend fun importDocument(uri: Uri): String = withContext(Dispatchers.IO) {
        val originalName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Dokument.pdf"
        val safeName = originalName.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(120)
        require(safeName.substringAfterLast('.').lowercase() in setOf("pdf", "txt", "md")) {
            "Bitte eine PDF-, TXT- oder Markdown-Datei auswählen."
        }
        var destination = File(documentsDir, safeName)
        if (destination.exists()) destination = File(documentsDir, "${System.currentTimeMillis()}_$safeName")
        val temporary = File(documentsDir, destination.name + ".partial")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= 50L * 1024 * 1024) { "Das Dokument ist größer als 50 MB." }
                        output.write(buffer, 0, n)
                    }
                }
            } ?: error("Datei konnte nicht geöffnet werden.")
            val pages = extract(temporary, destination.extension)
            require(pages.any { it.second.isNotBlank() }) {
                "Diese PDF enthält keinen lesbaren Text. Bitte zuerst eine Texterkennung (OCR) durchführen."
            }
            check(temporary.renameTo(destination)) { "Dokument konnte nicht gespeichert werden." }
            destination.name
        } finally { temporary.delete() }
    }

    suspend fun saveMemo(title: String, text: String): String = withContext(Dispatchers.IO) {
        require(text.isNotBlank() && text.length <= 100_000)
        val safeTitle = title.ifBlank { "Memo" }.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").take(80)
        val file = File(documentsDir, "${safeTitle}_${System.currentTimeMillis()}.txt")
        file.writeText(text, Charsets.UTF_8)
        file.name
    }

    suspend fun indexAllDocuments(store: InMemoryVectorStore): List<String> = withContext(Dispatchers.IO) {
        val warnings = mutableListOf<String>()
        documentsDir.listFiles().orEmpty().filter { it.extension.lowercase() in setOf("pdf", "txt", "md") }
            .sortedBy { it.name }.forEach { file ->
                try {
                    val pages = extract(file, file.extension)
                    if (pages.none { it.second.isNotBlank() }) warnings.add("${file.name}: kein lesbarer Text (OCR erforderlich).")
                    else store.indexPages(file.name, pages)
                } catch (_: Exception) { warnings.add("${file.name}: konnte nicht gelesen werden.") }
            }
        warnings
    }
    private fun extract(file: File, extension: String): List<Pair<Int?, String>> =
        if (extension.equals("pdf", true)) PDDocument.load(file).use { pdf ->
            require(pdf.numberOfPages <= 500) { "Bitte PDFs mit höchstens 500 Seiten verwenden." }
            (1..pdf.numberOfPages).map { page ->
                val stripper = PDFTextStripper().apply { startPage = page; endPage = page; sortByPosition = true }
                page to stripper.getText(pdf)
            }
        } else listOf(null to file.readText(Charsets.UTF_8))
}
