# Navegación web braille

Se abre desde el menú de CAN Talk con **Navegación web braille**, o asignando
esa acción en **Personalizar gestos**. Requiere Android 11 o posterior y una
página que exponga acciones de navegación HTML al lector.

La superficie conserva los puntos, orientación, calibración, seis u ocho puntos
y acumulación del teclado braille. Funciona sin enfocar un campo de texto.
Las letras de los comandos se escriben sin contracciones.

| Letra | Elementos |
| --- | --- |
| h | Encabezados |
| k | Enlaces |
| l | Listas |
| i | Elementos de lista |
| b | Botones |
| e | Campos de texto |
| f | Controles de formulario |
| t | Tablas |
| g | Imágenes |
| d | Regiones de la página |
| x | Casillas de verificación |
| c | Listas desplegables |
| r | Botones de opción |

Cada letra busca el siguiente elemento de ese tipo. Un dedo arriba busca el
anterior del último tipo elegido; un dedo abajo busca el siguiente. Un dedo
a izquierda o derecha recorre los elementos de lectura habituales.

Dos dedos a la derecha activa el elemento actual. Tres dedos arriba abre
un menú accesible con ayuda, asignación de gestos adicionales y restablecimiento.
Dos dedos abajo cierra el modo de forma predeterminada. Dos dedos a la izquierda
siempre cierra y tres dedos arriba siempre abre el menú, aunque se hayan
personalizado otros gestos. Atrás también cierra; durante la captura de un
gesto, Atrás cancela la captura y vuelve al menú.

La asignación de un deslizamiento pide confirmación e informa de su acción
anterior. Las letras permanecen reservadas para navegar. Los gestos de este
modo se guardan aparte de los gestos del teclado y de NVDA Remote.

## Escritura

Encontrar un campo con e solo lo lee. Activarlo libera la superficie y permite
escribir con el teclado braille normal. No se introduce texto ni se envía
el formulario al saltar a un campo.

El menú del teclado braille incluye **Navegación web** para volver. También
puede hacerse manteniendo los puntos 4, 5 y 6 y deslizando a la izquierda con
un dedo. Esa acción se puede reasignar en los ajustes de gestos del teclado.
Al volver se confirma el texto braille pendiente, sin enviar el formulario.

## Comportamiento

Los saltos usan las acciones HTML del navegador y el historial de foco del
lector. Recorren los elementos que expone el navegador, incluido contenido
fuera de pantalla; su disponibilidad depende del navegador y de la página.
Al alcanzar el principio o final se anuncia el límite del tipo elegido y se
conserva la posición, sin volver automáticamente al otro extremo.

Los eventos de foco y contenido de la página siguen llegando al lector mientras
está abierta la superficie. La navegación se cierra al cambiar de ventana de
aplicación, bloquear o apagar la pantalla, girar el dispositivo, pausar o
desactivar el lector. Libera los sensores, la zona táctil y la restricción
temporal del teclado. Abrir la búsqueda braille o el historial del portapapeles
también cierra esta superficie.

Las instrucciones y confirmaciones se permiten durante reproducción o llamadas,
como las otras respuestas solicitadas expresamente por el usuario.

## Prueba en el teléfono

1. Abrir una página con encabezados, enlaces, listas y controles de formulario.
2. Abrir el menú de CAN Talk y elegir Navegación web braille.
3. Escribir h varias veces; retroceder con un dedo arriba. Repetir con k, l,
   i y los demás tipos presentes en la página.
4. Alcanzar ambos extremos y comprobar que no se vuelve al otro extremo.
5. Buscar un campo con e: el contenido debe permanecer igual. Activarlo,
   escribir y volver mediante el menú o el gesto del teclado.
6. Abrir la ayuda, asignar un gesto adicional, cancelar otra captura y
   restablecer los gestos. Comprobar el menú y la salida reservados.
7. Probar Atrás, cambio de aplicación, bloqueo, giro y pausa.

Referencia de las letras:
[NVDA, navegación con una sola letra](https://download.nvaccess.org/documentation/en/keyCommands.html#SingleLetterNavigation).

## Versión de prueba instalada

El 25 de octubre de 2026 se comprobaron los XML, los 43 textos de la interfaz
en inglés, español y español latinoamericano, y sus parámetros de formato.
La compilación final terminó con BUILD SUCCESSFUL en 4 min 6 s, sin lint ni
pruebas unitarias. Registro local:
.git/preparacion/navegacion-web-braille-build-final.log.

APK: build/apks/cantalk-navegacion-web-braille-prueba.apk.
Versión TfPu_release_16_2-2026_10_25_0110, código 70000294, paquete
fyi.quin.backtalk.cantalktest. Firma APK v2 verificada con la clave de la
instalación existente. Instalación con adb install -r: Success.
El Samsung SM-S928B confirmó esa versión y la actualización a las 01:15:14.
CAN Talk quedó conectado como servicio de accesibilidad y sin servicios caídos.

Después de instalarla, el usuario probó la navegación en el teléfono y confirmó
que funciona correctamente el 25 de octubre de 2026.
