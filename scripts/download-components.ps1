param([switch]$IncludeModel)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$projectRoot = Split-Path $PSScriptRoot -Parent
$downloadRoot = Join-Path $projectRoot 'downloads'
New-Item -ItemType Directory -Path $downloadRoot -Force | Out-Null
function Download-Verified($url, $destination, $expectedHash) {
 if (Test-Path -LiteralPath $destination) {
  if ($expectedHash -and (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -eq $expectedHash.ToLowerInvariant()) { return }
  if (-not $expectedHash) { return }
 }
 $partial = $destination + '.partial'
 Invoke-WebRequest -Uri $url -OutFile $partial
 if ($expectedHash -and (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash.ToLowerInvariant()) { throw ('Hash stimmt nicht: ' + $destination) }
 Move-Item -LiteralPath $partial -Destination $destination -Force
}
$llamaCommit='b29c606e28a01b1bc8c1351026a0fa6e616bf6c4'
Download-Verified ('https://codeload.github.com/ggml-org/llama.cpp/zip/'+$llamaCommit) (Join-Path $downloadRoot 'llama-source.zip') $null
$vendorRoot=Join-Path $projectRoot 'vendor'
New-Item -ItemType Directory -Path $vendorRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $vendorRoot 'llama.cpp'))) {
 Expand-Archive -LiteralPath (Join-Path $downloadRoot 'llama-source.zip') -DestinationPath $vendorRoot
 Rename-Item -LiteralPath (Join-Path $vendorRoot ('llama.cpp-'+$llamaCommit)) -NewName 'llama.cpp'
}
$voiceRepo='csukuangfj2/sherpa-onnx-apk'
$voiceFile='tts-engine-new/1.13.8/sherpa-onnx-1.13.8-arm64-v8a-deu-tts-engine-vits-piper-de_DE-thorsten-medium.apk'
$voiceTree=Invoke-RestMethod -Uri ('https://huggingface.co/api/models/'+$voiceRepo+'/tree/main/tts-engine-new/1.13.8?expand=false&limit=1000')
$voiceInfo=$voiceTree | Where-Object { $_.path -eq $voiceFile }
if (-not $voiceInfo) {
 $voicePaths=@{paths=@($voiceFile);expand=$true} | ConvertTo-Json
 $voiceInfo=Invoke-RestMethod -Method Post -ContentType 'application/json' -Body $voicePaths -Uri ('https://huggingface.co/api/models/'+$voiceRepo+'/paths-info/main')
}
Download-Verified ('https://huggingface.co/'+$voiceRepo+'/resolve/main/'+$voiceFile) (Join-Path $downloadRoot 'Thorsten-Deutsch-arm64.apk') $voiceInfo.lfs.oid
if ($IncludeModel) {
 $modelRepo='unsloth/Qwen3-4B-Instruct-2507-GGUF'
 $modelCommit='a06e946bb6b655725eafa393f4a9745d460374c9'
 $modelFile='Qwen3-4B-Instruct-2507-Q4_K_M.gguf'
 $modelTree=Invoke-RestMethod -Uri ('https://huggingface.co/api/models/'+$modelRepo+'/tree/'+$modelCommit)
 $modelInfo=$modelTree | Where-Object { $_.path -eq $modelFile }
 if (-not $modelInfo.lfs.oid) { throw 'Keine Prüfsumme für das Sprachmodell verfügbar.' }
 Download-Verified ('https://huggingface.co/'+$modelRepo+'/resolve/'+$modelCommit+'/'+$modelFile) (Join-Path $downloadRoot $modelFile) $modelInfo.lfs.oid
}
Get-ChildItem -LiteralPath $downloadRoot -File | ForEach-Object {
 [pscustomobject]@{File=$_.Name;Bytes=$_.Length;SHA256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $downloadRoot 'checksums.json') -Encoding utf8
Write-Output 'Downloads und Prüfsummen sind vorbereitet.'
