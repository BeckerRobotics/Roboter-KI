$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$projectRoot=Split-Path $PSScriptRoot -Parent
$assetRoot=Join-Path $projectRoot 'app/src/main/assets/embeddings'
New-Item -ItemType Directory -Path $assetRoot -Force | Out-Null
$repo='jinaai/jina-embeddings-v2-base-de'
$revision='3f9eede875721714945b6a99a3198299243cf2be'
$paths=@('onnx/model_quantized.onnx','vocab.json','merges.txt','tokenizer_config.json','tokenizer.json','README.md')
$manifest=Invoke-RestMethod -Uri ('https://huggingface.co/api/models/'+$repo+'/paths-info/'+$revision) -Method Post -ContentType 'application/json' -Body (@{paths=$paths;expand=$true} | ConvertTo-Json)
foreach ($item in $manifest) {
 $name=if ($item.path -eq 'onnx/model_quantized.onnx') {'model.onnx'} else {[System.IO.Path]::GetFileName($item.path)}
 $destination=Join-Path $assetRoot $name
 if (Test-Path -LiteralPath $destination) { continue }
 $partial=$destination+'.partial'
 Invoke-WebRequest -Uri ('https://huggingface.co/'+$repo+'/resolve/'+$revision+'/'+$item.path) -OutFile $partial
 if ($item.lfs.oid -and (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant() -ne $item.lfs.oid.ToLowerInvariant()) { throw ('Prüfsumme stimmt nicht: '+$name) }
 Move-Item -LiteralPath $partial -Destination $destination
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $assetRoot 'download-manifest.json') -Encoding utf8
Write-Output 'Deutsches Suchmodell heruntergeladen.'
