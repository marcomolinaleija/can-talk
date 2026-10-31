# Bloqueo al escribir en los campos de can-talk

## Captura del 30 de octubre de 2026

En el Samsung SM-S928B con Android 17, can-talk `can.cantalk` estaba activo y
el teclado seleccionado era Samsung HoneyBoard. El campo activo pertenecía a
can-talk y tenía tipo contraseña. No se capturó su contenido.

Los registros de `Choreographer` mostraron pausas de varios segundos. Una traza
de métodos de 28,18 segundos mostró 13 esperas de aproximadamente 2 segundos
en el hilo principal: 26,06 segundos en `Object.wait`, dentro de
`AccessibilityInteractionClient.waitForResultTimedLocked`, al resolver
`AccessibilityRecord.getSource`. No era una espera en el corrector ortográfico.
El muestreo ya está detenido; los archivos locales de diagnóstico están en
`.git/` y no forman parte del código versionado.

## Corrección

Los campos del propio lector compartían su hilo principal con el servicio.
El lector consultaba la accesibilidad del teclado mientras el teclado podía
esperar una respuesta del campo en ese mismo hilo. Cada consulta podía agotar
el tiempo límite de Android y repetirse en varios intérpretes del evento.

Se adaptan las correcciones de upstream `47155567` y `c8a281c7`:

- `KeyboardCalls` ofrece un hilo para la conexión con teclados de otras apps.
  Los cambios se encolan en el hilo principal, en orden, sin hacer esperar al
  teclado. Las consultas esperan como máximo 150 ms; si el hilo sigue ocupado,
  las siguientes obtienen una respuesta vacía hasta que este se libera.
- `KeyboardSafeEditText` aplica esta conexión a los campos de can-talk,
  incluidos restauración, credenciales, NVDA Remote, búsquedas, tutorial y
  preferencias. Conserva las opciones de contraseña, autocompletado y guardado
  que ya definía cada campo.
- El teclado braille del mismo paquete recibe la conexión directa del campo,
  porque se ejecuta en el mismo hilo; así se evita introducir una espera contra
  sí mismo.

La corrección anterior del corrector sigue vigente y protege una ruta distinta.

## Verificación

`KeyboardCallsTest`, `KeyboardSafeEditTextTest` y `SpellCheckerLatencyTest`
se ejecutan en Android 10, 11 y 14. Cubren consultas con el hilo ocupado,
recuperación posterior, cambios sin espera, orden de escritura y borrado,
privacidad de contraseña y selección del teclado del propio paquete.

Resultado automático: 88 pruebas sin fallos (57 de teclado y corrector, 27 de
Groq y 4 de tipos de preferencias de copia de seguridad). Compilación debug
normal `can.cantalk` y firma verificadas; actualización instalada conservando
`fyi.quin.backtalk.cantalktest` y los servicios de accesibilidad habilitados.

La segunda captura, de 55,69 segundos, registró el nuevo campo de contraseña
de restauración y la conexión `KeyboardCalls` en uso. No aparecieron las
esperas de 2 segundos ni esperas de `Object.wait` de 1 segundo o más en el
hilo principal. El usuario confirmó que escribir y borrar con Samsung ya
no congelaba el lector. Las trazas terminaron y sus archivos temporales se
eliminaron del teléfono; las copias locales de diagnóstico siguen en `.git/`.
