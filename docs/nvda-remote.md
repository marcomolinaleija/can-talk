# NVDA Remote: control y teclado braille

Rama: `codex/nvda-remote`, creada desde `codex/desarrollo`.

La conexión usa NVDA Remote v2: TCP con TLS y JSON UTF-8 separado por saltos
de línea. CAN Talk entra como `master`; la computadora con NVDA como `slave`.

## Conectar

En NVDA, elegir **Permitir controlar este equipo**. En CAN Talk, abrir
**NVDA Remote (experimental)** dentro de Controles y usar el mismo servidor,
puerto y clave. Se acepta `nvdaremote.com:6837`, o el servidor sin puerto y
el campo Puerto aparte. La clave distingue mayúsculas y espacios. No se
guarda en preferencias ni en el estado de la actividad. Mostrar clave permite
revisarla y vuelve a ocultarse al conectar. El teclado braille no añade
mayúsculas automáticas en contraseñas ni direcciones.

La pantalla distingue conexión al servidor de una computadora realmente
presente. Solo habilita comandos con una computadora controlada. Si hay varias,
se desconecta para evitar enviar comandos o mezclar voz de varias PCs.
Para certificados no verificables se permite comparar SHA-256 y confiar
en el certificado una vez; la sesión queda fijada a esa huella.

## Superficie braille y modos

**Abrir teclado remoto** muestra una superficie propia de esta pantalla,
sin abrir ni cambiar el IME. Usa la disposición, inversión de puntos,
orientación, acumulación y calibración de CAN Talk. Necesita Android 11 o
posterior para recibir toques directamente. Menús, salida y pérdida de foco
restauran la exploración táctil. Atrás vuelve al formulario.

Tres dedos abajo alternan **Modo control** y **Modo teclado**; tres dedos
arriba abren el menú; dos dedos abajo vuelven al formulario. Estas asignaciones
son configurables por modo. Atrás mantiene siempre su función de salida.

En control, tocar 6 prepara Control, 5 Alt, 3 Shift y 2 NVDA. Se acumulan,
se cancelan al tocarlos otra vez y se consumen en la siguiente tecla.
Los modificadores preparados permanecen en el teléfono: cada comando envía pulsaciones y liberaciones
completas a Windows. Cambiar de modo, salir, configurar o pausar limpia
los modificadores. Las letras braille básicas a-z envían sus teclas,
independientemente de la tabla usada para escribir texto. Punto 6 seguido de
c envía Ctrl+C; punto 2 seguido de t envía NVDA+T.
Las letras mantenidas que no coinciden con los acordes de navegación o
modificadores también se ejecutan al soltar, como F (124). Una combinación
con modificadores anuncia «Enviando Control + F», por ejemplo. Este anuncio
confirma que se encoló el comando, no que la PC lo haya ejecutado.

Los modificadores también pueden mantenerse físicamente. Mantener 5 hasta
la vibración pulsa Alt en Windows; deslizar desde 4 a la derecha envía Tab
sin soltar Alt. Repetir recorre más ventanas y deslizar desde 4 a la izquierda
envía Shift+Tab para retroceder. Soltar el punto 5 libera Alt y confirma la
ventana. Manteniendo 5, deslizar otros dos dedos a la izquierda envía Escape
y libera Alt para cancelar; hay que levantar el punto antes de iniciar otro
mantenimiento. Los nombres de las ventanas se escuchan mediante NVDA: no se
repite el anuncio local del atajo en cada deslizamiento.

Mantener 1 junto con 3 y deslizar desde 4 envía Shift+flechas para seleccionar;
mantener 1 junto con 6 envía Control+flechas para moverse por palabras. Se
pueden combinar hasta tres dedos mantenidos. Mantener únicamente 1 produce
la vibración de reconocimiento para la navegación, pero conserva su letra
al soltar si no se realizó un deslizamiento. En modo control, acordes formados
solo por modificadores y el punto de navegación se reservan para estas acciones;
las letras se siguen escribiendo con pulsaciones breves.

La acción de mantener sigue la asignación del punto; puede reasignarse o
desactivarse por separado capturando una pulsación mantenida. Tiene un ID
`hold_5`, por ejemplo. Una asignación explícita de un deslizamiento con puntos
mantenidos conserva la prioridad sobre la navegación derivada. Abrir menús,
cambiar de modo, salir, perder foco o pausar libera los modificadores.
La cola reserva espacio para liberaciones. Al cerrar normalmente, el escritor
intenta liberar las teclas realmente enviadas antes de cerrar TLS, con un
límite de 500 ms sin bloquear la interfaz. Una conexión rota no puede
garantizar que esos eventos lleguen a Windows.

