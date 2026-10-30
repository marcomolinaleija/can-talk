# Buscar aplicaciones en braille

Esta guía documenta la primera implementación. La ampliación de la interfaz y sus
controles actuales se describen en [Búsqueda braille unificada](busqueda-braille-unificada.md).

Primera implementación en `codex/busqueda-apps-braille`, a partir de `codex/desarrollo`.

En los ajustes de can-talk, abre **Personalizar gestos**, elige un gesto y asígnale
**Buscar aplicaciones en braille**, en **Acciones del sistema**. No se cambia ninguna
asignación existente. Se puede abrir la búsqueda desde cualquier aplicación, sin un
campo de texto y sin cambiar el teclado predeterminado. Requiere Android 11 o posterior.

Al abrir se anuncia «Búsqueda de aplicaciones abierta» y la indicación de escribir.
Escribe el nombre o parte del nombre de una aplicación con los puntos braille.
Se usa el idioma, el modo contraído, la disposición, la inversión de puntos y la
calibración del teclado. También admite el modo de acumulación.

| Gesto dentro de la búsqueda | Acción |
| --- | --- |
| Un dedo arriba o abajo | Elegir la coincidencia anterior o siguiente, de forma cíclica |
| Un dedo a la izquierda | Borrar la última celda braille y recalcular la búsqueda |
| Un dedo a la derecha | Añadir un espacio |
| Dos dedos a la derecha | Abrir la aplicación seleccionada y cerrar la búsqueda |
| Dos dedos a la izquierda o hacia abajo | Cerrar la búsqueda |
| Tres dedos hacia arriba | Escuchar la ayuda |
| Botón o gesto del sistema Atrás | Cerrar la búsqueda |

Los gestos siguen la orientación de los puntos, igual que el teclado braille.
Dentro de esta interfaz tienen estas acciones fijas; la personalización de los
gestos del teclado de escritura no altera la búsqueda. El gesto que la abre sí se
personaliza en can-talk.

Se anuncian el nombre de la aplicación y su posición, por ejemplo «YouTube. 1 de 2».
La búsqueda ignora mayúsculas, acentos y espacios repetidos, priorizando nombres
que empiezan por lo escrito. Si no hay coincidencias, se anuncia y se puede borrar
o seguir escribiendo. Una consulta vacía no abre aplicaciones.

La lista incluye las actividades habilitadas, exportadas y no suspendidas que
aparecen en el lanzador del usuario actual. No incluye servicios ni todos los
paquetes instalados. Se vuelve a consultar al abrir la búsqueda. Si una aplicación
se desinstala mientras la búsqueda está abierta, se informa del fallo de apertura.

La interfaz usa una ventana de accesibilidad y recibe los mismos puntos braille
que el teclado, pero mantiene una consulta propia: no introduce texto ni pulsa
Enviar en la aplicación de fondo. Al cerrar, abrir una aplicación, bloquear la
pantalla, pausar el lector, cambiar de ventana activa o rotar la pantalla, libera
los sensores y restaura la exploración táctil o la región del teclado que estuviera
activa. No admite perfiles de trabajo ni gestos de comandos de VoiceOver en esta
primera versión.

## Validación

Compilación de prueba completada y 18 pruebas específicas pasadas (8 del teclado y
10 de resultados y controlador). Instalada por ADB en el Samsung SM-S928B el
9 de octubre de 2026. El usuario confirmó el funcionamiento correcto después de
abrir más de diez aplicaciones mediante búsquedas con distintas letras, incluida
la apertura desde otras aplicaciones.

APK local: `build/apks/can-talk-busqueda-apps-braille-prueba.apk`.
SHA-256: `D347731B60AD10331E38A8C28EC8F7C83C1DE2B6250A61AC931EF6CFE126A3CA`.
Paquete: `fyi.quin.backtalk.cantalktest`; versión de prueba: `70000249`.

Pruebas específicas de consulta braille, gestos, filtrado y navegación de resultados,
apertura de un componente explícito, fallo de apertura y restauración de la región
táctil. Las pruebas de traducción usan un traductor simulado: la traducción nativa y
el reconocimiento de los puntos deben comprobarse también en el teléfono.

```powershell
.\compilar.ps1 -Prueba -Tareas @(
  ':brailleime:testPhoneDebugUnitTest', '--tests', '*Braille*Search*Test',
  ':talkback:testPhoneDebugUnitTest', '--tests', '*AppSearch*Test',
  ':assemblePhoneDebug'
)
```

Flujo manual: asignar el gesto, abrir desde la pantalla de inicio, escribir
«youtube», recorrer YouTube y YouTube Music si ambas están instaladas y abrir con
dos dedos a la derecha. Repetir desde otra aplicación y comprobar que el texto del
campo de fondo no cambia. Probar borrar, espacios, ausencia de resultados y cierre.

Referencia: [manual de Apple, entrada braille en pantalla](https://cdsassets.apple.com/live/6GJYWVAV/user/ma1725_ipad_user_guide.pdf).
