# Preparar el primer lanzamiento de CAN Talk

Estado: preparación en la rama `actualizador`. No se ha generado la clave oficial,
compilado un APK con `can.cantalk`, instalado una nueva versión ni publicado un
lanzamiento como parte de estos preparativos.

## Identidad y versión

- Aplicación oficial de teléfono: `can.cantalk`.
- Wear OS queda fuera del candidato: variantes desactivadas por defecto y código
  conservado. Solo se reactivan expresamente con `-PcanTalkWearBuild=true`.
- Aplicación independiente de pruebas: `can.cantalk.cantalktest`.
- Nombre visible: can-talk.
- Versión elegida: `1.0.0-rc.1`, candidato previo a la primera versión definitiva.
  Está guardada en `version.gradle`; los APK debug añaden `-dev-` y la fecha.
- Notas iniciales: [borrador del primer lanzamiento](docs/notas-primer-lanzamiento.md).
- `versionCode`: se conserva la base `70000000` más el número de commits para
  respetar las migraciones heredadas. No debe reiniciarse en 1. Las siguientes
  publicaciones deben tener un número mayor; el canal actual también compara commits.

Las versiones previas con `fyi.quin.backtalk` o su variante de prueba permanecen
como aplicaciones distintas. Sus ajustes pueden trasladarse mediante exportación
e importación de una copia de CAN Talk. Hay que habilitar por separado el lector,
el teclado braille y los accesos de accesibilidad con el identificador nuevo.

## Firma y colaboradores

Para el primer lanzamiento se ha elegido un responsable que compila y firma
localmente y una segunda persona que custodia una copia de recuperación.
Los demás colaboradores pueden revisar código, ejecutar pruebas y generar APK
de desarrollo sin recibir la clave.
Falta asignar las personas concretas, crear la clave y comprobar su recuperación.
La firma en GitHub Actions se deja como alternativa futura, sin configurar sus
secretos para este lanzamiento.

La clave `keystore/debug.keystore` está versionada y su contraseña es conocida:
solo sirve para desarrollo. Los APK oficiales requieren otra clave privada,
guardada fuera del checkout y con copia cifrada en un lugar independiente.
La clave y las contraseñas se entregan a los custodios por un gestor de secretos,
no por Git ni por una conversación del proyecto. Conservar también alias,
contraseña, huella SHA-256 del certificado y procedimiento de recuperación.

Cuando los responsables estén definidos, uno puede crear el almacén con el
`keytool` del JDK. Este comando es una instrucción pendiente; no se ha ejecutado:

```powershell
# La carpeta elegida debe estar fuera del repositorio y existir previamente.
keytool -genkeypair -keystore C:\Claves\CAN-Talk\cantalk-release.p12 -storetype PKCS12 -alias cantalk -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` solicita la contraseña y los datos del titular. En PKCS12 se usa la
misma contraseña para el almacén y la clave. Verificar la copia de recuperación
antes de distribuir el primer APK. No cambiar de clave entre actualizaciones.

