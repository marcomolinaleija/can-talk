#Requires -Version 7
param(
    [string[]]$Tareas = @(':assemblePhoneDebug'),
    [switch]$Prueba
)

$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'Este script es para Windows. En Linux/macOS usa ./gradlew.' }

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
$temporaryDrive = $null
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
try {
    & .\gradlew.bat @gradleArguments --console=plain --max-workers=1 --no-daemon '-Dorg.gradle.jvmargs=-Xms256m -Xmx2g' '-Pkotlin.compiler.execution.strategy=in-process'
    if ($LASTEXITCODE -ne 0) { throw "Gradle terminó con el código $LASTEXITCODE." }
} finally {
    Pop-Location
    if ($temporaryDrive) {
        # Desmonta solamente la unidad virtual; los archivos permanecen en la carpeta original.
        & subst.exe $temporaryDrive /D
    }
}
