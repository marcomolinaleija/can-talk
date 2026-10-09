# Ajustar la voz durante una llamada con los botones de volumen

**Estado: fase de prueba.** Las comprobaciones automatizadas han pasado y la versión
se ha instalado en el teléfono. Sigue pendiente validar el gesto en una llamada real
antes de darlo por estable e integrarlo en desarrollo.

Activa **Estabilizar voz durante llamadas (experimental)** en la configuración de voz.
Durante una llamada, mantén un dedo apoyado en la pantalla y pulsa subir o bajar volumen.
Cada pulsación cambia **Volumen de voz durante llamadas (%)** en 5 puntos, entre 25 y 100.
Mantener pulsado el botón repite el ajuste mientras el dedo siga en la pantalla.
can-talk anuncia el porcentaje y aplica el cambio a partir de esa frase.
El valor queda guardado para las siguientes llamadas.

Sin el dedo apoyado, las teclas conservan su función habitual. Fuera de una llamada,
o con la estabilización desactivada, sigue funcionando el control de volumen anterior.
Se usa la misma detección de llamada que la compensación de voz, incluyendo las llamadas
por aplicaciones que ponen el audio en modo de comunicación.

El ajuste modifica únicamente la ganancia de voz de can-talk. No escribe en el volumen
del sistema ni cambia la salida de audio. Una pulsación capturada se consume hasta soltar
el botón, incluso si se levanta antes el dedo o termina la llamada. No se captura a mitad
de una pulsación que haya comenzado sin tocar la pantalla.

## Comprobación en el teléfono

1. Inicia una llamada con la estabilización activada.
2. Apoya un dedo y pulsa bajar volumen: debe anunciar 5 puntos menos y bajar la voz del lector.
3. Sin tocar la pantalla, pulsa subir volumen: debe subir la llamada.
4. Apoya el dedo, pulsa subir volumen y suelta el dedo antes que el botón: la llamada no debe cambiar.
5. Finaliza la llamada y comprueba que dedo más volumen vuelve a controlar el volumen habitual del lector.