Referencia: [firma de aplicaciones Android](https://developer.android.com/studio/publish/app-signing).

## Compilación local oficial: pendiente de autorización para compilar

En PowerShell 7, cargar las credenciales solo en la sesión de compilación:

```powershell
$env:CANTALK_KEYSTORE_PATH = 'C:\Claves\CAN-Talk\cantalk-release.p12'
$env:CANTALK_KEY_ALIAS = 'cantalk'
$env:CANTALK_STORE_PASSWORD = Read-Host 'Contraseña del almacén' -MaskInput
$env:CANTALK_KEY_PASSWORD = $env:CANTALK_STORE_PASSWORD
try {
    .\compilar.ps1 -Release
} finally {
    Remove-Item Env:CANTALK_KEYSTORE_PATH, Env:CANTALK_KEY_ALIAS, Env:CANTALK_STORE_PASSWORD, Env:CANTALK_KEY_PASSWORD -ErrorAction SilentlyContinue
}
```

El ejemplo genera `build/outputs/apk/phone/release/cantalk-phone-release.apk`,
con versión `1.0.0-rc.1`. No usar `-Prueba` para este APK.
Las compilaciones de desarrollo con el mismo `can.cantalk` tienen otra firma y
no pueden actualizar una instalación oficial; para pruebas independientes usar
`-Prueba`.

## Candidato mediante GitHub Actions

Alternativa futura: el procedimiento elegido para este candidato es la firma local.

El flujo manual **Release candidate** queda preparado para `master`. No se ejecuta
por push ni por pull request. Requiere estos secretos de Actions:

| Secreto | Contenido |
| --- | --- |
| `CANTALK_KEYSTORE_BASE64` | Archivo del almacén codificado en Base64. |
| `CANTALK_STORE_PASSWORD` | Contraseña del almacén. |
| `CANTALK_KEY_ALIAS` | Alias de la clave, por ejemplo `cantalk`. |
| `CANTALK_KEY_PASSWORD` | Contraseña de la clave. |

Base64 no cifra la clave: su protección depende de los secretos y permisos de
GitHub. Configurarlos solo cuando se decida trasladar la firma al CI; no es
necesario para que compile localmente un responsable.
[Documentación de secretos de Actions](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).

El flujo ejecuta pruebas de teléfono y lint, genera el APK oficial de teléfono y
verifica su firma con `apksigner`. Entrega `cantalk.apk`, su suma SHA-256, datos
del commit y certificado dentro de un artefacto descargable. No crea ni modifica
releases. El flujo habitual **Build** conserva la verificación de
desarrollo y deja de publicar APK debug automáticamente en `dev`.

## Verificaciones pendientes antes de distribuir

- Definir custodios, crear la clave y comprobar la copia de recuperación.
- Completar las notas del candidato `1.0.0-rc.1` con los resultados de sus pruebas.
- Resolver el lint pendiente: la última ejecución previa a estos cambios informó
  509 errores y un fallo del generador SARIF. El candidato está bloqueado hasta
  que pruebas y lint terminen correctamente; no se han repetido ni omitido aquí.
- Compilar y verificar paquete, versión, firma y SHA-256 del APK nuevo de teléfono.
- Probar en teléfono el arranque, activación, navegación y gestos, voz en español,
  teclado braille, copias de ajustes, NVDA Remote y actualización con la misma firma.
- Revisar avisos de privacidad, licencias y atribuciones que se distribuirán.
- Elegir dónde entregar el APK y cómo recibir futuras actualizaciones: el
  repositorio permanece privado y el actualizador no lleva credenciales. No
  puede descargar sus releases sin un mecanismo de acceso adecuado. El canal
  configurado sigue siendo `dev`; queda por definir un canal estable.

Las verificaciones de APK y dispositivo anteriores corresponden a paquetes con
el identificador antiguo. No demuestran que el nuevo candidato esté listo.

## Validación de estos preparativos

- `gradlew help --offline`: configuración de Gradle correcta; solo se ejecutó
  la tarea de ayuda. Antes de suspender Wear OS, una comprobación de las cuatro variantes confirmó
  `can.cantalk`, teléfono release `1.0.0-rc.1`, reloj release
  `1.0.0-rc.1-wear` y sufijo `-dev-` con fecha para ambos APK debug.
- `assemblePhoneRelease --dry-run --offline -PcanTalkReleaseVersion=1.0.0-rc.1`:
  rechazado antes de ejecutar tareas porque faltan las cuatro variables de firma,
  como se esperaba. No se generó un APK.
- Ambos workflows: YAML y sintaxis Bash válidos; permisos de contenido solo de
  lectura y sin pasos de publicación. No se ejecutaron en GitHub.
- `git diff --check`: sin errores de espacios.
- Tras suspender Wear OS, `assembleDebug --dry-run --offline` confirmó que siguen
  disponibles las variantes de teléfono y no existen tareas `assembleWearDebug`
  ni `assembleWearRelease`. El grafo no contiene tareas Wear; todas las tareas
  de compilación se omitieron y no se generó ningún APK.

Estas comprobaciones no sustituyen una compilación real, las pruebas de unidad,
lint ni las pruebas en dispositivos de los APK oficiales.

## Formato obligatorio de Releases en GitHub

El actualizador integrado en la aplicación (`UpdateChecker.kt`) requiere que el texto de cada release en GitHub comience obligatoriamente con el número de compilación y la lista de commits. Si se omite, el actualizador fallará con un error de JSON al buscar actualizaciones.

### Estructura del cuerpo de la release:
1. **Línea de build**: `build: <número_de_commits>` (ej. `git rev-list --count HEAD`).
2. **Commits recientes**: Lista de cambios con el prefijo `- <hash> <mensaje>`.
3. **Notas en español**: Todas las descripciones y notas de versión deben estar redactadas en **español**, detallando novedades, correcciones y cambios relevantes para los usuarios.
4. **Sincronización `dev`**: Toda nueva versión publicada debe actualizar también la release con tag `dev`, que es el canal consultado por defecto por el actualizador dentro de la aplicación.

