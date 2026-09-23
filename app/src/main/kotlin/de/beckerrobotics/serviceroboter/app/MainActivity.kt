package de.beckerrobotics.serviceroboter.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import de.beckerrobotics.serviceroboter.core.AnswerSource

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(application as ServiceRoboterApplication) as T
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF235A55), background = Color(0xFFF7F5F0),
                surface = Color(0xFFFFFFFF), onSurface = Color(0xFF172E30)
            )) {
                Surface(modifier = Modifier.fillMaxSize()) { RobotScreen(model) }
            }
        }
    }
}

@Composable
private fun RobotScreen(model: MainViewModel) {
    val state by model.uiState.collectAsState()
    var question by remember { mutableStateOf("") }
    var manage by remember { mutableStateOf(false) }
    var memoTitle by remember { mutableStateOf("") }
    var memoText by remember { mutableStateOf("") }
    var online by remember(state.onlineEnabled) { mutableStateOf(state.onlineEnabled) }
    var endpoint by remember(state.searchEndpoint) { mutableStateOf(state.searchEndpoint) }
    var rate by remember(state.speechRate) { mutableStateOf(state.speechRate) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) model.importDocument(it)
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) model.importModel(it)
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) model.listen()
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val linkHandler = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Schön, dass Sie da sind.", fontSize = 30.sp, lineHeight = 38.sp)
        Text("Was möchten Sie wissen?", fontSize = 22.sp)
        Button(onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                model.listen() else mic.launch(Manifest.permission.RECORD_AUDIO)
        }, enabled = state.ready && !state.busy && state.sttReady,
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
            Text(if (state.listening) "Ich höre zu …" else "Frage stellen", fontSize = 24.sp)
        }
        if (state.stage.isNotBlank()) {
            if (state.busy || !state.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.stage, fontSize = 20.sp)
        }
        OutlinedTextField(value = question, onValueChange = { question = it.take(2000) },
            modifier = Modifier.fillMaxWidth(), label = { Text("Frage eingeben", fontSize = 18.sp) },
            textStyle = LocalTextStyle.current.copy(fontSize = 22.sp), enabled = !state.busy)
        Button(onClick = { model.submit(question); question = "" },
            enabled = state.ready && !state.busy && question.isNotBlank(), modifier = Modifier.heightIn(min = 56.dp)) {
            Text("Antwort erhalten", fontSize = 20.sp)
        }
        if (state.transcript.isNotBlank()) Text("Ihre Frage: ${state.transcript}", fontSize = 18.sp)
        if (state.answer.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.answer, fontSize = 25.sp, lineHeight = 35.sp)
                    Text(when (state.source) {
                        AnswerSource.KNOWLEDGE_BASE -> "Aus Ihren Dokumenten"
                        AnswerSource.OFFLINE_LLM -> "Antwort der lokalen KI"
                        AnswerSource.ONLINE_FALLBACK -> "Im Internet gefunden"
                        else -> ""
                    }, fontSize = 16.sp)
                    state.citations.forEach { source ->
                        val url = source.substringAfterLast('\n', "")
                        if (url.startsWith("https://")) TextButton(onClick = { linkHandler.openUri(url) }) {
                            Text(source.substringBefore('\n'), fontSize = 17.sp)
                        } else Text(source, fontSize = 16.sp)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = model::repeat, enabled = state.answer.isNotBlank() && !state.busy,
                modifier = Modifier.weight(1f).heightIn(min = 60.dp)) { Text("Noch einmal", fontSize = 20.sp) }
            Button(onClick = model::stop, modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E3636))) {
                Text("Stopp", fontSize = 22.sp)
            }
        }
        if (state.message.isNotBlank()) Text(state.message, fontSize = 18.sp)
        HorizontalDivider()
        TextButton(onClick = { manage = !manage }) { Text(if (manage) "Verwaltung schließen" else "Dokumente & Einstellungen", fontSize = 18.sp) }
        if (manage) {
            Text("Verwaltung", fontSize = 26.sp)
            Text("${state.documentCount} Dokumente geladen\n${state.searchStatus}\n${state.llmStatus}\n${state.speechStatus}", fontSize = 17.sp)
            Button(onClick = { documentPicker.launch(arrayOf("application/pdf", "text/plain", "text/markdown")) },
                enabled = state.ready && !state.busy) { Text("PDF oder Textdatei hinzufügen") }
            state.documentNames.forEach { name ->
                Row(Modifier.fillMaxWidth()) {
                    Text(name, modifier = Modifier.weight(1f), fontSize = 16.sp)
                    TextButton(onClick = { pendingDelete = name }, enabled = !state.busy) { Text("Löschen") }
                }
            }
            OutlinedTextField(value = memoTitle, onValueChange = { memoTitle = it },
                label = { Text("Titel des Memos") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = memoText, onValueChange = { memoText = it },
                label = { Text("Memo") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            Button(onClick = { model.saveMemo(memoTitle, memoText); memoTitle = ""; memoText = "" },
                enabled = state.ready && !state.busy && memoText.isNotBlank()) { Text("Memo speichern") }
            HorizontalDivider()
            Text("Stimme", fontSize = 22.sp)
            Text("Sprechtempo", fontSize = 18.sp)
            Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.7f..1.15f)
            Button(onClick = model::refreshVoice, enabled = !state.busy) { Text("Installierte Stimme neu laden") }
            Text("Internet", fontSize = 22.sp)
            Row {
                Text("Online nachschlagen erlauben", Modifier.weight(1f), fontSize = 18.sp)
                Switch(checked = online, onCheckedChange = { online = it })
            }
            OutlinedTextField(value = endpoint, onValueChange = { endpoint = it },
                label = { Text("Eigene Suchadresse (optional)") }, modifier = Modifier.fillMaxWidth())
            Text("Ohne eigene Suchadresse wird die deutsche Wikipedia durchsucht. Für eine allgemeine Websuche hier eine HTTPS-Adresse einer SearXNG-Suche mit JSON-Unterstützung eintragen.", fontSize = 16.sp)
            Button(onClick = { model.saveSettings(online, endpoint, rate) }, enabled = !state.busy) { Text("Einstellungen speichern") }
            HorizontalDivider()
            Button(onClick = { modelPicker.launch(arrayOf("*/*")) }, enabled = state.ready && !state.busy) {
                Text("Lokales Sprachmodell importieren (.gguf)")
            }
        }
    }
    pendingDelete?.let { name ->
        AlertDialog(onDismissRequest = { pendingDelete = null },
            title = { Text("Dokument löschen?") }, text = { Text(name) },
            confirmButton = { TextButton(onClick = { model.deleteDocument(name); pendingDelete = null }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Behalten") } })
    }
}
