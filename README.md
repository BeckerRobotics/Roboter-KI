# Serviceroboter KI – überarbeitete Testversion

Stand: 15.09.2026. Grundlage: dein Projektarchiv vom 15.09.2026.

## Was jetzt enthalten ist

- **Memos/PDFs zuerst:** lokale Suche mit Jina Embeddings v2 Deutsch und ergänzender Stichwortsuche.
- **Echte Offline-KI:** Qwen3-4B-Instruct-2507, Q4_K_M, über die direkt in die Android-App eingebaute llama.cpp-Laufzeit.
- **Internet zuletzt:** deutsche Wikipedia ohne API-Schlüssel; allgemeine Websuche über einen konfigurierbaren SearXNG-HTTPS-Endpunkt.
- **Deutsche Offline-Stimme:** Thorsten über das separate sherpa-onnx-Sprachpaket.
- PDF-/TXT-/Markdown-Import, Memo-Eingabe, Löschen, Quellen und PDF-Seitenzahlen.
- Große Bedienelemente, verstellbares Sprechtempo, Wiederholen und Stopp.
- Spracheingabe mit Pausenerkennung: bis zu 25 Sekunden Aufnahme, etwa 1,8 Sekunden Sprechpause erlaubt.
- Korrekte WordPiece- und Byte-BPE-Tokenizer. Die bisherige vocab.txt wird nicht wahllos erweitert.
- Der fest eingetragene Google-API-Schlüssel und die irrtümliche Gemini-Nano-Anbindung wurden entfernt.

## Installation auf dem Roboter

Voraussetzung für das mitgelieferte Paket: **Android 7.0 oder neuer, 64-Bit-ARM-System (arm64-v8a)**.
Die von dir genannten 8 GB RAM sind eine sinnvolle Ausgangsbasis; Rechenleistung und dauerhaft verfügbarer RAM sind zusätzlich entscheidend.
Für APKs, Modellimport und die vorübergehende doppelte GGUF-Datei etwa **6 GB freien Gerätespeicher** vorsehen.

1. **02-Thorsten-Deutsch-arm64.apk** installieren. Die App „TTS Engine: Next-gen Kaldi“ einmal öffnen und ihre Sprachausgabe prüfen.
2. **01-Serviceroboter-KI.apk** installieren und öffnen.
3. Unter **Dokumente & Einstellungen → Lokales Sprachmodell importieren** die Datei **Qwen3-4B-Instruct-2507-Q4_K_M.gguf** auswählen. Die Datei wird in den privaten App-Speicher kopiert. Auf die Meldung „Lokale KI bereit“ warten.
4. In der Verwaltung **Installierte Stimme neu laden** drücken, falls Thorsten nachträglich installiert wurde.
5. Ein PDF hinzufügen oder ein Memo schreiben, z. B.: „Das Frühstück beginnt um acht Uhr im Speisesaal.“
6. „Wann beginnt das Frühstück?“ fragen. Als Quelle muss das Dokument mit gegebenenfalls zugehöriger Seite erscheinen.
7. WLAN ausschalten und die Frage wiederholen. Dokumentensuche, lokales Sprachmodell, Spracherkennung und Thorsten benötigen nach der Installation keine Internetverbindung.

Die APK ist eine **Debug-Testversion**, noch keine für eine Flotte signierte Produktionsversion.
Wenn Android eine vorhandene Installation wegen einer abweichenden Signatur nicht aktualisiert: das neue Projekt mit dem bisherigen Signaturschlüssel bauen. Eine Deinstallation würde die bisherigen privaten App-Daten entfernen.

### Internet einrichten

Ohne eigene Suchadresse durchsucht die App **die deutsche Wikipedia**. Das ist eine Enzyklopädiesuche und deckt beispielsweise Wetter oder beliebige aktuelle Webseiten nicht zuverlässig ab.

Für die allgemeine Websuche:

- Einen eigenen oder vom Betreiber bereitgestellten SearXNG-Dienst verwenden.
- Unter „Eigene Suchadresse“ seinen HTTPS-Suchendpunkt eintragen, z. B. `https://suche.example.org/search`.
- In SearXNG muss das Ausgabeformat `json` aktiviert sein.
- Die App sendet nur die zu suchende Frage. Lokale PDF-/Memo-Auszüge werden diesem Dienst nicht mitgeschickt.
- „Online nachschlagen erlauben“ kann die Online-Stufe vollständig abschalten.

