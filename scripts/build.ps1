param([switch]$CoreOnly, [switch]$Lint)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$jdkCandidates=@(
 (Join-Path $env:USERPROFILE '.gradle/jdks/eclipse_adoptium-21-amd64-windows.2'),
 $env:JAVA_HOME
)
$javaExecutable=$jdkCandidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin/java.exe')) } | ForEach-Object { Join-Path $_ 'bin/java.exe' } | Select-Object -First 1
if (-not $javaExecutable) { throw 'Bitte JDK 21 installieren oder JAVA_HOME auf JDK 21 setzen.' }
# On this Windows host, AF_UNIX accepts bind but fails on connect.
# A nonexistent socket directory makes Java use its built-in TCP loopback fallback.
# This setting affects only this build process and its Java workers.
$socketDirectory=Join-Path $projectRoot '.build/no-unix-sockets'
$jvmArguments='-Xmx3072m -Dfile.encoding=UTF-8 -Djdk.net.unixdomain.tmpdir='+$socketDirectory.Replace('\','/')
$targets=if ($CoreOnly) {@(':core:test')} else {@(':core:test',':app:assembleDebug')}
if ($Lint) { $targets += ':app:lintDebug' }
Push-Location $projectRoot
$previousJavaOptions=$env:JAVA_TOOL_OPTIONS
try {
 $env:JAVA_TOOL_OPTIONS=('-Djdk.net.unixdomain.tmpdir='+$socketDirectory.Replace('\','/'))
 & $javaExecutable ('-Djdk.net.unixdomain.tmpdir='+$socketDirectory.Replace('\','/')) -jar 'gradle/wrapper/gradle-wrapper.jar' @targets --no-daemon ('-Dorg.gradle.jvmargs='+$jvmArguments)
 if ($LASTEXITCODE -ne 0) { throw ('Build fehlgeschlagen: '+$LASTEXITCODE) }
} finally { $env:JAVA_TOOL_OPTIONS=$previousJavaOptions; Pop-Location }
