# Sugerencias de menciones de WhatsApp desde el teclado braille

## Inspección en teléfono

El 9 de octubre de 2026 se inspeccionó mediante ADB y UI Automator un
Samsung SM-S928B con WhatsApp 2.26.39.74. El usuario dejó abierto un grupo,
con `@` en el campo de escritura y el teclado braille de
`fyi.quin.backtalk.cantalktest` como método de entrada activo.

Se observó esta estructura de accesibilidad:

- Campo `com.whatsapp:id/entry`: con foco de entrada y un carácter, `@`.
- Contenedor `com.whatsapp:id/mention_attach`: delimita la lista de menciones.
- Lista `com.whatsapp:id/list`: `RecyclerView` desplazable dentro del contenedor.
- Cuatro filas visibles, habilitadas y pulsables.
- Cada fila contiene un nombre `com.whatsapp:id/primary_name` y una descripción
  de accesibilidad. Algunas contienen texto secundario.

No se guardaron nombres, números, mensajes ni el árbol completo en el repositorio.
La captura se limitó a inspección: no se seleccionó una persona ni se envió un mensaje.
Cuatro es el número de opciones visibles; no se comprobó el total de participantes.

Después de que el usuario reportó que se saltaba «Sugerencias», se compararon los
árboles normal y comprimido de UI Automator con la misma pantalla. El árbol
comprimido conserva `entry`, `list` y cuatro `primary_name`, pero omite el
contenedor `mention_attach` y sitúa la lista bajo `coordinator`. El servicio
activo solicita identificadores de vistas, pero no vistas sin importancia para
accesibilidad; exigir ese contenedor descartaba la lista. La primera inspección
del árbol completo no representaba este filtrado del lector.

## Viabilidad y alcance

Esta entrega cubre las menciones de WhatsApp (`com.whatsapp`). El autocompletado
de contraseñas y las sugerencias de otras aplicaciones requieren una integración futura.

La inspección respalda implementar navegación de menciones desde el teclado
sin ocultarlo: la lista y el campo de escritura son accesibles mientras está activo.
UI Automator confirma la estructura; la confirmación del usuario tras la corrección
y los casos manuales que quedan por ampliar se describen al final de esta guía.

La implementación reconoce `list` en `com.whatsapp` con filas pulsables que
contienen `primary_name`, mientras `entry` tiene foco y una consulta de mención
iniciada con `@` al comienzo del texto o tras espacio, hasta el cursor. Cuando
`mention_attach` está disponible se limita la búsqueda a ese contenedor. Cuando
Android lo omite, se exige una única lista con esa estructura en la ventana del
editor. No se habilitan globalmente las vistas sin importancia para accesibilidad.
Listas ambiguas, direcciones de correo y listas sin nombres de menciones se
rechazan. Estos identificadores pueden cambiar en futuras versiones de WhatsApp.

Se puede reutilizar el patrón de validación de `ScreenElementSearch`, pero no
abrir la búsqueda braille general: esa interfaz mantiene su consulta propia y se
cierra antes de activar elementos. Las menciones deben funcionar dentro de la
sesión del teclado y conservar el editor de WhatsApp.

## Implementación

Rama: `codex/sugerencias-braille-whatsapp`, creada desde `codex/desarrollo`.

El control «Sugerencias» se incorpora después de Caracteres, en el orden de
controles del teclado, únicamente cuando está disponible la lista de menciones.
Se selecciona explícitamente con los gestos de cambiar de control, conservando
los controles y gestos personalizados existentes. Al entrar se anuncia solamente
«Sugerencias»; la primera navegación elige y anuncia una persona.

| Acción en Sugerencias | Resultado |
| --- | --- |
| Un dedo arriba o abajo, con asignaciones predeterminadas | Anterior o siguiente opción |
| Dos dedos a la derecha, con asignación predeterminada | Activar la fila elegida y volver a escritura |
| Cambiar de control | Salir de Sugerencias sin cerrar el teclado |
| Escribir braille | Volver a escritura y dejar que WhatsApp actualice la lista |

Los gestos predeterminados para cambiar de control son tres dedos a izquierda o
derecha. Un dedo arriba elige la sugerencia anterior y abajo la siguiente. El
gesto de enviar mantiene su función; confirmar una mención utiliza la acción
Nueva línea, predeterminada en dos dedos a la derecha, solo dentro de Sugerencias.

Se anuncia «Nombre. 2 de 4 sugerencias visibles». Al alcanzar un extremo de las
filas visibles se solicita el desplazamiento de la lista y se espera a que
aparezcan las siguientes. La posición vuelve a calcularse para la nueva página
visible. Si no hay más opciones, se anuncia el límite sin cambiar la selección.
No se confirman filas mientras está pendiente el desplazamiento.

La implementación respeta los gestos personalizados del teclado, anuncia
el nombre seleccionado y distingue el número de opciones visibles del total.
No anuncia un total de participantes que no pueda obtener de la lista.

El teclado llama actualmente a `focusInputField()` antes de ejecutar sus acciones.
La navegación de sugerencias se resuelve antes de las acciones habituales de
cursor o nueva línea. Se mantiene una selección interna y se anuncia su etiqueta
sin desplazar el foco de entrada a otra vista.

Antes de confirmar se refresca el nodo y se verifica editor, ventana,
contenedor, etiqueta, estado habilitado y pertenencia de la fila a la lista actual.
Una selección desaparecida se anuncia como no disponible y no se sustituye
por otra persona. La activación usa la acción de pulsación de la fila para
que WhatsApp inserte su mención, en lugar de escribir manualmente el nombre.

