# Saltos de foco al abrir el ecualizador desde Spotify

## Informe y límites de la investigación

Al abrir el ecualizador, el lector parece recorrer controles muy rápidamente,
sin permitir la interacción. Investigación iniciada desde `9651a480`, en la rama
`spotify-ecualizador`. Se conectó un Samsung SM-S928B con CAN Talk 1.0.2
(`70000332`). El usuario confirma la repetición sin tocar la pantalla y que,
cuando intenta moverse, parece volver al mismo control.

La actividad visible confirma que Spotify abre
`com.sec.android.app.soundalive/.activity.EqualizerActivity`, del ecualizador de
Samsung, versión 17.0.23. Spotify documenta que un ecualizador del dispositivo
puede afectar a otras aplicaciones:
<https://support.spotify.com/au/article/equalizer/>.

## Evidencia de la captura

Con el registro detallado activo, `SelectionEventInterpreter` recibe unas
9–13 notificaciones `TYPE_VIEW_SELECTED` por segundo incluso sin interacción.
El origen es un `android.widget.Button` con rol `ROLE_BUTTON`, identificador
`com.sec.android.app.soundalive:id/button_equalizer_preset_clear`. En la captura
se repiten el mismo nodo, selección, texto y descripciones. Se compararon firmas
de contenido en memoria; no se guardaron los textos reales de los registros.

`EventTypeViewSelectedFeedbackRule` genera para estos botones voz, sonido y
vibración, con interrupción de la voz anterior. El intérprete no descarta las
selecciones repetidas sin cambios. Así se vuelve a anunciar el botón aunque el
usuario esté intentando escuchar otro control.

Durante intervalos de repetición no se registran nuevas acciones de foco del
lector. Esto confirma la repetición de feedback; no demuestra por sí solo que
el foco real siempre permanezca estable. Debe comprobarse la navegación completa
con el usuario después del ajuste.

La prueba del primer APK confirmó una segunda ruta: al alejarse del botón,
`InputFocusInterpreter` interpreta sus eventos `TYPE_VIEW_SELECTED` como una
petición para sincronizar el foco. Se observan acciones `FOCUS_SYNCHRONIZATION`
que regresan a `button_equalizer_preset_clear` tras acciones de navegación o
exploración sobre otro control. El usuario confirma que desaparece la repetición
en bucle, pero el foco sigue volviendo al botón. Por tanto, el primer filtro de
feedback no basta para corregir la navegación.

## Ajuste preparado

En `SelectionEventInterpreter`, recordar hasta 32 botones de SoundAlive y su
último estado de selección anunciado. Ignorar una selección repetida únicamente
si su contenido y estado son iguales. Se filtra antes del compositor para evitar
también el sonido y la vibración, además de la voz.

Los cambios de texto, descripción, selección, marcado, habilitación, rango e
índices siguen pasando. Los cambios de foco y los gestos no reactivan el anuncio
del botón anterior. Al cambiar de ventana se vacía el historial. Los eventos
transitorios ignorados por el compositor no consumen el primer anuncio útil.
Las otras aplicaciones, deslizadores y barras de progreso conservan su ruta.

Además, impedir que `InputFocusInterpreter` convierta la selección de un botón
de SoundAlive en una orden de foco: esa selección representa el estado del
ecualizador, no una navegación del usuario. Se mantiene la sincronización ante
un evento real `TYPE_VIEW_FOCUSED`, incluido el teclado físico. Ambas rutas
comparten la misma identificación de los botones de SoundAlive.

Se revisó también la recuperación de desplazamiento pendiente de Backtalk #332,
pero esa corrección independiente no se incluye: la evidencia de esta captura
apunta a selecciones repetidas de SoundAlive, no a una recuperación de scroll.

## Validación local

`SelectionEventInterpreterSoundAliveTest` reproduce la ráfaga, botones alternados,
el movimiento de foco, cambios de estado y contenido, ventanas y eventos sin
origen, además de comprobar otras aplicaciones, deslizadores y barras de progreso.
Ejecuta 15 casos en Android 10 (API 29) y Android 14 (API 34).

