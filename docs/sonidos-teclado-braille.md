# Sonidos del teclado braille

En los ajustes del teclado, activa **Sonidos al escribir**. **Sonidos del teclado** permite elegir:

- **Tema de sonidos actual**: conserva los reemplazos del tema; usa Android para acciones sin reemplazo.
- **Android predeterminado**: usa los sonidos de carácter, espacio, borrar y nueva línea del teléfono.
- **Samsung habitual, Calm, Fun o Retro**: aparecen solo cuando sus archivos de escritura y borrado son legibles. Samsung comparte el sonido de escritura entre carácter, espacio y nueva línea.

No se incluyen ni se copian archivos de Samsung en el APK. Se reproducen los del teléfono. Si faltan o no pueden decodificarse, se usa el efecto de Android.

**Seguir el volumen del lector** está activado inicialmente. Los sonidos usan la salida de audio y el canal de volumen del lector; mantienen una intensidad base del 50 % y siguen también el ajuste de volumen de voz de can-talk. Si desactivas la opción, **Volumen de sonidos al escribir (%)** permite ajustar su intensidad de 0 a 100 sin modificar la voz. El volumen del canal de Android sigue afectando a ambos; esta opción no crea un canal de volumen del sistema independiente. No aplica la compensación experimental de voz durante llamadas a los sonidos.

Los archivos se precargan al abrir el teclado o los ajustes. **Escuchar muestra del teclado** reproduce un carácter usando la selección y el volumen actuales. Si todavía se está cargando, la muestra espera; los eventos normales de escritura no se reproducen tarde.

Cuando los archivos estándar del teléfono no están disponibles, el último recurso es `AudioManager.playSoundEffect`. En ese caso Android decide la salida y el canal del efecto, que pueden diferir de los del lector.

## Prueba en teléfono

1. Activar sonidos y comprobar la muestra de cada perfil disponible, incluidos los reemplazos del tema actual.
2. Escribir caracteres, espacio, borrar y nueva línea. En Samsung verificar el sonido diferente de borrar.
3. Con seguimiento activado, cambiar el volumen del lector y comprobar que cambia la intensidad del teclado.
4. Desactivar seguimiento y probar 0, 25 y 100 %. Cambiar el porcentaje no debe cambiar el volumen de voz.
5. Apagar Sonidos al escribir: muestra y selector deben desactivarse, y no deben sonar las teclas.
6. Cambiar la salida entre móvil y Bluetooth y el canal accesibilidad/multimedia del lector. Los archivos deben seguir la nueva configuración.
7. Cerrar y volver a abrir los ajustes y el teclado: conservar perfil, seguimiento y porcentaje.

La selección se guarda en `braille_keyboard`, en almacenamiento protegido del dispositivo, como el resto de ajustes braille.
