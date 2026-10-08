# Compilar Can Talk

Esta guía permite compilar el lector y su teclado braille desde una copia del repositorio. Los comandos de Windows se verificaron con PowerShell 7, Temurin JDK 21 y un Android SDK local. Para empezar a probar en un teléfono que ya tenga BackTalk instalado, usa la compilación independiente.

## Requisitos

| Componente | Versión o requisito |
| --- | --- |
| Git | Disponible en la terminal; clonar con el historial completo |
| Java | JDK 17 o posterior; JDK 21 verificado en Windows |
| Android SDK | Command-line Tools, Platform Tools y plataforma `android-37.0` (API 37) |
| Build Tools | `36.0.0`, seleccionada por el proyecto |
| Android NDK | `27.3.13750724` |
| PowerShell | 7 o posterior para `compilar.ps1` |
| Teléfono | Android 8.0/API 26 o posterior; depuración USB para instalar con ADB |

Gradle se descarga mediante el wrapper incluido (`gradlew`/`gradlew.bat`); no se instala por separado. Su versión y checksum están en `gradle/wrapper/gradle-wrapper.properties`. Las versiones de las bibliotecas y plugins están en `gradle/libs.versions.toml`. La primera compilación necesita Internet para descargar dependencias y puede tardar varios minutos.

## Obtener el código por SSH

```powershell
git clone git@github.com:marcomolinaleija/can-talk.git
cd can-talk
git remote add upstream git@github.com:trypsynth/backtalk.git
git fetch upstream
```

`origin` es el repositorio de Can Talk y `upstream` es BackTalk. La rama principal es `master`. Para comenzar otro cambio:

```powershell
git switch master
git pull --ff-only origin master
git switch -c codex/nombre-del-cambio
```

## Preparar Windows

Instala un JDK y el Android SDK mediante Android Studio o sus Command-line Tools. Configura `JAVA_HOME` con la carpeta del JDK y `ANDROID_HOME` con la raíz del SDK. Por ejemplo, para esta sesión, sustituyendo la ruta del JDK por la tuya:

```powershell
$env:JAVA_HOME = 'C:\ruta\al\jdk-21'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
```

Para conservar esas rutas en futuras terminales:

```powershell
[Environment]::SetEnvironmentVariable('JAVA_HOME', $env:JAVA_HOME, 'User')
[Environment]::SetEnvironmentVariable('ANDROID_HOME', $env:ANDROID_HOME, 'User')
```

Con las Command-line Tools en `cmdline-tools/latest`, instala los componentes y acepta sus licencias:

```powershell
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" 'platform-tools' 'platforms;android-37.0' 'build-tools;36.0.0' 'ndk;27.3.13750724'
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
```

Si tu instalación tiene las Command-line Tools en otra carpeta, adapta esa ruta. El nombre de la plataforma es `platforms;android-37.0`, no `platforms;android-37`.

## Compilar en Windows

Desde la raíz del repositorio:

```powershell
# APK habitual, con el mismo identificador que BackTalk.
.\compilar.ps1

# APK independiente para convivir con el BackTalk instalado.
.\compilar.ps1 -Prueba

# Pruebas del teclado, compilación de teléfono y análisis estático.
.\compilar.ps1 -Prueba -Tareas @(':brailleime:testPhoneDebugUnitTest', ':assemblePhoneDebug', ':lintPhoneDebug')

# Compilación para Wear OS.
.\compilar.ps1 -Tareas @(':assembleWearDebug')
```

El script limita Gradle a 2 GB y un proceso de trabajo. Si la ruta del repositorio contiene espacios, crea una unidad virtual temporal con `subst` y la desmonta al finalizar, incluso si Gradle falla. No mueve el repositorio. También carga las rutas guardadas para el usuario cuando la terminal conserva una configuración inválida.