| Gesto en control | Acción predeterminada |
| --- | --- |
| Mantener 1 y deslizar desde 4 | Flecha correspondiente |
| Deslizar desde 4 a derecha/izquierda | Tab / Shift+Tab |
| Deslizar desde 4 arriba/abajo | Página arriba / Página abajo |
| Un dedo a derecha/izquierda desde otro punto | Espacio / Retroceso |
| Dos dedos a derecha/izquierda | Enter / Escape |
| Dos dedos arriba | Control para detener voz |

En teclado, los puntos viajan como `braille_input` y NVDA los interpreta con
**su tabla de entrada braille**, sin usar el portapapeles. Un dedo a la derecha
envía espacio; a la izquierda borra una celda o carácter; arriba/abajo envía
izquierda/derecha. Dos dedos a la derecha confirman y envían Enter; arriba
confirman sin espacio; a la izquierda envían Ctrl+Retroceso. Tres dedos
derecha/izquierda envían Tab/Shift+Tab. Mantener 1 y deslizar desde 4 también
permite las flechas. En ocho puntos, 7 borra, 8 confirma y envía Enter,
y 7+8 confirman sin espacio.

Navegar confirma primero el braille pendiente en el mismo lote que la tecla;
cambiar de modo también lo confirma. La tabla de NVDA puede ser distinta a
la de CAN Talk. Una desconexión puede interrumpir un lote o dejar entrada
pendiente en NVDA. Salir, bloquear el teléfono o pasar a otra aplicación
cierra la sesión; los fallos requieren reconexión manual.

## Reasignación y aprendizaje

El botón **Aprender a usar NVDA Remote** abre un tutorial con una computadora
simulada, sin conexión. Incluye lecciones, práctica libre, progreso guardado y
salida con confirmación. Se ofrece antes de conectar mientras queden lecciones
pendientes, salvo que se elija **No mostrar de nuevo**.
Consulta [el tutorial de NVDA Remote](nvda-remote-tutorial.md).

**Reasignar gestos remotos** funciona incluso sin conectar una PC. Elegir un
modo muestra sus asignaciones y permite capturar un gesto nuevo. La captura
y el aprendizaje no envían comandos a la PC. Se admiten puntos, acordes,
deslizamientos de hasta tres dedos y puntos mantenidos con deslizamiento.
Se conserva el punto desde el que se desliza, incluso con puntos mantenidos.

Asignaciones específicas de un punto tienen prioridad sobre las generales.
Sin acción desactiva un gesto. Se puede elegir una acción o tecla con Control,
Alt, Shift, NVDA y Windows, incluidas letras, números y F1-F12. Las asignaciones
se guardan en preferencias independientes del teclado local, con IDs estables
y separados por modo. Restaurar valores predeterminados afecta solo al modo
elegido. Atrás cancela una captura y sigue disponible aunque se reasignen los
gestos de menú, salida o cambio de modo.

El menú ofrece cancelación de modificadores, aprendizaje y comandos menos
frecuentes. La tecla NVDA se puede cambiar entre Insert y Bloqueo de mayúsculas
en el formulario; debe coincidir con la PC. Leer todo usa NVDA+Flecha abajo,
correspondiente a la distribución de escritorio.

## Voz y otros controles

**Silenciar voz remota en este teléfono** detiene la lectura remota actual y
descarta la voz recibida hasta pulsar **Reactivar voz remota en este teléfono**.
Está disponible en el formulario, en el menú del teclado remoto y como acción
reasignable a un gesto en ambos modos. El aprendizaje anuncia la acción sin
cambiar el silenciado. Los controles y anuncios locales de CAN Talk siguen
hablando; los mensajes remotos de cancelación y pausa se ignoran mientras está
silenciado. El último texto recibido sigue visible en el formulario.
Reactivar reproduce solo mensajes nuevos, sin recuperar los descartados.
El silenciado se conserva al cambiar de modo o volver al formulario durante la
sesión y se restablece a voz activa al desconectar. No cambia el sonido de la PC.
Para comprobarlo en el teléfono: iniciar una lectura larga, silenciarla,
navegar en la PC y comprobar que los controles locales siguen hablando.
Reactivar debe permitir escuchar la siguiente lectura sin reproducir la anterior.
Repetir desde el menú y desde un gesto reasignado; en aprendizaje, el gesto
debe anunciarse sin cambiar el estado. Estas comprobaciones requieren una
sesión real con NVDA.

