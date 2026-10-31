# Auditoría del actualizador de CAN Talk 1.0.3

Alcance: búsqueda manual y automática, notas de actualización, Novedades, descarga,
validación del APK y publicación de versiones. Se revisó además la incorporación
de las correcciones de TTS de Marco (`32df7a7b`). No es una auditoría integral de
cada función del lector ni una certificación de accesibilidad o seguridad.

## Hallazgos y cambios

| Hallazgo | Resultado |
| --- | --- |
| Ya se usaba GitHub REST, pero `build:` y viñetas del body controlaban la detección. | `releases/latest` + asset estructurado `update.json`; las notas no determinan la actualización. |
| Se quitaba el primer token de cada viñeta y se limitaban las notas según commits. | Se muestra todo el body editorial, con párrafos y viñetas intactos; sin notas también se puede actualizar. |
| Había un handler de Novedades de TalkBack, pero ninguna entrada en el menú vigente. | Nueva entrada Novedades en ajustes, con versión visible y texto local del APK. |
| Cada reinicio del servicio saltaba el intervalo diario. | El arranque respeta la última comprobación correcta; la búsqueda manual sigue siendo inmediata. |
| La descarga no vinculaba el archivo a la versión anunciada. | SHA-256 obligatorio y comprobación de versionCode antes de crear la sesión de instalación. Se conserva la validación de paquete/firma. |
| El script sobrescribía tags semánticos, toleraba errores nativos y elegía APK suelto con `-SkipBuild`. | Validación de árbol/versión/APK, errores terminantes, tags semánticos inmutables y borrador con assets antes de publicar. |
| Documentación de lanzamiento describía el candidato 1.0.0-rc.1 y contradecía la distribución actual. | Reglas y guías actualizadas para 1.0.x, firma compatible y contrato JSON. |

## Contrato y retrocompatibilidad

El esquema 1 de `update.json` incluye commit y un objeto `apks` con versión,
código y SHA-256 de cada APK. La URL del APK se toma de los assets de la API,
no del JSON ni del body; debe ser HTTPS y corresponder al mismo repositorio y tag.
Un reloj nunca recibe el APK de teléfono. No se distribuye Wear OS en esta entrega.

La release estable usa exactamente `release_notes.txt` como cuerpo. `dev` mantiene
la cabecera antigua y un resumen español con hash para que 1.0.0–1.0.2 puedan
llegar a esta versión. La app nueva deja de depender del tag móvil `dev`.
La API no lleva tokens; una respuesta 403, 404, 429, error de red o metadatos
incompletos no se presenta como «está actualizado».

Referencia del contrato REST: [GitHub Releases](https://docs.github.com/en/rest/releases/releases)

## TTS y firma

Se conservan las correcciones de Marco para callbacks sin ID, identidad propia
de anuncios en espera, continuación tras errores de caché y parámetros separados.
No se incorporan perfiles de voz ni otras funciones de upstream. El detalle está
en `docs/tts-estabilidad-upstream.md`.

Se conserva la firma compartida de las instalaciones 1.0.x. La clave es pública:
el checksum no reemplaza una estrategia de firma privada. Cambiarla ahora impediría
actualizar las instalaciones existentes; su migración queda fuera de este cambio.

## Validación

- `testPhoneDebugUnitTest`: 1223 pruebas, sin fallos, errores ni omisiones
  (TalkBack 823, utils 251 y teclado braille 149).
- Incluye 23 casos del actualizador y 16 ejecuciones de las regresiones de TTS
  de Marco, en Android 10 y 14. Se comprobaron el clic real en Novedades, texto
  empaquetado, metadatos sin cabecera build, notas vacías, selección de APK,
  errores HTTP/red, checksum y eliminación de descargas fallidas.
- `lintPhoneDebug` se ejecutó completo y **falló**: 565 errores y 176 avisos,
  con otros 886 errores y 1226 avisos filtrados por el baseline existente.
  555 errores son MissingTranslation. Ningún error se localiza en archivos de
  este cambio; los dos avisos de recursos antiguos sin uso se resolvieron
  retirando las cadenas de título/notificación reemplazadas. No se amplía el
  baseline ni se deshabilitan comprobaciones globales. Esto no es lint verde.
- El primer error global es GestureBackNavigation en
  `braille/brailleime/src/phone/java/com/google/android/accessibility/brailleime/settings/BraillePointCalibrationActivity.java:117`,
  una ruta no modificada. También quedan incidencias NewApi y ExtraTranslation
  fuera del actualizador. Resolver esa deuda general requiere otro alcance.
- XML, sintaxis de PowerShell y diff revisados. Se conservan los finales CRLF
  del baseline; la comprobación de espacios usa `core.whitespace=cr-at-eol`.
- La primera prueba enfocada coincidió con cambios de recursos aún en curso y
  detectó la ausencia de la entrada en el XML compilado. La suite completa se
  ejecutó de nuevo con la entrada integrada y aprobó el clic y su diálogo.
- En este equipo Gradle no pudo crear temporales en su caché habitual. Se usó
  una caché aislada bajo `build/gradle-release-home` y temporales dentro de
  `build/`, sin sustituir la configuración local ni borrar la caché anterior.

Las nuevas etiquetas del actualizador están en inglés y español; los demás
idiomas conservan el fallback inglés para esas etiquetas. La excepción de lint
`MissingTranslation` se limita a esas cuatro cadenas, siguiendo el patrón del
proyecto; no se amplía el baseline ni se desactiva lint globalmente.

La traducción y su configuración de compilación se conservan sin cambios.

## Límites y comprobación del usuario

No se instala el APK en ningún teléfono como parte de esta preparación.
Después de publicar, comprobar desde una versión anterior: Buscar actualizaciones,
texto del aviso, permiso de instalar apps, instalación, Novedades sin conexión y
que no vuelva a ofrecer la misma versión. La pantalla apagada en HONOR X6b Plus
sigue siendo una prueba pendiente; no se afirma que su causa esté resuelta.

## Reversión

Antes de publicar, el límite de reversión es el cambio de actualizador, recursos,
pruebas y guías de esta entrega; no retirar el commit TTS de Marco. Después de
publicar, no reemplazar el APK del tag estable ni reutilizar su código: cualquier
corrección necesita una nueva versión y un versionCode mayor.