Es wird kein fremder öffentlicher Suchserver stillschweigend als Standard eingerichtet. Die Betreiberadresse ist deshalb noch einzutragen.

## Wie die Antwort entsteht

1. Gesprächssteuerung erkennt Stopp, Wiederholen und Hilfe.
2. Die lokale Wissensbasis liefert passende Abschnitte. Die lokale KI versucht, die Frage ausschließlich daraus zu beantworten.
3. Für eine als Dokumentantwort ausgewiesene Modellantwort muss mindestens ein angegebenes wörtliches Zitat tatsächlich im gefundenen Abschnitt vorkommen.
4. Fehlt dort die Antwort, versucht das lokale Sprachmodell eine allgemeine Antwort.
5. Aktuelle Informationen und vom Modell als unbekannt markierte Antworten gehen anschließend an die Online-Suche, sofern diese freigegeben ist.
6. Ohne verwendbare Antwort wird die Unsicherheit angezeigt.

Ein Suchscore ist **keine Wahrscheinlichkeit, dass eine Aussage richtig ist**. Ein passendes Zitat prüft die Herkunft, beweist aber nicht automatisch, dass die formulierte Schlussfolgerung daraus folgt.

Dokumente und Webseiten werden im Modellprompt ausdrücklich als Daten behandelt. Aufforderungen innerhalb solcher Texte dürfen den Arbeitsablauf nicht steuern. Das ist eine Schutzmaßnahme, keine Garantie gegen jede Prompt-Injection.

## Modelle und Herkunft

| Aufgabe | Komponente | Einsatz |
|---|---|---|
| Deutsch verstehen und antworten | Qwen3-4B-Instruct-2507-Q4_K_M.gguf, Quantisierung von Unsloth | auf dem Roboter |
| GGUF ausführen | llama.cpp v0.4.1, Commit b29c606e28a01b1bc8c1351026a0fa6e616bf6c4 | JNI/C++, CPU |
| Deutsche Dokumentensuche | jinaai/jina-embeddings-v2-base-de, int8 ONNX, Revision 3f9eede875721714945b6a99a3198299243cf2be | auf dem Roboter |
| Sprache erkennen | dein vosk-model-small-de-0.15 | auf dem Roboter |
| Natürliches Vorlesen | Thorsten medium mit sherpa-onnx 1.13.8 | separates Offline-Sprachpaket |

Das GGUF-Modell hat **2.497.281.120 Bytes**. SHA-256:
`3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597`.

### Primärquellen

