#Requires -Version 7
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Version,

    [Parameter(Mandatory = $false)]
    [string]$Notas,

    [Parameter(Mandatory = $false)]
    [string]$ArchivoNotas,

    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

if (-not $Notas -and -not $ArchivoNotas) {
    throw "Debes proporcionar las notas de la versión en español mediante -Notas o -ArchivoNotas."
}

if ($ArchivoNotas) {
    if (-not (Test-Path -LiteralPath $ArchivoNotas)) {
        throw "El archivo de notas especificado no existe: $ArchivoNotas"
    }
    $Notas = Get-Content -LiteralPath $ArchivoNotas -Raw
}

# 1. Verificar gh CLI autenticado
gh auth status 2>$null
if ($LASTEXITCODE -ne 0) {
    throw "GitHub CLI (gh) no está autenticado. Ejecuta 'gh auth login' antes de continuar."
}

# 2. Compilar APK si no se indicó -SkipBuild
$apkDest = Join-Path $PSScriptRoot "cantalk.apk"
if (-not $SkipBuild) {
    Write-Host "Compilando APK :assemblePhoneDebug..." -ForegroundColor Cyan
    & (Join-Path $PSScriptRoot "compilar.ps1")
    if ($LASTEXITCODE -ne 0) {
        throw "La compilación de :assemblePhoneDebug falló con código $LASTEXITCODE."
    }
    $apkSource = Join-Path $PSScriptRoot "build/outputs/apk/phone/debug/cantalk-phone-debug.apk"
    if (-not (Test-Path -LiteralPath $apkSource)) {
        throw "No se encontró el APK generado en: $apkSource"
    }
    Copy-Item -LiteralPath $apkSource -Destination $apkDest -Force
}

if (-not (Test-Path -LiteralPath $apkDest)) {
    throw "No se encontró $apkDest para adjuntar a la release."
}

# 3. Calcular número de build (total de commits en HEAD)
$buildCount = (git rev-list --count HEAD).Trim()
if (-not $buildCount -or $buildCount -eq "0") {
    throw "No se pudo obtener el número de commits de Git."
}

# 4. Obtener commits desde la última versión etiquetada (ignorando 'dev')
$prevTag = git tag -l "1.*" | Sort-Object | Select-Object -Last 1
$commits = if ($prevTag -and $prevTag -ne $Version) {
    git log "$prevTag..HEAD" --oneline
} else {
    git log -n 15 --oneline
}

$commitLines = ($commits -split "`r?`n" | Where-Object { $_.Trim().Length -gt 0 } | ForEach-Object { "- $_" }) -join "`n"

# 5. Formatear cuerpo obligatorio para UpdateChecker.kt
$body = @"
build: $buildCount

$commitLines

$Notas
"@

$tempBodyFile = [System.IO.Path]::GetTempFileName()
try {
    Set-Content -LiteralPath $tempBodyFile -Value $body -Encoding UTF8

    # 6. Crear tag y pushear a origin
    Write-Host "Creando tag $Version..." -ForegroundColor Cyan
    git tag -a $Version -m "CAN Talk $Version" -f
    git push origin $Version -f

    # 7. Crear o actualizar Release en GitHub para $Version
    Write-Host "Publicando release $Version en GitHub..." -ForegroundColor Cyan
    gh release view $Version --repo marcomolinaleija/can-talk 2>$null
    if ($LASTEXITCODE -eq 0) {
        gh release upload $Version $apkDest --repo marcomolinaleija/can-talk --clobber
        gh release edit $Version --repo marcomolinaleija/can-talk --title "CAN Talk $Version" --notes-file $tempBodyFile
    } else {
        gh release create $Version $apkDest --repo marcomolinaleija/can-talk --title "CAN Talk $Version" --notes-file $tempBodyFile
    }

    # 8. Sincronizar canal 'dev' para el actualizador interno de la app
    Write-Host "Sincronizando canal de actualización 'dev'..." -ForegroundColor Cyan
    git tag -f dev $Version
    git push -f origin dev
    gh release upload dev $apkDest --repo marcomolinaleija/can-talk --clobber
    gh release edit dev --repo marcomolinaleija/can-talk --title "CAN Talk $Version" --notes-file $tempBodyFile

    Write-Host "¡Release $Version y canal 'dev' publicados y sincronizados con éxito!" -ForegroundColor Green
} finally {
    Remove-Item -LiteralPath $tempBodyFile -ErrorAction SilentlyContinue
}
