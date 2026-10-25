#Requires -Version 7
param(
    [string[]]$Tareas,
    [switch]$Release,
    [switch]$Prueba
)

if (-not $Tareas) {
    if ($Release) {
        $Tareas = @(':assemblePhoneRelease')
    } else {
        $Tareas = @(':assemblePhoneDebug')
    }
}

$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'Este script es para Windows. En Linux/macOS usa ./gradlew.' }

$savedEnvironment = @{}
foreach ($name in @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'GRADLE_USER_HOME', 'JAVA_TOOL_OPTIONS', 'Path')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$temporaryDrive = $null
$locationPushed = $false
try {
    $localEnvironmentFile = Join-Path $PSScriptRoot 'entorno.local.json'
    if (Test-Path -LiteralPath $localEnvironmentFile) {
        $localEnvironment = Get-Content -LiteralPath $localEnvironmentFile -Raw | ConvertFrom-Json -AsHashtable
        if ($localEnvironment -isnot [System.Collections.IDictionary]) {
            throw 'entorno.local.json debe contener un objeto con las rutas del entorno.'
        }
        foreach ($name in @('JAVA_HOME', 'ANDROID_HOME', 'GRADLE_USER_HOME')) {
            if (-not $localEnvironment.ContainsKey($name) -or $localEnvironment[$name] -isnot [string] -or
                [string]::IsNullOrWhiteSpace($localEnvironment[$name]) -or
                -not [System.IO.Path]::IsPathFullyQualified($localEnvironment[$name])) {
                throw "entorno.local.json debe definir una ruta absoluta para $name."
            }
        }
        if (-not (Test-Path -LiteralPath (Join-Path $localEnvironment['JAVA_HOME'] 'bin/java.exe'))) {
            throw 'El JDK de entorno.local.json no existe.'
        }
        if (-not (Test-Path -LiteralPath $localEnvironment['ANDROID_HOME'] -PathType Container)) {
            throw 'El SDK de entorno.local.json no existe.'
        }
        foreach ($name in @('JAVA_HOME', 'ANDROID_HOME', 'GRADLE_USER_HOME')) {
            [Environment]::SetEnvironmentVariable($name, $localEnvironment[$name], 'Process')
        }
        $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
        if ($localEnvironment.ContainsKey('JAVA_TOOL_OPTIONS')) {
            if ($localEnvironment['JAVA_TOOL_OPTIONS'] -isnot [string]) {
                throw 'JAVA_TOOL_OPTIONS en entorno.local.json debe ser una cadena.'
            }
            $env:JAVA_TOOL_OPTIONS = $localEnvironment['JAVA_TOOL_OPTIONS']
        }
    }

    # Una terminal abierta puede conservar variables anteriores a la instalación del SDK/JDK.
    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) {
        $env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
    }
    if (-not $env:ANDROID_HOME -or -not (Test-Path -LiteralPath $env:ANDROID_HOME)) {
        $env:ANDROID_HOME = [Environment]::GetEnvironmentVariable('ANDROID_HOME', 'User')
    }
    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) {
        throw 'Configura JAVA_HOME con la carpeta de un JDK instalado.'
    }
    if (-not $env:ANDROID_HOME -or -not (Test-Path -LiteralPath $env:ANDROID_HOME)) {
        throw 'Configura ANDROID_HOME con la carpeta del Android SDK.'
    }

    $env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + (Join-Path $env:ANDROID_HOME 'platform-tools') + ';' + $env:Path
    $buildRoot = $PSScriptRoot
    if ($buildRoot -match '\s') {
        # ndk-build no admite espacios en las rutas de sus archivos Make.
        foreach ($driveLetter in @('T', 'W', 'V', 'U', 'S', 'R', 'Q', 'P', 'O', 'N')) {
            $driveName = $driveLetter + ':'
            if (-not (Test-Path -LiteralPath ($driveName + '\')) -and -not (Get-PSDrive -Name $driveLetter -ErrorAction SilentlyContinue)) {
                & subst.exe $driveName $buildRoot
                if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear la ruta temporal de compilación.' }
                $temporaryDrive = $driveName
                $buildRoot = $driveName + '\'
                break
            }
        }
        if (-not $temporaryDrive) { throw 'No hay una letra de unidad libre para compilar sin espacios en la ruta.' }
    }

    $gradleArguments = @($Tareas)
    if ($Prueba) { $gradleArguments += '-PcanTalkTestBuild=true' }
    Push-Location $buildRoot
    $locationPushed = $true
    & .\gradlew.bat @gradleArguments --console=plain --max-workers=1 --no-daemon '-Dorg.gradle.jvmargs=-Xms256m -Xmx2g' '-Pkotlin.compiler.execution.strategy=in-process'
    if ($LASTEXITCODE -ne 0) { throw "Gradle terminó con el código $LASTEXITCODE." }
} finally {
    if ($locationPushed) { Pop-Location }
    if ($temporaryDrive) {
        # Desmonta solamente la unidad virtual; los archivos permanecen en la carpeta original.
        & subst.exe $temporaryDrive /D
    }
    foreach ($name in $savedEnvironment.Keys) {
        if ($null -eq $savedEnvironment[$name]) {
            Remove-Item -LiteralPath "Env:\$name" -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
        }
    }
}
