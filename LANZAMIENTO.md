# Publicar CAN Talk

La distribución actual utiliza `phoneDebug` con paquete `can.cantalk`, firma
compartida compatible y versión 1.0.x. No cambiar la firma para esta actualización.
El procedimiento antiguo de candidato 1.0.0-rc.1 ya no describe la distribución vigente.

## Camino habitual

1. Editar `talkback/src/main/res/raw/release_notes.txt` en español. Se usa tanto
   en Novedades sin conexión como en GitHub. No incluir secretos ni promesas de
   problemas de dispositivo que no se hayan podido reproducir.
2. Actualizar versión en `version.gradle`, ejemplos de `strings_update.xml`
   (`values`, `values-es`, `values-b+es+419`, `values-cs`) y `lint-baseline.xml`.
3. Ejecutar pruebas, revisar el diff y confirmar los cambios. El código de versión
   es `70000000 + número de commits`; no añadir commits entre compilar y publicar.
4. Compilar con `pwsh -File .\compilar.ps1` y verificar firma, identidad y código.
5. Hacer push de la rama autorizada y publicar:

```powershell
pwsh -File .\publicar-release.ps1 -Version 1.0.3 -SkipBuild
```

Para validar y generar los assets sin publicar ni crear tags, añadir `-SoloPreparar`.

Sin `-SkipBuild`, el script compila primero. Lee el APK de salida de Gradle, no un
`cantalk.apk` suelto que podría corresponder a otra compilación. Requiere entorno
local configurado y `gh` autenticado con permisos de publicación.

## Assets y canales

| Elemento | Función |
| --- | --- |
| `cantalk.apk` | APK de teléfono, mismo archivo en versión estable y `dev`. |
| `update.json` | Esquema 1 con commit y `apks.cantalk.apk`: versionCode, versionName y sha256. |
| `release-notes.txt` | Texto editorial de la versión, idéntico al incluido en el APK. |
| Release estable `1.0.x` | Cuerpo editorial libre; app 1.0.3+ consulta `/releases/latest`. |
| Pre-release `dev` | Compatibilidad con clientes anteriores: cabecera build y resumen con hash. |

Los assets se suben primero a un borrador de la release estable. La release se
publica cuando están disponibles todos. Nunca sobrescribir una versión estable
ni reutilizar su URL para otro APK. Solo el tag móvil `dev` admite force-push.
Si falla la sincronización posterior de `dev`, la publicación estable ya existe:
inspeccionar el estado remoto y completar únicamente el paso pendiente, no volver
a crear o forzar el tag estable.

## Verificar la entrega

- Inspeccionar con `gh --repo marcomolinaleija/can-talk` la release estable y `dev`.
- Consultar anónimamente la API y descargar ambos APK para contrastar SHA-256.
- Comprobar que el JSON coincide con versión/código del APK y que ambas referencias
  apuntan al commit compilado.
- Desde una versión anterior, buscar la actualización, revisar las novedades,
  instalar y comprobar que no vuelve a ofrecerse la misma versión.
- Probar voz, navegación y el caso reportado de pantalla apagada en el dispositivo
  afectado. Las pruebas locales no certifican ese comportamiento.

## Seguridad y límites

La firma compartida conserva compatibilidad, pero su clave está versionada y es
pública. Debe planificarse aparte una estrategia de firma privada: no resolverla
cambiando la clave de una actualización existente. El SHA-256 vincula metadatos y
archivo, no protege frente a una cuenta de publicación comprometida.

Los detalles normativos del contrato están en `AGENTS.md`. Las pruebas y los
límites de esta revisión están en `docs/auditoria-actualizador-1.0.3.md`.
