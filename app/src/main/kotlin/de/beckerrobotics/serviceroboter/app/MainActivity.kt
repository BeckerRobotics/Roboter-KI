package de.beckerrobotics.serviceroboter.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras

/**
 * Bewusst schlanke Test-UI für den Prototyp: Mikrofon-Aufnahme ODER Texteingabe (letzteres
 * praktisch zum Testen der Pipeline-Logik ohne Vosk-Modell/Mikrofonberechtigung), Anzeige der
 * Antwort inkl. verwendeter Stufe (Transparenz, siehe Recherche-Dokument Abschnitt 6).
 *
 * Kein Anspruch auf ein fertiges, seniorengerechtes UI-Design – das ist bewusst Sache der
 * eigentlichen Lernanwendungs-Oberfläche und nicht dieses technischen Prototyps.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                @Suppress("UNCHECKED_CAST")
                return MainViewModel(application as ServiceRoboterApplication) as T
            }
        }
    }

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Ergebnis wird beim nächsten Tastendruck erneut geprüft. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        viewModel = viewModel,
                        onRequestMic = { requestMicPermission.launch(Manifest.permission.RECORD_AUDIO) },
                        hasMicPermission = {
                            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MainScreen(
    viewModel: MainViewModel,
    onRequestMic: () -> Unit,
    hasMicPermission: () -> Boolean
) {
    val state by viewModel.uiState.collectAsState()
    var typedText by remember { mutableStateOf("") }

    val pdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importPdf(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text("Serviceroboter – KI-Prototyp", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        // Dokumenten-Bereich
        Text("Wissensbasis (PDFs & Memos)", style = MaterialTheme.typography.titleMedium)
        Text("Dokumente geladen: ${state.documentCount}", style = MaterialTheme.typography.bodySmall)
        Text(
            text = if (state.isSmartSearchActive) "✅ Smarte Suche aktiv" else "⚠️ Einfache Suche (Modell fehlt)",
            style = MaterialTheme.typography.labelSmall,
            color = if (state.isSmartSearchActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Button(
            onClick = { pdfLauncher.launch("application/pdf") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Text("📄 PDF hochladen")
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Button(
            onClick = {
                if (hasMicPermission()) viewModel.startListening() else onRequestMic()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.isListening) "Höre zu … (4 Sek.)" else "🎤 Sprich mit dem Roboter")
        }

        Spacer(Modifier.height(16.dp))
        Text("…oder zum Testen tippen:", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = typedText,
            onValueChange = { typedText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Was möchtest du fragen?") }
        )
        Button(
            onClick = {
                viewModel.submitTypedText(typedText)
                typedText = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Senden")
        }

        Spacer(Modifier.height(24.dp))

        if (state.transcript.isNotBlank()) {
            Text("Verstanden: \"${state.transcript}\"", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }

        state.currentStage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }

        if (state.answer.isNotBlank()) {
            Text("Antwort:", style = MaterialTheme.typography.labelLarge)
            Text(state.answer, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            state.answerSource?.let {
                Text("(Quelle: $it)", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
