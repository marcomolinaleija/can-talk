#Requires -Version 7
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidatePattern('^1\.0\.\d+$')]
    [string]$Version,
    [string]$ArchivoNotas = (Join-Path $PSScriptRoot 'talkback/src/main/res/raw/release_notes.txt'),
    [switch]$SkipBuild,
    [switch]$SoloPreparar
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$repo = 'marcomolinaleija/can-talk'
function Invoke-Native([string]$Command, [string[]]$Arguments) {
    $output = & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command falló ($LASTEXITCODE): $($Arguments -join ' ')" }
    return $output
}

# Fail before any build, tag or publication if source and notes are inconsistent.
if (Invoke-Native git @('status', '--porcelain')) { throw 'El árbol debe estar limpio y confirmado antes de publicar.' }
$head = (Invoke-Native git @('rev-parse', 'HEAD')).Trim()
$count = [int](Invoke-Native git @('rev-list', '--count', 'HEAD'))
$notes = (Get-Content -LiteralPath $ArchivoNotas -Raw).Trim()
$canonicalNotes = (Get-Content -LiteralPath 'talkback/src/main/res/raw/release_notes.txt' -Raw).Trim()
if ($notes -ne $canonicalNotes -or -not $notes.StartsWith("CAN Talk $Version`n") -and -not $notes.StartsWith("CAN Talk $Version`r`n")) {
    throw 'Las notas deben coincidir con release_notes.txt e indicar la versión solicitada.'
}
if ((Get-Content version.gradle -Raw) -notmatch ('talkbackVersionName = "' + [regex]::Escape($Version) + '"')) {
    throw 'La versión solicitada no coincide con version.gradle.'
}
Invoke-Native gh @('auth', 'status') | Out-Host
$latest = (Invoke-Native gh @('api', "repos/$repo/releases/latest")) -join "`n" | ConvertFrom-Json
if ([version]$Version -le [version]$latest.tag_name.TrimStart('v')) {
    throw 'La versión debe ser mayor que la última versión estable publicada.'
}
$latestManifest = @($latest.assets | Where-Object name -EQ 'update.json')
if ($latestManifest.Count -eq 1) {
    $published = Invoke-RestMethod -Uri $latestManifest[0].browser_download_url
    if ((70000000L + $count) -le [long]$published.apks.'cantalk.apk'.versionCode) {
        throw 'El código del APK debe ser mayor que el último código publicado.'
    }
} else {
    # Older releases have no JSON. Their immutable tag still identifies the build history.
    $publishedCount = [int](Invoke-Native git @('rev-list', '--count', $latest.tag_name))
    if ($count -le $publishedCount) { throw 'El historial no avanza respecto de la versión publicada.' }
}
$existingTags = Invoke-Native git @('ls-remote', '--tags', 'origin', "refs/tags/$Version")
if ($existingTags) { throw "La versión $Version ya tiene tag remoto. No se sobrescriben versiones publicadas." }

if (-not $SkipBuild) { & (Join-Path $PSScriptRoot 'compilar.ps1') }
$apkSource = Join-Path $PSScriptRoot 'build/outputs/apk/phone/debug/cantalk-phone-debug.apk'
$metadata = Get-Content (Join-Path (Split-Path $apkSource) 'output-metadata.json') -Raw | ConvertFrom-Json
$element = @($metadata.elements)[0]
if ($metadata.applicationId -ne 'can.cantalk' -or $element.versionName -ne $Version -or $element.versionCode -ne (70000000 + $count)) {
    throw 'El APK no coincide con el paquete, versión o commit actual. Vuelve a compilar.'
}
# Read the APK itself, not only Gradle output metadata (which can be stale).
$local = Get-Content entorno.local.json -Raw | ConvertFrom-Json
$buildTools = Get-ChildItem (Join-Path $local.ANDROID_HOME 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$aapt = Join-Path $buildTools.FullName 'aapt2.exe'
$badging = (Invoke-Native $aapt @('dump', 'badging', $apkSource)) -join "`n"
if ($badging -notmatch "package: name='can.cantalk' versionCode='$($element.versionCode)' versionName='$Version'") {
    throw 'La identidad interna del APK no coincide con sus metadatos.'
}
$env:JAVA_HOME = $local.JAVA_HOME
Invoke-Native (Join-Path $buildTools.FullName 'apksigner.bat') @('verify', '--verbose', $apkSource) | Out-Host

$artifactDirectory = Join-Path $PSScriptRoot "build/releases/$Version"
New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null
$apk = Join-Path $artifactDirectory 'cantalk.apk'
Copy-Item -LiteralPath $apkSource -Destination $apk -Force
$sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$manifest = Join-Path $artifactDirectory 'update.json'
@{ schemaVersion = 1; commit = $head; apks = @{ 'cantalk.apk' = @{
    versionCode = [long]$element.versionCode; versionName = $Version; sha256 = $sha256
}}} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifest -Encoding utf8NoBOM
$notesFile = Join-Path $artifactDirectory 'release-notes.txt'
Set-Content -LiteralPath $notesFile -Value $notes -Encoding utf8NoBOM
$legacyFile = Join-Path $artifactDirectory 'dev-notes.txt'
$legacyNotes = $notes -replace '(?m)^- ', '• '
Set-Content -LiteralPath $legacyFile -Value "build: $count`n`n- $($head.Substring(0,7)) Búsqueda de actualizaciones y estabilidad de la voz mejoradas.`n`n$legacyNotes" -Encoding utf8NoBOM
if ($SoloPreparar) {
    Write-Host "Artefactos preparados sin tag, push ni publicación: $artifactDirectory"
    return
}

# A stable tag is immutable. Never force an existing version or replace its assets.
Invoke-Native git @('tag', '-a', $Version, '-m', "CAN Talk $Version", $head) | Out-Host
Invoke-Native git @('push', 'origin', "refs/tags/$Version") | Out-Host
# Assets are uploaded to a draft before the release becomes visible to the updater.
Invoke-Native gh @('release', 'create', $Version, $apk, $manifest, $notesFile, '--repo', $repo,
    '--verify-tag', '--draft', '--title', "CAN Talk $Version", '--notes-file', $notesFile) | Out-Host
Invoke-Native gh @('release', 'edit', $Version, '--repo', $repo, '--draft=false', '--latest') | Out-Host

# Compatibility channel for already-installed 1.0.0–1.0.2 clients. Only dev is movable.
Invoke-Native git @('tag', '-f', 'dev', "$Version^{}") | Out-Host
Invoke-Native git @('push', 'origin', 'refs/tags/dev', '--force') | Out-Host
Invoke-Native gh @('release', 'upload', 'dev', $apk, $manifest, $notesFile, '--repo', $repo, '--clobber') | Out-Host
Invoke-Native gh @('release', 'edit', 'dev', '--repo', $repo, '--prerelease', '--title', "CAN Talk $Version", '--notes-file', $legacyFile) | Out-Host
Write-Host "Publicadas $Version y dev. APK SHA-256: $sha256"
