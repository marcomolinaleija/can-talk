#Requires -Version 7
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidatePattern('^1\.0\.\d+$')]
    [string]$Version,
    [string]$ArchivoNotas = (Join-Path $PSScriptRoot 'talkback/src/main/res/raw/release_notes.txt'),
    [string]$Repo = 'can-apps/can-talk',
    [string]$Remote = 'origin',
    [switch]$SkipBuild,
    [switch]$SoloPreparar
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
function Invoke-Native([string]$Command, [string[]]$Arguments) {
    $output = & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command falló ($LASTEXITCODE): $($Arguments -join ' ')" }
    return $output
}

# Older clients read only hash-prefixed bullet lines; keep their visible text editorial.
function Get-LegacyReleaseBody([string]$Notes, [int]$Build, [string]$Commit) {
    $shortHash = $Commit.Substring(0, 7)
    $summaries = @($Notes -split '\r?\n' | Where-Object { $_.StartsWith('- ') } |
        ForEach-Object { "- $shortHash $($_.Substring(2))" })
    if ($summaries.Count -eq 0) {
        $summaries = @("- $shortHash $(($Notes -split '\r?\n')[0])")
    }
    $legacyNotes = $Notes -replace '(?m)^- ', '• '
    return "build: $Build`n`n$($summaries -join "`n")`n`n$legacyNotes"
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
$latestOutput = & gh api "repos/$Repo/releases/latest" 2>&1
if ($LASTEXITCODE -eq 0) {
    $latest = ($latestOutput -join "`n") | ConvertFrom-Json
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
} elseif (($latestOutput | Out-String) -match 'HTTP 404|Not Found|\b404\b') {
    Invoke-Native gh @('repo', 'view', $Repo, '--json', 'name') | Out-Null
} else {
    throw "gh api falló ($LASTEXITCODE): $($latestOutput | Out-String)"
}
$existingTags = Invoke-Native git @('ls-remote', '--tags', $Remote, "refs/tags/$Version")
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
Set-Content -LiteralPath $legacyFile -Value (Get-LegacyReleaseBody $notes $count $head) -Encoding utf8NoBOM
if ($SoloPreparar) {
    Write-Host "Artefactos preparados sin tag, push ni publicación: $artifactDirectory"
    return
}

# A stable tag is immutable. Never force an existing version or replace its assets.
Invoke-Native git @('tag', '-a', $Version, '-m', "CAN Talk $Version", $head) | Out-Host
Invoke-Native git @('push', $Remote, "refs/tags/$Version") | Out-Host
# Assets are uploaded to a draft before the release becomes visible to the updater.
Invoke-Native gh @('release', 'create', $Version, $apk, $manifest, $notesFile, '--repo', $Repo,
    '--verify-tag', '--draft', '--title', "CAN Talk $Version", '--notes-file', $notesFile) | Out-Host
Invoke-Native gh @('release', 'edit', $Version, '--repo', $Repo, '--draft=false', '--latest') | Out-Host

# Compatibility channel for already-installed 1.0.0–1.0.2 clients. Only dev is movable.
Invoke-Native git @('tag', '-f', 'dev', "$Version^{}") | Out-Host
Invoke-Native git @('push', $Remote, 'refs/tags/dev', '--force') | Out-Host
$devViewOutput = & gh release view dev --repo $Repo --json tagName 2>&1
if ($LASTEXITCODE -eq 0) {
    Invoke-Native gh @('release', 'upload', 'dev', $apk, $manifest, $notesFile, '--repo', $Repo, '--clobber') | Out-Host
    Invoke-Native gh @('release', 'edit', 'dev', '--repo', $Repo, '--prerelease', '--title', "CAN Talk $Version", '--notes-file', $legacyFile) | Out-Host
} elseif (($devViewOutput | Out-String) -match 'release not found|HTTP 404|Not Found') {
    Invoke-Native gh @('release', 'create', 'dev', $apk, $manifest, $notesFile, '--repo', $Repo,
        '--verify-tag', '--prerelease', '--title', "CAN Talk $Version", '--notes-file', $legacyFile) | Out-Host
} else {
    throw "gh release view dev falló ($LASTEXITCODE): $($devViewOutput | Out-String)"
}
Write-Host "Publicadas $Version y dev. APK SHA-256: $sha256"
