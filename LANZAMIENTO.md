# Compilar y publicar una nueva release de CAN Talk

Esta es la guía operativa del flujo **actual, local y manual**. Las reglas del
actualizador están en [AGENTS.md](AGENTS.md); la preparación del entorno está en
[COMPILACION.md](COMPILACION.md). GitHub Actions todavía no publica releases.

## Qué se distribuye

| Propiedad | Valor que se debe conservar |
| --- | --- |
| Variante y tarea | `phoneDebug`, `:assemblePhoneDebug` |
| Paquete | `can.cantalk` |
| Firma | La compartida de `keystore/debug.keystore`, igual a las instalaciones 1.0.x. |
| APK de salida | `build/outputs/apk/phone/debug/cantalk-phone-debug.apk` |
| Ejecución | Minificada y no depurable, aunque la variante se llame debug. |
| Versión visible | La definida en `version.gradle`, sin sufijo automático de fecha. |
| Código Android | `70000000 + git rev-list --count HEAD`; debe superar al publicado. |
| Alcance | Teléfono; no se genera ni distribuye APK Wear OS. |

**No usar `-Prueba`, `-Release` ni una clave nueva para esta distribución.** La
variante privada `release` no es el flujo de actualización vigente. No modificar
traducción, sus claves ni otras funciones para preparar una release.

Los comandos de compilación y publicación siguientes tienen efectos reales:
ejecutarlos solo con autorización para esas acciones, desde la raíz del repo en
PowerShell 7 para Windows. Esta guía no autoriza por sí sola un lanzamiento.

## 1. Comprobar el entorno y elegir la versión

- JDK/SDK y licencias preparados según [COMPILACION.md](COMPILACION.md).
- Git con historial completo; no preparar versiones desde un clon superficial.
- `origin` debe ser `marcomolinaleija/can-talk`, no el upstream Backtalk.
- `gh` autenticado con permisos de escritura para publicar en ese repositorio.
- Para **el publicador actual**, `entorno.local.json` es obligatorio: lee de ahí
  `JAVA_HOME` y `ANDROID_HOME` y utiliza herramientas `.exe`/`.bat`. Incluir también
  `GRADLE_USER_HOME`, como exige `compilar.ps1` al cargar ese archivo. No sustituir
  configuraciones existentes ni incorporar secretos a Git.

```powershell
$Repo = 'marcomolinaleija/can-talk'
$Version = '1.0.4' # Solo ejemplo: elegir una 1.0.x nueva, superior a la publicada.
git status -sb
git remote -v
git fetch origin --tags
gh auth status
gh release view --repo $Repo --json tagName,url
```

Cada orden debe terminar correctamente antes de avanzar. Elegir la rama/commit
aprobados; no fusionar con `master` ni cambiar de rama automáticamente. Los
permisos o reglas de tags de GitHub también deben permitir la publicación.

## 2. Preparar versión y notas, sin crear todavía el tag

Actualizar estos archivos para la misma versión elegida:

| Archivo | Cambio |
| --- | --- |
| `version.gradle` | `talkbackVersionName = "<versión elegida>"`. |
| `talkback/src/main/res/raw/release_notes.txt` | Primera línea `CAN Talk <versión elegida>` y novedades reales en español. |
| `talkback/src/main/res/values/strings_update.xml` | Ejemplo de versión en `summary_pref_check_for_updates`. |
| `talkback/src/main/res/values-es/strings_update.xml` | Mismo ejemplo. |
| `talkback/src/main/res/values-b+es+419/strings_update.xml` | Mismo ejemplo. |
| `talkback/src/main/res/values-cs/strings_update.xml` | Mismo ejemplo. |
| `lint-baseline.xml` | Solo la referencia de ese ejemplo de versión; no regenerar el baseline para ocultar fallos. |

El TXT es la fuente única de las notas locales del APK, del cuerpo de la release
estable y del asset `release-notes.txt`. No escribir `build:` ni listas de hashes
en ese archivo: el publicador añade la compatibilidad antigua únicamente a `dev`.
No anunciar como resuelto un caso de dispositivo que no se haya comprobado.

El publicador admite actualmente versiones estables con formato `1.0.<número>`.
No acepta reutilizar una versión/tag remoto, prereleases ni otra serie mayor sin
adaptar expresamente el procedimiento. `1.0.3` es una versión ya publicada, no un
comando de ejemplo para volver a publicar.

## 3. Validar y confirmar los cambios

Ejecutar por separado para poder distinguir los resultados:

