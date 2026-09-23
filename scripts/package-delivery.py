"""Assemble the tested Android handoff without modifying the input archive."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
out = root.parent / "Serviceroboter-KI-Paket"
out.mkdir(exist_ok=True)
reports = out / "Pruefberichte"
reports.mkdir(exist_ok=True)
licenses = out / "Lizenzen"
licenses.mkdir(exist_ok=True)

def sha256(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(8 * 1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()

expected = {
    "Qwen3-4B-Instruct-2507-Q4_K_M.gguf": "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597",
    "Thorsten-Deutsch-arm64.apk": "04058f8a3497ff5546e6d3ffceb2cd28b34e944ca539db2c2833fae356559ed8",
}
for name, digest in expected.items():
    assert sha256(root / "downloads" / name) == digest, name
print("Modell- und Sprachpaket-Pruefsummen bestaetigt.", flush=True)

tests = failures = errors = skipped = 0
for path in sorted((root / "core/build/test-results/test").glob("TEST-*.xml")):
    suite = ET.parse(path).getroot()
    tests += int(suite.get("tests", "0"))
    failures += int(suite.get("failures", "0"))
    errors += int(suite.get("errors", "0"))
    skipped += int(suite.get("skipped", "0"))
    shutil.copy2(path, reports / path.name)
assert tests == 44 and failures == errors == skipped == 0
lint = (root / "app/build/reports/lint-results-debug.txt").read_text(encoding="utf-8")
assert "0 errors, 9 warnings" in lint
shutil.copy2(root / "app/build/reports/lint-results-debug.txt", reports / "Android-Lint.txt")
shutil.copy2(root / "app/build/reports/lint-results-debug.html", reports / "Android-Lint.html")
for path in (root / "validation").iterdir():
    if path.is_file():
        shutil.copy2(path, reports / path.name)
for path in (root / "licenses").iterdir():
    if path.is_file():
        shutil.copy2(path, licenses / path.name)
shutil.copy2(root / "vendor/llama.cpp/LICENSE", licenses / "llama.cpp-LICENSE.txt")
shutil.copy2(root / "app/src/main/assets/embeddings/README.md", licenses / "Jina-MODEL-CARD.md")
shutil.copy2(root / "validation/Thorsten-MODEL_CARD.txt", licenses / "Thorsten-MODEL-CARD.txt")
shutil.copy2(root / "downloads/checksums.json", reports / "download-checksums.json")
shutil.copy2(root / "app/src/main/assets/embeddings/download-manifest.json", reports / "embedding-download-manifest.json")

provenance = """# Herkunft der enthaltenen Komponenten

Stand: 15.09.2026. Die Lizenztexte und Modellkarten liegen in diesem Ordner.

- Qwen3-4B-Instruct-2507: Qwen, Apache 2.0.
  https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507
- Quantisierung Q4_K_M: Unsloth, Revision a06e946bb6b655725eafa393f4a9745d460374c9.
  https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF
- llama.cpp: MIT, Commit b29c606e28a01b1bc8c1351026a0fa6e616bf6c4.
  https://github.com/ggml-org/llama.cpp
- Deutsche Embeddings: jinaai/jina-embeddings-v2-base-de, Apache 2.0,
  Revision 3f9eede875721714945b6a99a3198299243cf2be.
  https://huggingface.co/jinaai/jina-embeddings-v2-base-de
- Thorsten medium: deutsche Stimme, Datensatz CC0 laut beiliegender Modellkarte.
  https://github.com/thorstenMueller/Thorsten-Voice
- Android-TTS-Laufzeit: sherpa-onnx 1.13.8, Apache 2.0.
  APK aus der offiziellen sherpa-onnx-Liste.
  https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html
- Vosk: vom Nutzer bereitgestelltes vosk-model-small-de-0.15.
  https://alphacephei.com/vosk/models
- Vorhandenes MiniLM-Modell und vocab.txt bleiben als Alternative im Quellprojekt.
  https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2

Die ursprünglichen Desktop-Dateien und das ursprüngliche Projektarchiv wurden nicht verändert.
Die Bearbeitung des App-Codes ist im Quellpaket enthalten. Weitere Android-Abhängigkeiten
sind in app/build.gradle.kts aufgeführt; deren Lizenzen gelten ebenfalls.
"""
(licenses / "HERKUNFT.md").write_text(provenance, encoding="utf-8")
summary = """# Prüfbericht – Serviceroboter KI

Stand: 15.09.2026

## Ergebnis

- Android-App für arm64-v8a und x86_64 erfolgreich gebaut.
- Mindestversion Android 7.0 / API 24, Zielversion Android 14 / API 34.
- Mitgelieferte TTS-APK: arm64-v8a.
- 44 JUnit-Tests: 0 Fehler, 0 Fehlschläge, 0 übersprungen.
- Darin 22 Referenzfälle für WordPiece und Byte-BPE, verglichen mit HuggingFace-Tokenizern.
- Deutsche Jina-Embeddings tatsächlich auf Windows-CPU ausgeführt: 5/5 Suchfragen
  liefern den erwarteten ersten Treffer unter vier synthetischen Abschnitten.
- Qwen-GGUF tatsächlich auf Windows-CPU ausgeführt: 4/4 Szenarien bestanden
  (belegte Dokumentantwort, fehlende Dokumentantwort, Allgemeinwissen, aktuelle Information).
