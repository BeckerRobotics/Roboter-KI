$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$projectRoot=Split-Path $PSScriptRoot -Parent
$archive=Join-Path $projectRoot '.validation/llama-windows.zip'
Invoke-WebRequest -Uri 'https://github.com/ggml-org/llama.cpp/releases/download/b10952/llama-b10952-bin-win-cpu-x64.zip' -OutFile $archive
$expected='9500c38f614a2971fd7142b0a7ca270a29e0f703e5a5904c2ae9e9f9b739060f'
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'Prüfsumme der Test-Laufzeit stimmt nicht.' }
Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $projectRoot '.validation/llama-windows') -Force
Write-Output 'Geprüfte Windows-Testlaufzeit bereit.'