Si PowerShell bloquea el script por su política de ejecución, puedes ejecutarlo solo en ese proceso con:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\compilar.ps1 -Prueba
```

También se puede invocar directamente `gradlew.bat`, siempre que la ruta del repositorio no contenga espacios y el SDK/JDK estén configurados. El script evita el problema de `ndk-build` con los espacios y el límite de memoria de 8 GB que tiene `gradle.properties`.

## Linux o macOS

Instala los mismos componentes del SDK y configura Java. Trabaja en una carpeta sin espacios:

```bash
export JAVA_HOME=/ruta/al/jdk
export ANDROID_HOME=/ruta/al/Android/Sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew :assemblePhoneDebug -PcanTalkTestBuild=true --console=plain --max-workers=1 --no-daemon '-Dorg.gradle.jvmargs=-Xms256m -Xmx2g' -Pkotlin.compiler.execution.strategy=in-process
./gradlew :brailleime:testPhoneDebugUnitTest :lintPhoneDebug -PcanTalkTestBuild=true --console=plain --max-workers=1 '-Dorg.gradle.jvmargs=-Xms256m -Xmx2g'
```

Omite `-PcanTalkTestBuild=true` para compilar el paquete habitual. `build.sh` es otra opción para compilar teléfono y reloj juntos: requiere `ANDROID_SDK` y añade la ruta del SDK a `local.properties` si falta.

## APKs y firma

| Compilación | Identificador | Nombre visible |
| --- | --- | --- |
| Habitual | `fyi.quin.backtalk` | Backtalk |
| Con `-Prueba` | `fyi.quin.backtalk.cantalktest` | Can Talk — prueba |

Ambas compilaciones escriben el APK de teléfono en `build/outputs/apk/phone/debug/backtalk-phone-debug.apk`: una sustituye el archivo generado por la otra. El de reloj queda en `build/outputs/apk/wear/debug/backtalk-wear-debug.apk`. Comprueba qué variante compilaste antes de instalar.

Los APKs se firman con la clave debug local de Android, normalmente en `$HOME/.android/debug.keystore`. El tipo de build se llama `debug`, aunque el proyecto desactiva `debuggable` para mejorar el rendimiento del lector. El número de versión depende del número de commits y el nombre incluye la fecha de compilación.

Android solo permite actualizar un paquete con la misma firma. Conserva la clave local para que tus siguientes APKs actualicen tu instalación. Si aparece `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, compila con `-Prueba` para instalar junto al BackTalk existente. Esa variante tiene ajustes y permisos propios. No es necesario desinstalar el lector actual.

Para comprobar la firma en Windows:

```powershell
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --verbose .\build\outputs\apk\phone\debug\backtalk-phone-debug.apk
```

## Instalar por ADB

Conecta el teléfono, desbloquéalo y acepta «Permitir depuración USB». Comprueba la conexión:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
```

Debe aparecer como `device`. Si aparece `unauthorized`, falta aceptar la autorización en el teléfono. Si no aparece el aviso, vuelve a conectar el cable; si persiste, revoca las autorizaciones de depuración USB en las opciones de desarrollador y reconecta.

Instala el APK, sustituyendo `SERIAL` por el identificador mostrado:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" -s SERIAL install -r .\build\outputs\apk\phone\debug\backtalk-phone-debug.apk
& "$env:ANDROID_HOME\platform-tools\adb.exe" -s SERIAL shell dumpsys package fyi.quin.backtalk.cantalktest
```

`-r` conserva los datos al actualizar un paquete con firma compatible. Para verificar el paquete habitual, usa `fyi.quin.backtalk` en el último comando. En Linux/macOS, usa los mismos argumentos con `adb`.

## Comprobar el teclado braille

1. Activa el lector de prueba en Accesibilidad y deja solo ese lector activo durante la prueba.
2. Configura y selecciona «Teclado braille de Can Talk — prueba».
3. En los ajustes del teclado braille, activa «Mantener el teclado abierto al enviar mensajes». Está activada por defecto.
4. En WhatsApp, escribe y envía varios mensajes seguidos, sin añadir un espacio final. El teclado debe permanecer abierto y el texto del mensaje anterior no debe reaparecer.
5. Desactiva la opción y comprueba que el teclado se cierre al enviar. El gesto de ocultarlo debe funcionar en ambos modos.

La opción se aplica cuando la aplicación declara `IME_ACTION_SEND`. Las demás acciones conservan el cierre habitual. La memoria braille se confirma y limpia antes de enviar en ambos modos; la aplicación receptora vacía su campo. El anuncio de activación en español es «can-talk está activo».

Las pruebas del módulo braille están en `braille/brailleime/build/reports/tests/testPhoneDebugUnitTest/index.html`; el informe de lint está en `build/reports/lint-results-phoneDebug.html`. Los APKs, `local.properties`, las claves y los registros locales no deben añadirse a Git.

## Automatización en GitHub

El workflow `.github/workflows/build.yml` compila teléfono y reloj, ejecuta las pruebas de teléfono y lint. En un push a `master`, también publica el prerelease `dev` de este repositorio si las verificaciones terminan correctamente. Para que los APKs de ese workflow conserven una firma estable, configura su secreto `DEBUG_KEYSTORE_BASE64` con una clave de firma controlada por el proyecto. Sin ese secreto, el runner genera una clave nueva.
