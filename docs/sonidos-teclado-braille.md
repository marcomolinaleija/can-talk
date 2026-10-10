# Sonidos del teclado braille

En los ajustes del teclado, activa **Sonidos al escribir**. **Sonidos del teclado** permite elegir:

- **Tema de sonidos actual**: conserva los reemplazos del tema; usa Android para acciones sin reemplazo.
- **Android predeterminado**: usa los sonidos de carácter, espacio, borrar y nueva línea del teléfono.
- **Samsung habitual, Calm, Fun o Retro**: aparecen solo cuando sus archivos de escritura y borrado son legibles. Samsung comparte el sonido de escritura entre carácter, espacio y nueva línea.

No se incluyen ni se copian archivos de Samsung en el APK. Se reproducen los del teléfono. Si faltan o no pueden decodificarse, se usa el efecto de Android.

**Seguir el volumen del lector** está activado inicialmente. Los sonidos usan la salida de audio y el canal de volumen del lector; mantienen una intensidad base del 50 % y siguen también el ajuste de volumen de voz de can-talk. Si desactivas la opción, **Volumen de sonidos al escribir (%)** permite ajustar su intensidad de 0 a 200 sin modificar la voz. El volumen del canal de Android sigue afectando a ambos; esta opción no crea un canal de volumen del sistema independiente. No aplica la compensación experimental de voz durante llamadas a los sonidos.

Hasta 100 % se conserva la reproducción anterior. Por encima de 100 % se amplifican
los archivos del perfil seleccionado o del tema actual mediante el mezclador PCM
de baja latencia. 200 % duplica la amplitud de las muestras, sin garantizar el doble
de volumen percibido. Los picos se limitan a valores válidos de audio; un archivo que
ya está cerca del máximo puede distorsionar al amplificarlo. El valor inicial sigue
siendo 50 % y los porcentajes guardados anteriormente conservan su intensidad.

Los archivos se precargan al abrir el teclado o los ajustes. **Escuchar muestra del teclado** reproduce un carácter usando la selección y el volumen actuales. Si todavía se está cargando, la muestra espera; los eventos normales de escritura no se reproducen tarde.

Cuando no hay un archivo compatible, no se puede decodificar o el mezclador no está
disponible, se conserva el reproductor anterior, limitado a 100 %. El último recurso
es `AudioManager.playSoundEffect`; Android decide entonces la salida y el canal del
efecto, que pueden diferir de los del lector. La amplificación no se simula pasando
un valor mayor que 1 a `SoundPool`: su [API admite ganancias de 0 a 1](https://developer.android.com/reference/android/media/SoundPool).

## Prueba en teléfono

1. Activar sonidos y comprobar la muestra de cada perfil disponible, incluidos los reemplazos del tema actual.
2. Escribir caracteres, espacio, borrar y nueva línea. En Samsung verificar el sonido diferente de borrar.
3. Con seguimiento activado, cambiar el volumen del lector y comprobar que cambia la intensidad del teclado.
4. Desactivar seguimiento y probar 0, 25, 100, 150 y 200 %. Usar la muestra y escribir caracteres, espacios, borrado y nueva línea. Cambiar el porcentaje no debe cambiar el volumen de voz ni el del sistema.
5. Apagar Sonidos al escribir: muestra y selector deben desactivarse, y no deben sonar las teclas.
6. Cambiar la salida entre móvil y Bluetooth y el canal accesibilidad/multimedia del lector. Los archivos deben seguir la nueva configuración.
7. Guardar 200 %, cerrar y volver a abrir los ajustes y el teclado: conservar perfil, seguimiento y porcentaje.
8. Cambiar el perfil o apagar los sonidos mientras se carga una muestra: cancelar la muestra anterior. La escritura normal nunca debe sonar tarde al terminar una carga.

La selección se guarda en `braille_keyboard`, en almacenamiento protegido del dispositivo, como el resto de ajustes braille.

## Desarrollo y pruebas de la amplificación

Rama: `codex/volumen-sonidos-braille`, creada desde `codex/desarrollo` en `df25b3be`.
Se precargan solo los archivos de las cuatro acciones del teclado, deduplicados por
ruta, en un trabajador que termina cuando queda inactivo. Cambiar el tema, perfil o
salida o apagar los sonidos invalida las cargas y muestras pendientes. Cerrar el
teclado o salir de los ajustes cancela las muestras pendientes y conserva la precarga.
No se guardan copias de los sonidos del teléfono en el APK ni en archivos temporales.

Las regresiones cubren 200 % en el mezclador PCM real, límites de las muestras,
reproducción anterior, persistencia del control, carga asíncrona, cancelación de
muestras y recuperación cuando falla la decodificación o el reproductor.

```powershell
.\compilar.ps1 -Prueba -Tareas @('testPhoneDebugUnitTest', ':assemblePhoneDebug')
```

Las pruebas de audio inspeccionan muestras y llamadas de reproducción; la percepción
del volumen y su calidad se comprueban escuchando la muestra en el teléfono.

Validación del 10 de octubre de 2026: 744 pruebas unitarias de la variante phone
aprobadas (437 de talkback, 159 de utils y 148 de brailleime). El APK debug compiló
y su firma se verificó correctamente. Se instaló por ADB inalámbrico con `-r`,
conservando los datos, sobre el Samsung SM-S928B. La versión instalada es
`TfPu_release_16_2-2026_10_10_0210`, código `70000268`; se comprobó que el servicio
del lector quedó conectado y el teclado braille continuó como método de entrada.
La comprobación auditiva de la amplificación queda para la muestra del teléfono.
