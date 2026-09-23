$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$projectRoot=Split-Path $PSScriptRoot -Parent
$pythonExecutable=Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
& $pythonExecutable -m pip install --disable-pip-version-check --target (Join-Path $projectRoot '.validation/python') 'onnxruntime==1.23.2' 'tokenizers==0.22.1' 'sherpa-onnx==1.13.8'
if ($LASTEXITCODE -ne 0) { throw 'Validierungswerkzeuge konnten nicht installiert werden.' }
$release=Invoke-RestMethod -Uri 'https://api.github.com/repos/ggml-org/llama.cpp/releases/tags/v0.4.1'
$release.assets | Where-Object { $_.name -match 'win|Windows' } | Select-Object name,browser_download_url,size | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $projectRoot '.validation/llama-windows-assets.json') -Encoding utf8
Write-Output 'Validierungswerkzeuge vorbereitet.'