- [Qwen3-4B-Instruct-2507](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507)
- [Verwendete GGUF-Quantisierung](https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF)
- [llama.cpp: Android](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)
- [Jina: deutsches Embedding-Modell](https://huggingface.co/jinaai/jina-embeddings-v2-base-de)
- [sherpa-onnx: Android-Sprachpakete](https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html)
- [Thorsten Voice](https://github.com/thorstenMueller/Thorsten-Voice)
- [Vosk-Modelle](https://alphacephei.com/vosk/models)
- [SearXNG Search API](https://docs.searxng.org/dev/search_api.html)

Die jeweiligen Modellkarten und Lizenzen gelten weiter. Im Quellpaket befinden sich die llama.cpp-Lizenz und die Jina-Modellkarte; die Thorsten-Modellkarte liegt zusätzlich im Prüfberichtordner.

## Bauen

- JDK 21, Android SDK 34, NDK 27.2.12479018, CMake 3.22.1.
- Gradle 8.7, Android Gradle Plugin 8.5.2, Kotlin 1.9.24.
- Projektwurzel enthält `gradlew.bat`.
- Standard: `gradlew.bat :core:test :app:assembleDebug :app:lintDebug`.
- Auf diesem Windows-Rechner: `.\scripts\build.ps1 -Lint`.
  Dieses Hilfsskript verwendet für Java-Verbindungen einen lokalen TCP-Fallback und verändert keine dauerhaften Systemeinstellungen.
- Das Build-Ergebnis liegt unter `app/build/outputs/apk/debug/app-debug.apk`.
- Der Quellcode enthält die benötigten Such- und Vosk-Modelle sowie die fixierte llama.cpp-Quelle. Das GGUF liegt separat im Installationsordner.
- Zum erneuten Bezug stehen `scripts/download-components.ps1` und `scripts/download-embeddings.ps1` bereit.

## Durchgeführte Prüfungen

Die konkreten Ergebnisse liegen im Ordner **Pruefberichte** des Installationspakets bzw. unter **validation** im Projekt:

- 44 JUnit-Tests erfolgreich; darin ein Referenzvergleich über 22 deutsche, englische und Unicode-Textfälle für beide Tokenizer.
- Deutsches ONNX-Modell real auf der CPU ausgeführt: 5 von 5 synthetischen Suchfragen ordnen den passenden von vier Abschnitten zuerst ein.
- Qwen-Modell real auf Windows-CPU ausgeführt: Dokumentantwort, fehlende Dokumentantwort, allgemeine Offline-Frage und Bedarf an aktueller Online-Information.
- Thorsten erzeugt eine lokale WAV-Hörprobe.
- Wikipedia-Suche mit einer öffentlichen Testfrage geprüft; Ergebnisse enthalten Quellenlinks.
- Android-APK erfolgreich gebaut; minimum SDK 24, Ziel-SDK 34, ABI arm64-v8a und x86_64. Das mitgelieferte Sprachpaket enthält arm64-v8a.
- Android-Lint: 0 Fehler, 9 Warnungen. Sie betreffen neuere Ziel-/Bibliotheksversionen und drei TLS-Hilfsklassen einer PDFBox-Abhängigkeit. Die Online-Suche verwendet OkHttp mit der normalen Zertifikatsprüfung; diese Hilfsklassen werden dort nicht eingesetzt. Der vollständige Bericht liegt bei.

**Diese Prüfungen ersetzen keinen Durchlauf auf dem Roboter.** Kein Android-Gerät war angeschlossen. Mikrofonqualität, Antwortzeiten, TTS-Bindung und der gesamte Ablauf auf dem Zielgerät sind deshalb noch zu prüfen.

## Bekannte praktische Grenzen

- Der PC-Test des 4B-Modells benötigte etwa 25–40 Sekunden pro Anfrage einschließlich jeweiligem Modellstart. Diese Zahlen sind keine Robotermessung. Die App hält das Modell geladen und verwendet passende Prompt-Präfixe erneut; die tatsächliche Wartezeit muss trotzdem gemessen werden.
- Die Offline-KI kann auch mit der verbesserten Technik unzutreffende oder sprachlich ungeschickte Antworten geben. Für den Einsatz bei Senioren zunächst mit Betreuungspersonal und einem festen Fragenkatalog erproben.
- Es ist kein medizinisches Fachsystem. Individuelle Diagnosen oder Dosierungen sollen nicht erzeugt werden. Der einfache Filter erkennt nicht jede Form personenbezogener Information.
- Bild-/Scan-PDFs ohne Textschicht benötigen vorher OCR. Die App meldet diesen Fall; eine integrierte OCR ist noch nicht enthalten.
- PDF-Import: bis zu 50 MB und 500 Seiten pro Datei. Dokumente werden beim Start erneut indexiert; sehr große Bestände können die Startzeit verlängern.
- Die App greift nicht automatisch auf Dokumente einer älteren App-Installation zu. Diese bitte über die Dateiauswahl erneut importieren.
- Der Roboter führt keine Bewegungen aus und stellt keine echten Erinnerungen. Er behauptet auch nicht mehr, solche noch nicht angebundenen Aktionen ausgeführt zu haben.
- Die bereitgestellte natürliche Stimme ist männlich. Über ein anderes kompatibles sherpa-Sprachpaket kann später eine andere Stimme eingesetzt werden.
- Der in deinem ursprünglichen Projekt enthaltene Google-API-Schlüssel sollte beim Anbieter widerrufen werden. Er wurde aus dieser Version entfernt und für die Tests nicht benutzt.

## Abnahme auf dem Roboter

Mit Betreuungspersonal mindestens folgende Fälle prüfen:

1. Dokumentfrage mit eindeutigem Beleg: richtige Antwort, richtiger Dateiname und richtige Seite.
2. Ähnlicher Abschnitt ohne Antwort: keine erfundene Dokumentantwort.
3. Allgemeinwissen ohne Dokumenttreffer: lokale Antwort im Flugmodus.
4. Aktuelle Frage: Online-Stufe sichtbar; bei abgeschaltetem Internet nachvollziehbare Unsicherheit.
5. Persönliche Angaben: keine unbeabsichtigte Internetübertragung.
6. Langsame und leise Sprache, längere Pausen, Hintergrundgeräusche.
7. Stopp während Aufnahme, Modellberechnung und Vorlesen.
8. Sprachtempo und Lautstärke mit den tatsächlichen Nutzerinnen und Nutzern abstimmen.