- Thorsten-WAV lokal erzeugt: 22.050 Hz, etwa 7,72 Sekunden Audio.
- Wikipedia-API mit einer öffentlichen Frage geprüft: Ergebnisse mit Quellenlinks.
- Android-Lint: 0 Fehler, 9 Warnungen. Der vollständige Bericht liegt bei.
  Warnungen: neueres Ziel-SDK, neuere AndroidX-Versionen, drei TrustManager-Hilfsklassen
  in der PDFBox-Abhängigkeit BouncyCastle 1.72. Der eigene Netzwerkclient verwendet
  die normale OkHttp-Zertifikatsprüfung.
- Modell- und Sprachpaket-SHA-256 mit den vorab erfassten Werten abgeglichen.
- Der alte fest eingetragene API-Schlüssel ist nicht im bearbeiteten App-Quellcode.
- Quellarchiv nach Erstellung mit ZIP-CRC-Prüfung kontrolliert.

## Grenzen der Prüfung

Kein Android-Gerät und kein Roboter war angeschlossen.
Das Zusammenspiel von Mikrofon, Android-JNI-Modell, Android-TTS-Dienst und Oberfläche
wurde deshalb nicht auf dem Zielgerät getestet. Die PC-Modelltests verwenden eine
separate offizielle Windows-llama.cpp-Laufzeit; sie sind kein Android-End-to-End-Test.
Die Android-native Bibliothek wurde für beide angegebenen Architekturen kompiliert.

PC-Zeitbedarf beim 4B-Modell: ungefähr 25–40 Sekunden pro Anfrage einschließlich
Modellstart. Die App hält das Modell geladen und verwendet passende Prompt-Präfixe
erneut. Ein belastbarer Leistungswert für den Roboter liegt noch nicht vor.

Die synthetischen Fälle prüfen Funktion und Reihenfolge. Sie belegen weder
umfassende Antwortqualität noch medizinische Eignung.
Die allgemeine Websuche über SearXNG braucht eine konkrete Betreiberadresse und
wurde mangels bereitgestelltem Endpunkt nicht gegen einen solchen Dienst getestet.

Siehe README.md im Paket für Einrichtung, praktische Grenzen und Geräte-Abnahme.
"""
(reports / "ZUSAMMENFASSUNG.md").write_text(summary, encoding="utf-8")
copies = {
    root / "app/build/outputs/apk/debug/app-debug.apk": "01-Serviceroboter-KI.apk",
    root / "downloads/Thorsten-Deutsch-arm64.apk": "02-Thorsten-Deutsch-arm64.apk",
    root / "downloads/Qwen3-4B-Instruct-2507-Q4_K_M.gguf": "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
    root / "README.md": "README.md",
    root / "START-HIER.html": "START-HIER.html",
    root / "validation/Hoerprobe-Thorsten.wav": "Hoerprobe-Thorsten.wav",
}
for source, name in copies.items():
    shutil.copy2(source, out / name)
    print("Bereit: " + name, flush=True)

# An explicit top-level allowlist prevents packaging caches or the original archive.
allowed = {"app", "core", "gradle", "scripts", "vendor", "validation", "licenses",
           "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
           "gradlew", "gradlew.bat", ".gitignore", "README.md", "START-HIER.html"}
excluded = {"build", ".cxx", ".gradle", ".git", "__pycache__", ".github"}
source_files = []
for top in sorted(root.iterdir()):
    if top.name not in allowed:
        continue
    candidates = sorted(top.rglob("*")) if top.is_dir() else [top]
    for path in candidates:
        rel = path.relative_to(root)
        if not path.is_file() or any(part in excluded for part in rel.parts):
            continue
        if path.name == "BuildNetworkProbe.java":
            continue
        if path.suffix.lower() in {".kt", ".kts", ".md", ".json", ".properties", ".ps1", ".xml"} and rel.parts[0] != "vendor":
            if re.search(rb"AIza[0-9A-Za-z_-]{35}", path.read_bytes()):
                raise RuntimeError("Ein API-Schluessel wurde in einer Quelldatei gefunden: " + rel.as_posix())
        source_files.append(path)
archive_path = out / "Serviceroboter-KI-Quellcode.zip"
with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=4, allowZip64=True) as archive:
    for path in source_files:
        archive.write(path, "serviceroboter-ki/" + path.relative_to(root).as_posix())
with zipfile.ZipFile(archive_path) as archive:
    assert archive.testzip() is None
    names = set(archive.namelist())
    for required in ("gradlew.bat", "app/src/main/cpp/native_llm.cpp",
                     "vendor/llama.cpp/CMakeLists.txt",
                     "app/src/main/assets/embeddings/model.onnx"):
        assert "serviceroboter-ki/" + required in names, required
print("Quellarchiv geprueft: " + str(len(source_files)) + " Dateien.", flush=True)

# Validate the local guide's downloadable links without opening or installing APKs.
guide = (out / "START-HIER.html").read_text(encoding="utf-8")
for link in re.findall(r'(?:href|src)="([^"]+)"', guide):
    if "://" not in link and not link.startswith("#") and link != "SHA256SUMS.txt":
        assert (out / link).is_file(), link

entries = []
for path in sorted(out.rglob("*")):
    if path.is_file() and path.name not in {"SHA256SUMS.txt", "PAKET-MANIFEST.json"}:
        entries.append({"file": path.relative_to(out).as_posix(), "bytes": path.stat().st_size,
                        "sha256": sha256(path)})
(out / "SHA256SUMS.txt").write_text(
    "".join(e["sha256"] + "  " + e["file"] + "\n" for e in entries), encoding="utf-8")
(out / "PAKET-MANIFEST.json").write_text(json.dumps(
    {"date": "2026-09-15", "junit_tests": tests, "lint_errors": 0, "lint_warnings": 9,
     "source_files": len(source_files), "files": entries},
    ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"package": str(out), "files": len(entries), "bytes": sum(e["bytes"] for e in entries)}, ensure_ascii=False), flush=True)