```powershell
.\compilar.ps1 -Tareas @('testPhoneDebugUnitTest')
.\compilar.ps1 -Tareas @(':lintPhoneDebug')
```

Una prueba o compilación fallida se investiga antes de distribuir. Lint tampoco
se debe silenciar: la revisión 1.0.3 registró deuda global en la
[auditoría](docs/auditoria-actualizador-1.0.3.md). Si sigue fallando, revisar el
informe, separar regresiones de deuda conocida y obtener una decisión explícita
del responsable de publicación; no afirmar que está verde ni ampliar el baseline
como atajo.

Revisar el diff y preparar con `git add --` **solo las rutas revisadas de la
release**, sin incluir cambios ajenos. Después:

```powershell
git diff --cached
git commit -m "chore(release): preparar la versión $Version"
git status --short
git rev-parse HEAD
git rev-list --count HEAD
```

El árbol debe quedar limpio. **Confirmar antes de compilar el APK definitivo**:
Git determina el código de versión. No añadir commits, cambiar de rama ni alterar
fuentes entre la compilación definitiva y la publicación. Si eso ocurre, volver
a validar y compilar; no reutilizar el APK anterior.

## 4. Compilar el APK definitivo y comprobar la firma

```powershell
pwsh -File .\compilar.ps1
```

No pasar `-Prueba` ni `-Release`. Si la caché o los temporales locales fallan,
diagnosticar el entorno sin borrar cachés ni cambiar variables globales de otros
proyectos. Una compilación fallida puede dejar un APK viejo en la carpeta de
salida: su presencia no demuestra éxito.

Con el SDK/JDK de `entorno.local.json`, comprobar el APK generado:

```powershell
$Local = Get-Content -LiteralPath .\entorno.local.json -Raw | ConvertFrom-Json
$BuildTools = Get-ChildItem -LiteralPath (Join-Path $Local.ANDROID_HOME 'build-tools') -Directory |
    Sort-Object Name -Descending | Select-Object -First 1
$Apk = Join-Path $PWD 'build/outputs/apk/phone/debug/cantalk-phone-debug.apk'
$PreviousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $Local.JAVA_HOME
    & (Join-Path $BuildTools.FullName 'apksigner.bat') verify --verbose --print-certs $Apk
    if ($LASTEXITCODE -ne 0) { throw 'La firma del APK no es válida.' }
} finally {
    $env:JAVA_HOME = $PreviousJavaHome
}
& (Join-Path $BuildTools.FullName 'aapt2.exe') dump badging $Apk
if ($LASTEXITCODE -ne 0) { throw 'No se pudo leer la identidad del APK.' }
Get-FileHash -LiteralPath $Apk -Algorithm SHA256
```

La firma debe ser válida **y coincidir con la versión distribuida anteriormente**;
`apksigner verify` por sí solo no compara certificados. La huella SHA-256 del
certificado de la distribución 1.0.3 es:

```text
bc1c2f5262af92bc033b9085a4c50ea33cbc30cf9d8220fcec1e99a852417c5a
```

No confundir esa huella del certificado con el SHA-256 del archivo APK, que cambia
en cada entrega. Verificar además `can.cantalk`, versión elegida, código esperado
y ausencia de `application-debuggable` en el badging. Si cambia la firma, detener
la publicación: no pedir a los usuarios desinstalar para ocultar la incompatibilidad.

## 5. Preparar los assets sin publicar

```powershell
pwsh -File .\publicar-release.ps1 -Version $Version -SkipBuild -SoloPreparar
```

Esta previsualización consulta GitHub y escribe archivos locales, pero **no compila,
no crea/mueve tags ni publica** gracias a la combinación de esos dos switches.
Revisar `build/releases/<versión>/`:

| Archivo generado | Uso |
| --- | --- |
| `cantalk.apk` | Copia del APK de salida de Gradle. |
| `update.json` | Esquema 1: commit y `apks.cantalk.apk` con versionCode, versionName y sha256. |
| `release-notes.txt` | Notas editoriales canónicas; asset y cuerpo estable. |
| `dev-notes.txt` | Cuerpo de compatibilidad: build, resumen con hash y notas con viñetas adaptadas. No se sube como asset. |

Comprobar que JSON, APK y commit coinciden y que las notas son correctas. El
publicador actual genera un resumen legacy fijo que menciona actualizador y voz:
revisar su pertinencia para futuras versiones; no asumir que resume automáticamente
sus cambios. Si ese resumen no corresponde a la versión, resolver el ajuste del
publicador antes de continuar. Editar únicamente `dev-notes.txt` no lo corrige:
el script lo regenera al publicar. `-ArchivoNotas` solo acepta texto coincidente
con el TXT canónico.