## Validación automatizada

La primera compilación aprobó 21 pruebas específicas: 14 del controlador de sugerencias y 7 del
actor del teclado, incluidas las regresiones de envío existentes. Cubren listas
ajenas a menciones, nombres repetidos, filas ocultas o deshabilitadas, cambios de
editor y grupo, filas recicladas, errores de pulsación, desplazamiento asíncrono,
cancelación, bloqueo/pausa y escritura después de una inserción externa.

La corrección agrega y aprueba cuatro regresiones del árbol comprimido: navegación y
confirmación sin el contenedor, listas ambiguas, filas ajenas a menciones y
consulta de mención junto al cursor. La compilación corregida aprobó 25 pruebas
en total: 18 del controlador y las 7 del actor, que permanecen sin cambios.

```powershell
.\compilar.ps1 -Prueba -Tareas @(
  ':talkback:testPhoneDebugUnitTest', '--tests', '*BrailleSuggestionsControllerTest',
  ':brailleime:testPhoneDebugUnitTest', '--tests', '*BrailleImeSubmitTest',
  ':assemblePhoneDebug'
)
```

Las pruebas usan nodos y traducción simulados. La estructura real de WhatsApp se
inspeccionó por ADB; la confirmación del usuario y los casos manuales que quedan
por ampliar se describen al final de esta guía.

Compilación completada e instalada por ADB el 9 de octubre de 2026 en el
Samsung SM-S928B. Se verificó que el servicio del lector volvió a estar conectado
y que el teclado braille de prueba sigue siendo el método de entrada predeterminado.

- Paquete: `fyi.quin.backtalk.cantalktest`.
- Versión de prueba: `70000255`.
- Nombre de la versión corregida: `TfPu_release_16_2-2026_10_09_2317`.
- APK local: `build/apks/can-talk-sugerencias-whatsapp-prueba.apk`.
- SHA-256 de la corrección instalada: `3AFFFD71CA566011AF037298B29FF54E636BE8C5CD09C0366C023995527520BF`.

## Integración con desarrollo

El 10 de octubre de 2026 se actualizó la base de la rama a
`252528c8` de `origin/codex/desarrollo`, incorporando once commits nuevos.
Se conservaron las pruebas de mayúsculas automáticas junto a las de sugerencias:
el actor tiene ahora nueve pruebas y el controlador de sugerencias, dieciocho.

La nomenclatura de APK y publicación queda como la define esa base:
`cantalk-phone-debug.apk`, `cantalk-wear-debug.apk`, `cantalk.apk` y
`cantalk-wear.apk`. Las variantes Android y su firma siguen definiéndose en la
configuración existente. El APK instalado y su hash anteriores corresponden a
la prueba anterior a esta integración.

Para validar el conjunto se ejecuta:

```powershell
.\compilar.ps1 -Prueba -Tareas @(
  'testPhoneDebugUnitTest', ':lintPhoneDebug', ':assemblePhoneDebug'
)
```

Las 727 pruebas de teléfono pasaron: 437 de `talkback`, 135 de `brailleime` y
155 de `utils`, sin fallos, errores ni pruebas omitidas.

La primera pasada de lint reportó 117 errores. Las líneas de 110 incidencias
ya estaban en la base `252528c8`: 107 por traducciones pendientes y tres por
compatibilidad de Android (`GestureBackNavigation`, `NewApi` y
`AppCompatCustomView`). Las otras siete correspondían a traducciones de las
sugerencias. Se aplicó el respaldo inglés con `tools:ignore="MissingTranslation"`
limitado a este archivo, siguiendo los controles nuevos existentes, y se conservó
el español. El análisis repetido de `talkback` no reportó incidencias en el
controlador ni en los textos de sugerencias. No se modificó la línea base de lint.
El informe global repetido terminó con los mismos 110 errores heredados y cero
incidencias en la función de sugerencias; por ellos, `:lintPhoneDebug` sigue
devolviendo un resultado fallido en esta base de desarrollo.

La compilación de prueba previa al commit se completó con `:assemblePhoneDebug`.
Se verificaron sus metadatos y la firma con `apksigner verify`; esta integración
no se instaló en el teléfono.

- Paquete: `fyi.quin.backtalk.cantalktest`.
- Versión: `70000266`, `TfPu_release_16_2-2026_10_10_0114`.
- APK: `build/outputs/apk/phone/debug/cantalk-phone-debug.apk`.
- SHA-256: `BE5578E3F70DF6E0C235F181A7A49D138DD48A44BB58B25A0C8CF321700A8BF4`.

## Validación manual

El usuario confirmó que el control funciona después de corregir el reconocimiento
del árbol comprimido. Queda por ampliar la validación a todos los casos siguientes.

1. Recorrer y escuchar las filas desde el servicio con braille activo.
2. Confirmar una fila y verificar que WhatsApp inserta una mención real, conserva
   el teclado y permite continuar escribiendo. No ejecutar la acción Enviar.
3. Escribir después de `@` y comprobar actualización y desaparición de resultados.
4. Recorrer más allá de las filas visibles con desplazamiento,
   verificando que las filas recicladas no conservan una selección incorrecta.
5. Salir del modo, cambiar de chat, ocultar el teclado, pausar el lector y bloquear
   la pantalla: cancelar la selección y cualquier activación pendiente.

Referencias del repo: [Búsqueda braille unificada](busqueda-braille-unificada.md)
y [Compilación de prueba](../COMPILACION.md).