Antes del ajuste fallaban 8 de las 30 ejecuciones. Después pasan las 30. También
pasan las 7 pruebas existentes de interpretación de texto y las 16 de seguridad
de voz: 53 en total, sin fallos ni errores. Estas pruebas verifican el intérprete;
la validación completa de navegación se realizó después en el Samsung.

La segunda regresión, `InputFocusInterpreterSoundAliveTest`, ejecuta 9 casos en
esas dos versiones de Android. Reproduce el retorno desde otro control, ejercita
los dos intérpretes con los mismos eventos y comprueba el foco real de teclado,
las listas de otras aplicaciones y los deslizadores. Antes del ajuste de foco
fallaban 6 de sus 18 ejecuciones; después pasan las 18. La comprobación conjunta
incluye 71 pruebas, todas sin fallos ni errores. Se conserva el modelo de selección
de Android TV.

## Primer APK de prueba

Compilación local `:assemblePhoneDebug` correcta, basada en `9651a480` con este
ajuste todavía sin commit. Artefacto:
`build/apks/can-talk-spotify-primer-ajuste-debug.apk`, paquete `can.cantalk`, versión
`1.0.3`, código `70000337`. Firma verificada e igual al APK anterior.

SHA-256: `27EAD89DD34F55A6918DDC3E6F905DE0138C67F993A03CA62B67057AE2CA17B5`.

Instalado mediante actualización sobre `can.cantalk` el 31 de octubre de 2026.
Se verificaron versión, código y servicio del lector activo tras reiniciarse.
`CAN Talk prueba` conserva su versión, código y fecha de instalación anteriores.
La prueba del usuario confirma que se elimina la repetición en bucle, pero sigue
el retorno del foco. Hace falta probar también el ajuste de sincronización.

## Segundo APK de prueba

Pruebas y compilación `:assemblePhoneDebug` correctas. Artefacto:
`build/apks/can-talk-spotify-ecualizador-debug.apk`, paquete `can.cantalk`, versión
`1.0.3`, código `70000337`. Firma verificada e igual a la versión anterior.
Incluye el filtro de feedback y el ajuste de sincronización del foco.

SHA-256: `C0248B8D66E896A1BFE97A8B7111C690B2D75C610F519421EA0A9F488698E213`.

Instalado el 31 de octubre de 2026. Lector activo tras la actualización;
`CAN Talk prueba` conserva versión, código y fecha de actualización anteriores.
El usuario confirma que con esta segunda actualización puede navegar normalmente
por el ecualizador, sin que el foco vuelva al mismo botón. En la captura posterior
no se observan acciones `FOCUS_SYNCHRONIZATION` que lo devuelvan a ese control.

## Otras rutas que hay que observar en el dispositivo

- `InputFocusInterpreter` y `Mappers`: los eventos `TYPE_VIEW_FOCUSED` y algunas
  selecciones de listas pueden sincronizar el foco del lector con otro control.
- `SubtreeChangeEventInterpreter` y `FocusManagerInternal`: los cambios del árbol
  pueden activar una recuperación cuando Android deja de informar de un foco.
- `SelectionEventInterpreter` y `EventTypeViewSelectedFeedbackRule`: un cambio
  de valor puede producir voz sin que haya cambiado el foco de accesibilidad.

La captura debe correlacionar tipos y tiempos de eventos, paquete, ventana y
origen de las acciones (`FOCUS_SYNCHRONIZATION`, `MANUAL_SCROLL`,
`SCREEN_STATE_CHANGE`, `ENSURE_ON_SCREEN`, exploración o navegación del usuario).
Los anuncios por sí solos no permiten distinguir estas rutas.

## Guía para volver a comprobarlo

1. Abrir el panel y dejar unos segundos sin tocar la pantalla para observar si
   los eventos y cambios de foco continúan solos.
2. Comparar con la navegación normal de ajustes y con otros deslizadores.
3. Identificar la actividad que contiene el panel y comparar el mismo panel
   abierto desde los ajustes del teléfono, si existe esa ruta.
4. Probar el ajuste y confirmar tanto el silencio sin interacción como que sea
   posible alejarse del botón, activar otros controles y ajustar valores.

Recoger solo los datos necesarios; no guardar textos de nodos, contraseñas,
notificaciones ni contenido hablado. La generación detallada de registros del
lector puede incluir ese contenido y debe desactivarse al terminar una captura.
