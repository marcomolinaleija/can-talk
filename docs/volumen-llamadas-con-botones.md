# Ajustar la voz y los sonidos durante una llamada con los botones de volumen

La reducción de voz con el mínimo de 5 % y la curva cuadrática ya fue confirmada por
el usuario en el teléfono de desarrollo, junto con 43 comprobaciones automatizadas
de volumen, botones y ajustes. La extensión a los sonidos del lector incorpora nuevas
regresiones y el usuario confirmó su funcionamiento en el teléfono tras instalar
la compilación del 10 de octubre de 2026, terminada en `0345`.
La estabilización conserva la etiqueta experimental porque el resultado depende del
teléfono y del motor de voz.

Activa **Estabilizar audio durante llamadas (experimental)** en la configuración de voz.
Durante una llamada, mantén un dedo apoyado en la pantalla y pulsa subir o bajar volumen.
Cada pulsación cambia **Volumen del lector durante llamadas (%)** en 5 puntos, entre 5 y 100.
El porcentaje es la posición del control, con una curva cuadrática para hacer mayor
el efecto de los pasos de 5 puntos: 100 % aplica la ganancia habitual, 50 % aplica
un cuarto y 25 % aplica un dieciseisavo, antes de compensar el volumen de la llamada.
El cambio de curva también se aplica desde los ajustes y al porcentaje ya guardado.
Mantener pulsado el botón repite el ajuste mientras el dedo siga en la pantalla.
can-talk anuncia el porcentaje y aplica el cambio a partir de esa frase y del siguiente sonido.
El valor queda guardado para las siguientes llamadas.

Sin el dedo apoyado, las teclas conservan su función habitual. Fuera de una llamada,
o con la estabilización desactivada, sigue funcionando el control de volumen anterior.
Se usa la misma detección de llamada que la compensación de voz, incluyendo las llamadas
por aplicaciones que ponen el audio en modo de comunicación.

El ajuste modifica la ganancia de voz y de los sonidos de navegación de can-talk,
conservando el nivel habitual de sonidos como base. Incluye los sonidos propios y
los reemplazos del tema, la reproducción de baja latencia, el paneo y los sonidos 3D.
Al terminar la llamada o desactivar la estabilización, el siguiente sonido vuelve
a su nivel habitual sin cambiar el porcentaje de sonidos guardado. Las claves de
preferencias siguen siendo las mismas; se conserva el porcentaje de llamadas existente.
Los sonidos del teclado braille mantienen su ajuste independiente.

No escribe en el volumen del sistema ni cambia la salida de audio. Una pulsación
capturada se consume hasta soltar
el botón, incluso si se levanta antes el dedo o termina la llamada. No se captura a mitad
de una pulsación que haya comenzado sin tocar la pantalla.

## Comprobación en el teléfono

1. Inicia una llamada con la estabilización activada.
2. Apoya un dedo y pulsa bajar volumen: debe anunciar 5 puntos menos y bajar la voz y los sonidos del lector al navegar.
3. Sin tocar la pantalla, pulsa subir volumen: debe subir la llamada.
4. Apoya el dedo, pulsa subir volumen y suelta el dedo antes que el botón: la llamada no debe cambiar.
5. Finaliza la llamada y comprueba que los sonidos vuelven a su intensidad habitual y dedo más volumen vuelve a controlar el volumen habitual del lector.
6. Repetir con los sonidos del tema actual, baja latencia y sonidos espaciales, y con una llamada por una app que use el modo de comunicación.

La regresión automatizada comprueba la ganancia que recibe SoundPool, las muestras
PCM reales del reproductor de baja latencia y el volumen del reproductor 3D. También
comprueba cambios de porcentaje sin voz intermedia, la restauración al colgar,
la opción desactivada, sonidos aún en carga, temas, paneo y salida multimedia.

Validación del 10 de octubre de 2026: pasaron 755 pruebas unitarias phone (437 de
talkback, 170 de utils y 148 de brailleime), incluidas las 11 regresiones nuevas de
sonidos durante llamadas. El APK compiló y se verificó su firma. La versión
`TfPu_release_16_2-2026_10_10_0345`, código `70000268`, se instaló por ADB inalámbrico
con `-r` sobre el Samsung SM-S928B. Se confirmó el servicio del lector conectado y
el teclado braille seleccionado; los datos y las preferencias se conservan.
