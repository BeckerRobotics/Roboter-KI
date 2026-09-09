# Technologie-Recherche: KI-gestütztes Sprachverständnis für den Serviceroboter

**Kontext:** Becker Robotics – Android-Lernanwendung für Serviceroboter (Senioren / Menschen mit Beeinträchtigung)
**Anforderung:** Roboter soll gesprochene Aussagen wie eine "echte" KI verstehen (nicht nur exakte Kommando-Strings), primär offline arbeiten, optional online nachschlagen, Priorität: 1) Memo/PDF-Wissensbasis, 2) Offline-KI, 3) Online-Suche. Datenschutz der Nutzer (Senioren, Menschen mit Beeinträchtigung) hat hohe Priorität.

---

## 1. Architekturvorschlag

Statt eines starren Keyword-Matchings wird eine dreistufige Pipeline empfohlen, die exakt die von dir genannte Priorisierung abbildet:

```
Nutzeräußerung (Sprache)
        │
   [STT: Speech-to-Text, offline]
        │
   [NLU/Intent-Erkennung]
        │
        ├─► 1. Lokale Wissensbasis (Memo/PDF via On-Device RAG)
        │      → Treffer? → Antwort direkt aus Dokument/Memo generieren
        │
        ├─► 2. Offline-KI (On-Device LLM, z. B. Gemini Nano/AICore)
        │      → kein Treffer in 1)? → allgemeine Antwort/Geräteaktion lokal generieren
        │
        └─► 3. Online-Fallback (nur wenn WLAN/Mobilfunk vorhanden UND Nutzer/Konfiguration
               es erlaubt UND Anfrage nicht personenbezogen/sensibel ist)
               → Cloud-Suche/Cloud-LLM als letzte Instanz
```

Wichtig: Jede Stufe sollte einen **Confidence-Schwellenwert** liefern, damit klar definiert ist, wann auf die nächste Stufe eskaliert wird (z. B. Similarity-Score der RAG-Suche, Intent-Confidence der NLU-Engine).

---

## 2. Baustein: Spracherkennung (Speech-to-Text, offline)

