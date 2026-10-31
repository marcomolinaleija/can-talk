# Reglas de desarrollo y publicación de CAN Talk

## Versión y notas

- Notas, títulos y resúmenes de releases siempre en español profesional y accesible.
- La fuente única de las notas es `talkback/src/main/res/raw/release_notes.txt`.
  Comienza con `CAN Talk x.y.z`. Se empaqueta en el APK y se utiliza como cuerpo
  de la release estable y como asset `release-notes.txt`.
- La opción Novedades muestra este archivo de la versión instalada, sin red.
- Antes de compilar, actualizar `version.gradle`, los ejemplos de versión en
  `values`, `values-es`, `values-b+es+419` y `values-cs` de `strings_update.xml`,
  y la referencia correspondiente de `lint-baseline.xml`.

## Contrato del actualizador desde 1.0.3

- Consultar únicamente `https://api.github.com/repos/marcomolinaleija/can-talk/releases/latest`
  para versiones estables públicas, sin credenciales dentro de la app.
- GitHub proporciona `tag_name`, `body` y `assets`. `body` es texto editorial:
  NO se obtiene de él el número de versión, compilación ni lista de commits.
- Cada release incluye `cantalk.apk` y `update.json`, con este contrato:

```json
{
  "schemaVersion": 1,
  "commit": "SHA completo del commit compilado",
  "apks": {
    "cantalk.apk": {
      "versionCode": 70000334,
      "versionName": "1.0.3",
      "sha256": "64 caracteres hexadecimales del APK"
    }
  }
}
```

- Obtener versión y código del APK real y metadatos de Gradle, no del texto.
  Conservar `versionCode = 70000000 + git rev-list --count HEAD` y no publicar
  un código menor o igual al último distribuido.
- El actualizador exige assets terminados (`state=uploaded`), misma release y
  repositorio, HTTPS y checksum válido. Mantener las rutas estables inmutables.
- Wear OS está desactivado; no ofrecer el APK de teléfono a un reloj. Una futura
  publicación Wear necesita `cantalk-wear.apk` y su entrada independiente en el JSON.

## Compatibilidad con 1.0.0–1.0.2

Estos clientes siguen consultando `releases/tags/dev` y parseando sus notas.
Al publicar se actualiza también `dev`, como pre-release, con el mismo APK y JSON.
Solo su cuerpo conserva `build: <total de commits>` y una línea
`- <hash corto> <resumen en español>`, seguidos de las notas completas.
El script adapta las viñetas editoriales para que los clientes antiguos no las
confundan con commits. No quitar esta compatibilidad todavía.

## Compilación y publicación

La guía operativa única es [LANZAMIENTO.md](LANZAMIENTO.md). `COMPILACION.md`
describe el entorno y las variantes, no otro procedimiento de publicación.
Las versiones de los ejemplos no autorizan volver a publicar un tag existente.

1. Comprobar los cambios con pruebas proporcionales; actualizar las notas.
2. Confirmar el código antes de compilar: el APK debe identificar ese commit.
3. Ejecutar `pwsh -File .\compilar.ps1` (`:assemblePhoneDebug`).
4. Verificar paquete `can.cantalk`, versión, código, firma y SHA-256.
5. Publicar solo con autorización explícita:
   `pwsh -File .\publicar-release.ps1 -Version 1.0.4 -SkipBuild`
   (ejemplo de versión nueva, que debe estar preparada en el código y las notas).

El APK de distribución actual es `build/outputs/apk/phone/debug/cantalk-phone-debug.apk`:
minificado, no depurable y firmado con `keystore/debug.keystore`. NO cambiar a
una clave distinta sin una migración coordinada: impediría actualizar las instalaciones.
La clave compartida es pública y no ofrece la protección de una clave privada;
el checksum detecta inconsistencias, no sustituye una firma privada.

El script genera `build/releases/<versión>/`, valida el APK, crea un tag de versión
sin sobrescribirlo, sube assets a una release en borrador antes de publicarla y
sincroniza `dev`. Se detiene ante errores nativos. No sustituye pruebas de dispositivo.
Usar siempre `--repo marcomolinaleija/can-talk` con `gh`: este checkout también tiene
un remoto upstream y la selección implícita puede apuntar al repositorio equivocado.

Commit, push, compilación y publicación requieren autorización por separado.
Nunca forzar tags semánticos existentes; únicamente `dev` es móvil.
No ejecutar dos publicaciones simultáneas ni repetir el script a ciegas tras un
fallo parcial; inspeccionar el estado y seguir la recuperación de la guía.
GitHub Actions aún no publica releases automáticamente.