Se reproduce `speak` con la voz de CAN Talk, con cambios de idioma y deletreo,
y se atienden `cancel` y `pause_speech`. Las pausas y límites de frase se
aproximan mediante puntuación; las prioridades se simplifican a cola normal o
interrupción. La voz remota evita traducción automática e historial local.
No se transmiten pantalla ni audio original de la PC, ni se reproducen tonos,
archivos de sonido, todos los efectos de prosodia o salida a un dispositivo braille.

El campo de texto del formulario permite **Enviar texto y pegar**: reemplaza
el portapapeles de Windows y envía Ctrl+V a la aplicación enfocada. El anuncio
confirma que se encoló. Antes de conectar o enviar se confirma la escritura
braille local pendiente. Copiar al teléfono el portapapeles recibido requiere
una acción local explícita.

Los diagnósticos `CanTalkNvdaRemote` registran etapas de conexión, cantidad
de computadoras, clase de error, recepción de entrada, modo y cantidad de
modificadores preparados. No registran puntos, teclas, claves, voz ni portapapeles.
La cola y los mensajes tienen límites para no crecer indefinidamente.

## Compilar e instalar

Por indicación del usuario se compila sin lint ni pruebas:

```powershell
.\compilar.ps1 -Prueba -Tareas @(':assemblePhoneDebug')
```

El APK de prueba utiliza `fyi.quin.backtalk.cantalktest`.
Se conserva una copia en `build/apks/cantalk-nvda-remote-prueba.apk`.
El usuario confirmó que la conexión y los comandos del primer prototipo
funcionaron el 11 de octubre de 2026. La superficie braille y la reasignación
de esta iteración requieren comprobar su uso real en el teléfono y NVDA.

Actualización del 12 de octubre de 2026 compilada con `BUILD SUCCESSFUL` en
3 min 5 s, sin lint ni pruebas. Instalada mediante ADB con resultado `Success`
en el Samsung SM-S928B. Versión verificada en el dispositivo:
`TfPu_release_16_2-2026_10_12_0101`, código `70000271`.

Corrección de pulsaciones mantenidas y anuncio de atajos compilada el mismo
día con `BUILD SUCCESSFUL` en 5 min 35 s, sin lint ni pruebas. Instalada por
ADB con `Success` en el mismo dispositivo: `TfPu_release_16_2-2026_10_12_1208`,
actualización verificada a las 12:13:14. Pendiente confirmar el uso real de
Control+F reportado por el usuario; se añadieron diagnósticos de recepción y
modo para distinguir el reconocimiento local del envío remoto. El usuario
confirmó después que esta actualización funciona correctamente.

Modificadores físicos y navegación repetida compilados con `BUILD SUCCESSFUL`
en 3 min 59 s, sin lint ni pruebas, e instalados por ADB con `Success`.
Versión `TfPu_release_16_2-2026_10_12_1238`, código `70000271`, actualización
verificada a las 12:42:17 del 12 de octubre de 2026 en el Samsung SM-S928B.
Pendiente confirmar los nuevos gestos mantenidos con el usuario y NVDA.

Silenciado de voz remota en el teléfono añadido el 24 de octubre de 2026.
Compilación `BUILD SUCCESSFUL` en 5 min 22 s, sin lint ni pruebas; firma APK v2
verificada. Versión `TfPu_release_16_2-2026_10_24_2202`, código `70000271`.
APK: `build/apks/cantalk-nvda-remote-silenciado-prueba.apk`.
Registro: `.git/preparacion/nvda-remote-silenciar-build.log`.
Instalada posteriormente por ADB con `Success` en el Samsung SM-S928B.
Versión verificada en el dispositivo y actualización registrada a las
22:08:19 del 24 de octubre de 2026. Pendiente comprobar silenciado y
reactivación en una sesión real con NVDA.

Referencias de protocolo y comportamiento:
[sesiones](https://github.com/nvaccess/nvda/blob/master/source/_remoteClient/session.py),
[entrada remota](https://github.com/nvaccess/nvda/blob/master/source/_remoteClient/input.py),
[comandos braille](https://github.com/nvaccess/nvda/blob/master/source/globalCommands.py).