La previsualización valida árbol limpio, avance de versión/código, ausencia del
tag remoto y metadatos/firma del APK. No sustituye comparar el certificado con
el anterior ni probar el lector en un teléfono.

## 6. Hacer push y publicar

Solo con autorización de entrega, y comprobando que cada orden termine sin error:

```powershell
git push origin HEAD
pwsh -File .\publicar-release.ps1 -Version $Version -SkipBuild
```

El push envía la rama actual; el script no lo realiza: es una acción separada.
El publicador vuelve a validar los datos y realiza, en orden:

1. Crear el tag semántico anotado y subirlo a `origin`, sin forzarlo.
2. Crear una release **en borrador** y subir APK, JSON y TXT.
3. Publicar la release estable y marcarla como Latest.
4. Mover únicamente el tag `dev` al mismo commit.
5. Subir los mismos tres assets a `dev` y actualizar su cuerpo como pre-release.

Sin `-SkipBuild`, el publicador compila primero. No crear el tag ni la release a
mano antes del camino habitual: el script rechaza tags remotos ya existentes.
Nunca usar `--clobber` para reemplazar un APK estable ya publicado.

## 7. Verificar la entrega pública y el teléfono

```powershell
gh release view $Version --repo $Repo --json url,isDraft,isPrerelease,assets
gh release view dev --repo $Repo --json url,isDraft,isPrerelease,assets
git ls-remote origin "refs/tags/$Version*" 'refs/tags/dev*'
```

- La estable debe estar publicada, no ser pre-release y aparecer en `/releases/latest`.
- Los tres assets deben estar subidos. APK, JSON y TXT deben coincidir entre ambos
  canales; el JSON debe describir el APK y el commit del tag. En tags anotados,
  comparar el commit de la referencia terminada en `^{}`, no el objeto tag.
- Consultar la API y descargar los archivos **sin credenciales** para comprobar el
  acceso que tendrá la app. Contrastar el SHA-256 descargado con el JSON y el local.
- Desde una versión anterior: buscar, revisar el aviso, permitir instalar apps si
  Android lo solicita, instalar y volver a buscar para comprobar que no se reofrece.
- Abrir Novedades sin conexión y probar voz/navegación. Las pruebas unitarias no
  certifican el comportamiento real ni resuelven por sí solas casos de fabricante.

## Recuperación si se corta la publicación

**No volver a ejecutar todo el script a ciegas.** Puede haberse subido el tag,
publicado la estable o actualizado solo parte de `dev`. Inspeccionar primero las
releases, tags y los archivos conservados en `build/releases/<versión>/`.

| Estado remoto comprobado | Acción |
| --- | --- |
| No existe el tag remoto ni release. | Corregir la causa y repetir el flujo. Si cambió el código/commit, recompilar. |
| Tag existente, pero no hay release o quedó un borrador incompleto. | Verificar que tag, APK, JSON y notas son los originales; completar con `gh` ese tag/borrador. El camino normal del script se detendrá por el tag existente. |
| La estable ya está publicada y falta sincronizar `dev`. | No tocar la estable. Completar únicamente tag móvil, assets y cuerpo de `dev` con los artefactos originales. Comprobar antes que no se publicó una versión más reciente. |
| Se detectó un APK incorrecto en una estable publicada. | Detener la distribución y preparar una corrección con versión/código mayores; no reutilizar el tag ni reemplazar silenciosamente el APK. |

No sincronizar dos publicaciones al mismo tiempo: una ejecución antigua podría
pisar `dev`. No reconstruir un APK diferente para reparar la misma versión. Si
no se conservan los originales, no improvisar una recuperación sobre el tag estable.

## Límites del flujo actual

- El actualizador 1.0.3+ consume `/releases/latest` y `update.json`; el cuerpo es
  solo texto editorial. Se conserva `dev` para los clientes 1.0.0–1.0.2.
- La firma compartida es pública. El checksum detecta inconsistencias, no sustituye
  una clave privada ni protege una cuenta de publicación comprometida. Cualquier
  migración de firma se planifica aparte, sin romper las actualizaciones existentes.
- Los workflows actuales de Actions generan artefactos y no publican releases.
  El publicador de Windows no es todavía un flujo automatizado de GitHub.