| Engine | Offline | Sprachen | Ressourcenbedarf | Eignung |
|---|---|---|---|---|
| **Vosk** ([alphacep/vosk-api](https://github.com/alphacep/vosk-api), [alphacephei.com/vosk](https://alphacephei.com/vosk/)) | ✅ vollständig | ~20 Sprachen inkl. Deutsch | sehr niedrig, läuft auf Raspberry Pi/Mobilgeräten | Streaming/Echtzeit, ressourcenschonend – guter Default für Embedded-Robotik |
| **Whisper (lokal, whisper.cpp)** | ✅ vollständig | 99 Sprachen, sehr robust bei Akzenten/Hintergrundgeräuschen | deutlich höher, GPU/NPU sinnvoll für Praxistauglichkeit | höhere Genauigkeit, aber schwerer für Dauerbetrieb auf begrenzter Hardware |
| **Android `SpeechRecognizer` (Offline-Modus)** | teilweise (gerätespezifisch) | abhängig vom Hersteller-Sprachpaket | gering (Systemdienst) | einfach zu integrieren, aber weniger Kontrolle/Portabilität |
| **Picovoice (Porcupine Wake-Word + Cheetah/Leopard STT)** | ✅ vollständig | mehrere Sprachen inkl. Deutsch | sehr gering, SDK-basiert, kommerzielle Lizenz nötig | professionelles SDK speziell für Voice-Produkte, gut dokumentiert |

**Einschätzung:** Für den Dauerbetrieb auf einem Serviceroboter mit begrenzter Rechenleistung ist **Vosk** aktuell der pragmatischste Startpunkt (kostenlos, Open Source, geringe Latenz, Deutsch verfügbar). Whisper (via whisper.cpp) eignet sich als Option für höhere Genauigkeit, falls die Hardware (z. B. NPU an Bord) das hergibt.

Quellen: [Vosk vs Whisper Local – Sinologic (2026)](https://www.sinologic.net/en/2026-05/vosk-vs-whisper-local-the-ultimate-2026-guide-to-self-hosted-speech-recognition-stt.html), [Vosk API GitHub](https://github.com/alphacep/vosk-api), [Picovoice Android Speech Recognition Guide 2026](https://picovoice.ai/blog/android-speech-recognition/)

---

## 3. Baustein: "Echtes Verständnis" statt Keyword-Matching (NLU / Intent)

Hier gibt es zwei grundsätzlich verschiedene Ansätze, die sich auch kombinieren lassen:

### 3.1 Speech-to-Intent / klassisches NLU (z. B. Picovoice Rhino)
Erkennt strukturierte Absichten ("Intents" mit Slots) direkt aus gesprochener Sprache, offline, sehr ressourcenschonend, ohne dass ein komplettes LLM nötig ist. Gut geeignet, wenn der Roboter primär **definierte Befehle/Aufgaben** (z. B. Gedächtnisübungen starten, Erinnerung stellen, Frage zu einem Dokument) erkennen soll, aber flexibler als starre Keyword-Listen, weil Formulierungsvarianten abgedeckt werden. ([Rhino Speech-to-Intent](https://picovoice.ai/products/voice/speech-to-intent/))

### 3.2 Generatives On-Device-LLM (freies Sprachverständnis)
Für offene, freie Formulierungen ("wie eine echte KI") braucht es ein kleines Sprachmodell direkt auf dem Gerät:

- **Gemini Nano / AICore (Android-Systemdienst):** Läuft vollständig on-device über den Android-Systemdienst AICore, bietet über ML Kit GenAI APIs u. a. Prompt-Verarbeitung, Zusammenfassung, Spracherkennung. Laut Google-Dokumentation werden Eingaben/Ausgaben von AICore **nicht gespeichert** und isoliert verarbeitet (Private Compute Core Prinzip) – das passt sehr gut zu eurer Datenschutz-Anforderung. Einschränkung: nur auf unterstützten Geräten mit ausreichender Hardware verfügbar. ([Android Developers – Gemini Nano](https://developer.android.com/ai/gemini-nano))
- **MediaPipe LLM Inference API / Nachfolger LiteRT-LM:** Ermöglicht das Ausführen offener Modelle (Gemma-3 1B/2B, Gemma-3n E2B/E4B, Phi-2) direkt auf dem Gerät. Wichtig: Die MediaPipe-LLM-API befindet sich laut Google inzwischen im **Maintenance-Only-Modus**, Google empfiehlt die Migration zu **LiteRT-LM** (Nachfolgeframework für On-Device-GenAI). Für ein neues Projekt sollte daher gleich auf LiteRT-LM gesetzt werden. Modelle sind zu groß für die APK und müssen separat auf das Gerät gebracht werden; empfohlen für aktuelle Ober-/Mittelklasse-Hardware (z. B. Pixel 8, Galaxy S23 oder neuer). ([LLM Inference Guide Android](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android), [LiteRT-LM Ankündigung](https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/))

**Einschätzung:** Eine Kombination ist sinnvoll: Rhino/klassisches NLU für die häufigsten, sicherheitskritischen Befehle (schnell, ressourcenschonend, deterministisch), Gemini Nano/AICore (bzw. LiteRT-LM mit Gemma-3n) für freies, offenes Sprachverständnis und natürliche Dialoge – abhängig von unterstützter Zielhardware.

---

## 4. Baustein: Wissensbasis aus Memo/PDF (höchste Priorität)

Für "erst im Memo/PDF nachschauen" ist ein **On-Device-RAG** (Retrieval-Augmented Generation) der richtige Ansatz:

1. PDF/Memo wird in Textabschnitte ("Chunks") zerlegt.
2. Jeder Chunk wird lokal in einen Vektor (Embedding) umgewandelt.
3. Bei einer Nutzerfrage wird der ähnlichste Chunk lokal per Vektorsuche gefunden.
4. Der gefundene Ausschnitt wird zusammen mit der Frage an das (offline oder online) LLM übergeben, das daraus die Antwort formuliert.

**Referenz-Implementierung für Android:** [Android-Document-QA](https://github.com/shubham0204/Android-Document-QA) zeigt genau diesen Aufbau – Embedding-Modell `all-MiniLM-L6-v2` (via ONNX Runtime), Vektordatenbank **ObjectBox**, austauschbares LLM-Backend (lokal via MediaPipe/LiteRT-LM oder remote). Embedding + Vektorsuche laufen dabei komplett offline.

**Alternative Vektordatenbanken für Embedded/On-Device:**
- **ObjectBox Vector DB** – nativ für Android/Mobile ausgelegt, bereits in obiger Referenzimplementierung genutzt.
- **Zvec (Alibaba, Open Source, 2026)** – "SQLite-ähnliche" eingebettete Vektordatenbank speziell für Edge-/On-Device-RAG. ([MarkTechPost, Feb. 2026](https://www.marktechpost.com/2026/02/10/alibaba-open-sources-zvec-an-embedded-vector-database-bringing-sqlite-like-simplicity-and-high-performance-on-device-rag-to-edge-applications/))
- **sqlite-vec / SQLite-Erweiterungen** – falls ohnehin eine SQLite-Datenbank in der App vorhanden ist, lässt sich Vektorsuche direkt integrieren.

**Einschätzung:** Diese Stufe ist technisch am wenigsten aufwendig und bringt den größten Nutzen für euren Use Case (Gedächtnistraining/Lerninhalte aus definierten Dokumenten) – sie sollte zuerst umgesetzt werden, bevor das freie On-Device-LLM (Stufe 2) angegangen wird.

---

## 5. Baustein: Online-Fallback

Nur als letzte Stufe, und nur wenn:
- eine Internetverbindung besteht,
- die Anfrage nicht durch Wissensbasis oder Offline-KI beantwortet werden konnte,
- die Anfrage **keine besonderen personenbezogenen Daten** (Gesundheitsdaten, Namen, Standort etc. der betreuten Person) enthält bzw. diese vorher entfernt/anonymisiert wurden,
- der Nutzer bzw. die Konfiguration das online-Nachschlagen erlaubt (idealerweise mit sichtbarem Hinweis "Ich schaue online nach…", damit für die Nutzergruppe transparent bleibt, dass jetzt Daten das Gerät verlassen).

Technisch reicht hier eine einfache Anbindung an eine Such- oder LLM-API; der eigentliche Aufwand liegt nicht in der Technik, sondern in der sauberen **Filterung/Anonymisierung**, bevor eine Anfrage überhaupt online geschickt wird.

---

## 6. Datenschutz (DSGVO) – zentrale Punkte für eure Zielgruppe

Senioren bzw. Menschen mit Beeinträchtigung sind eine besonders schutzbedürftige Nutzergruppe; Gesundheits- oder Betreuungsdaten gelten unter Art. 9 DSGVO als **besondere Kategorie personenbezogener Daten**:

- **Datenminimierung als Leitprinzip:** Wo möglich, keine biometrischen/identifizierenden Daten erheben (z. B. eher Pseudonym/QR-Code als Gesichtserkennung), keine Sprachaufnahmen dauerhaft speichern, sondern nur transient für die Verarbeitung nutzen.
- **On-Device-first ist auch datenschutzrechtlich der richtige Ansatz:** Alles, was lokal verarbeitet wird (STT, RAG, On-Device-LLM), verlässt das Gerät nicht – das reduziert den Kreis der DSGVO-Verpflichtungen erheblich gegenüber einer Cloud-Lösung. Google beschreibt für AICore/Gemini Nano genau dieses Prinzip (isolierte Verarbeitung, keine Speicherung von Ein-/Ausgaben).
- **Rechtsgrundlage klären, wenn doch Gesundheits-/Betreuungsdaten verarbeitet werden:** entweder ausdrückliche Einwilligung (Art. 9 Abs. 2 lit. a) oder Zweck der Gesundheitsversorgung unter fachlicher Verantwortung (Art. 9 Abs. 2 lit. h) – Letzteres ist bei autonomen Robotersystemen rechtlich noch nicht abschließend geklärt, sollte also im Projekt dokumentiert/mit Betreuer:innen abgestimmt werden.
- **Vertragliche Grundlage:** Bei Einsatz im Rahmen eines Betreuungs-/Pflegevertrags kann Art. 6 Abs. 1 lit. b DSGVO greifen, sofern der Robotereinsatz im Vertrag ausdrücklich vorgesehen ist.
- **Transparenz bei Online-Fallback:** Nutzer:innen (bzw. deren Betreuer:innen) sollten jederzeit erkennen können/einstellen können, ob und wann eine Anfrage das Gerät verlässt.
- **Protokollierung/Löschkonzept:** Auch lokal gespeicherte Wissensbasis-Inhalte (Memos/PDFs) sollten einem klaren Aufbewahrungs- und Löschkonzept folgen, falls sie personenbezogene Inhalte enthalten.

Quellen: [dr-datenschutz.de – Pflegeroboter und Datenschutz](https://www.dr-datenschutz.de/der-einsatz-von-pflegeroboter-und-die-datenschutzrechtliche-frage/), [DSGVO und Pflege 2026 – zweiplus](https://zwei.plus/posts/dsgvo-pflege-leitfaden-2026), [Android Developers – Gemini Nano (Private Compute Core)](https://developer.android.com/ai/gemini-nano)

---

## 7. Empfohlener Technologie-Stack (Zusammenfassung)

| Ebene | Empfehlung | Alternative |
|---|---|---|
| STT | Vosk (Deutsch) | Whisper.cpp bei ausreichender Hardware |
| Intent/NLU (definierte Befehle) | Picovoice Rhino | eigenes klassisches NLU |
| Freies Sprachverständnis | Gemini Nano / AICore | LiteRT-LM + Gemma-3n |
| Wissensbasis (Memo/PDF) | On-Device-RAG: ONNX-Embedding (`all-MiniLM-L6-v2`) + ObjectBox Vector DB | Zvec, sqlite-vec |
| Online-Fallback | Cloud-Such-/LLM-API, nur nach Filterung/Anonymisierung | – |
| Datenschutz-Leitplanke | On-Device-first, Datenminimierung, Transparenz bei Online-Zugriff | – |

---

## 8. Offene Punkte für die nächsten Schritte

- Zielhardware des Roboters klären (RAM/NPU vorhanden?) – das entscheidet, ob Gemini Nano/AICore bzw. LiteRT-LM überhaupt lauffähig sind, oder ob zunächst nur Vosk + Rhino + RAG realistisch ist.
- Prüfen, ob AICore/Gemini Nano auf der bei Becker Robotics eingesetzten Android-Version/Geräteklasse überhaupt verfügbar ist (Geräte-/API-Level-Voraussetzungen).
- Konzept für Einwilligung/Transparenz bei Online-Fallback mit Betreuungspersonal abstimmen (relevant für Dokumentation/Projektantrag).
- Entscheidung, ob Confidence-Schwellenwerte pro Stufe (RAG-Trefferqualität, Intent-Confidence) fest oder konfigurierbar sein sollen.

---

### Quellenverzeichnis

- [Vosk API (GitHub)](https://github.com/alphacep/vosk-api)
- [VOSK Offline Speech Recognition](https://alphacephei.com/vosk/)
- [Vosk vs Whisper Local – Sinologic (2026)](https://www.sinologic.net/en/2026-05/vosk-vs-whisper-local-the-ultimate-2026-guide-to-self-hosted-speech-recognition-stt.html)
- [Picovoice – Android Speech Recognition Guide 2026](https://picovoice.ai/blog/android-speech-recognition/)
- [Picovoice – Rhino Speech-to-Intent](https://picovoice.ai/products/voice/speech-to-intent/)
- [Android Developers – Gemini Nano](https://developer.android.com/ai/gemini-nano)
- [Google AI Edge – LLM Inference Guide für Android](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android)
- [Google Developers Blog – LiteRT-LM](https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/)
- [Android-Document-QA (GitHub Referenzimplementierung On-Device RAG)](https://github.com/shubham0204/Android-Document-QA)
- [Alibaba Zvec – Embedded Vector DB für Edge-RAG (MarkTechPost, 2026)](https://www.marktechpost.com/2026/02/10/alibaba-open-sources-zvec-an-embedded-vector-database-bringing-sqlite-like-simplicity-and-high-performance-on-device-rag-to-edge-applications/)
- [dr-datenschutz.de – Pflegeroboter und die datenschutzrechtliche Frage](https://www.dr-datenschutz.de/der-einsatz-von-pflegeroboter-und-die-datenschutzrechtliche-frage/)
- [DSGVO und Pflege 2026 – Leitfaden (zweiplus)](https://zwei.plus/posts/dsgvo-pflege-leitfaden-2026)
