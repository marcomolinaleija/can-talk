# Tutorial de NVDA Remote

El botón **Aprender a usar NVDA Remote** abre las lecciones sin conectar una
computadora. Al intentar conectar se ofrece iniciar o retomar el tutorial,
conectar sin lecciones o elegir **No mostrar de nuevo**. El aviso deja de salir
si se completaron las siete lecciones o si se eligió no mostrarlo. El botón de
acceso al tutorial sigue disponible cuando no hay una conexión abierta.

## Lecciones

1. Ubicar los puntos, abrir el menú y probar cómo pedir la salida.
2. Usar Tab, Shift+Tab, las cuatro flechas, Enter y Escape.
3. Preparar y cancelar Control; usar Control+A, Control+C y Control+V.
4. Abrir Inicio con Windows y Ejecutar con Windows+R.
5. Mantener Alt, recorrer al menos dos ventanas con Tab y soltar Alt para elegir.
6. Cambiar entre control y teclado, escribir una A, poner espacio, borrar y confirmar.
7. Silenciar y reactivar la voz remota en el teléfono.

Son 26 ejercicios. Cada uno prepara el estado que necesita: texto seleccionado
para copiar, texto para borrar, dos líneas para subir o bajar y un cuadro
abierto para practicar Escape. Solo se aprueba cuando la acción produce el
resultado esperado. Abrir el menú se practica con el menú real del tutorial;
se vuelve con **Seguir practicando**.

Cada ejercicio empieza con una explicación y el botón **Practicar**. Al acertar
se muestra el resultado con **Continuar**, **Repetir ejercicio** y
**Escuchar la explicación**. El siguiente ejercicio solo empieza cuando el
usuario elige continuar y luego practicar. Un comando distinto al esperado
recibe una respuesta de la simulación y permite intentarlo otra vez.

Se guarda cada ejercicio aprobado. Saltarlo permite seguir, pero no lo marca
como completado. Al retomar se ofrece el primer ejercicio pendiente de la
última lección abierta. Las lecciones completadas se pueden repetir.
**Reiniciar lecciones** pide confirmación y borra solo ese progreso.
**Volver a mostrar el aviso al conectar** permite deshacer la opción para
ocultar el aviso. Reiniciar las lecciones conserva esa preferencia.

## Gestos y salida

La práctica usa la misma superficie braille del control remoto, con la
disposición y los ajustes del usuario. Las instrucciones buscan los gestos
asignados en el modo del ejercicio y solo muestran puntos disponibles para la
configuración de seis u ocho puntos. Cuando una tecla no tiene un gesto
directo, se explica cómo elegirla desde el menú. Si falta una combinación
necesaria para mantener y deslizar, se indica que puede reasignarse o saltarse
el ejercicio; no se cambian las asignaciones del usuario.

El menú remoto también permite preparar Windows y los otros modificadores.
Así se puede practicar Windows+R sin asignar un gesto nuevo para Windows.

**Salir del tutorial**, el gesto de salida y Atrás piden confirmación con
**Seguir practicando** y **Salir del tutorial**. Durante la práctica, la
confirmación también permite abrir el **Menú de práctica**, incluso si el
usuario desactivó o cambió su gesto de menú. Los cuadros y las pantallas de
explicación o resultado usan la exploración táctil habitual.

Cambiar de aplicación detiene la superficie de práctica y libera su zona
táctil. Al volver se muestra la explicación del ejercicio. Los ejercicios
aprobados se conservan. Si ya se mostró **Correcto**, esa pantalla se conserva
al cambiar de aplicación o recrear la actividad; no se avanza por sí sola.
Regresar a conexión no inicia una conexión por sí solo.

## Simulación

La práctica libre ofrece Inicio, Ejecutar, un editor y tres ventanas. Permite
escribir, usar un portapapeles propio y cambiar de ventana. La voz del teléfono
describe las acciones. El silenciado de la simulación solo afecta las
respuestas de esa computadora, no las instrucciones del tutorial.
El editor tiene cursor, selección, borrado e inserción en la posición elegida.
Permite moverse por caracteres, líneas y palabras, seleccionar con Shift,
guardar una nota y volver a abrirla desde Documentos. El texto de práctica
tiene un límite de 2048 caracteres. Inicio y Ejecutar pueden abrir el editor;
Ejecutar acepta `editor`, `notepad` y `bloc de notas`. El navegador ofrece
controles de ejemplo y una página de ayuda local, sin navegar en Internet.

La entrada braille de la simulación usa letras básicas. Las otras celdas se
conservan como caracteres braille. No pretende traducir braille contraído ni
reproducir todas las tablas de NVDA. Las explicaciones avisan de esta diferencia.

El tutorial tiene su propia actividad y un simulador sin conexión de red ni
acceso al portapapeles real. No crea una sesión NVDA Remote, no envía teclas a
Windows y no cambia el modo ni el silenciado de una sesión real.

## Comprobación en el teléfono

- Abrir el tutorial sin completar servidor ni clave y practicar un ejercicio.
- Acertar, esperar y comprobar que no avanza solo; repetir y después continuar.
- Saltar un ejercicio, salir y retomar: el ejercicio saltado debe seguir pendiente.
- Pedir salir con el botón, el gesto y Atrás; cancelar debe mantener el tutorial.
- Cambiar de aplicación durante la práctica y volver a la explicación.
- Reasignar un gesto y comprobar que cambian las instrucciones. Repetir con seis puntos.
- Mantener Alt y enviar dos Tab sin soltarlo; la elección ocurre al liberar Alt.
- Probar el aviso al conectar, su opción para no mostrarlo y el acceso manual.
- Completar todas las lecciones y comprobar que el aviso deja de aparecer.

La compilación se realiza con `compilar.ps1 -Prueba -Tareas @(':assemblePhoneDebug')`,
sin lint ni pruebas unitarias. El APK independiente de prueba conserva la clave
debug local para poder actualizar la instalación del teléfono. Las compilaciones
habituales conservan la clave compartida del proyecto.

Primer prototipo, compilación del 24 de octubre de 2026: `BUILD SUCCESSFUL` en 8 min 29 s.
Versión `TfPu_release_16_2-2026_10_24_2331`, código `70000291`, paquete
`fyi.quin.backtalk.cantalktest`. Firma APK v2 verificada y actividad del tutorial
comprobada en el manifiesto del APK. Sus 89 textos estaban disponibles en inglés,
español y español latinoamericano, con los mismos nombres y sin duplicados.

APK: `build/apks/cantalk-nvda-remote-tutorial-prueba.apk`.
Registro: `.git/preparacion/nvda-remote-tutorial-build.log`.

La versión completa añade el simulador con estado y 123 textos por idioma.
Su registro de compilación es
`.git/preparacion/nvda-remote-tutorial-completo-build.log`.
Compilación del 25 de octubre de 2026: `BUILD SUCCESSFUL` en 6 min 18 s.
Versión `TfPu_release_16_2-2026_10_25_0000`, código `70000291`, paquete
`fyi.quin.backtalk.cantalktest`. Firma APK v2 verificada con la clave debug
local que coincide con la instalación del teléfono. La actividad del tutorial
está incluida en el manifiesto del APK y no se exporta.
Instalada con `adb install -r` en el Samsung SM-S928B conectado:
resultado `Success`. El teléfono confirmó esa versión y la actualización del
25 de octubre a las 00:07:41. CAN Talk siguió habilitado y conectado como
servicio de accesibilidad después de actualizar.
El usuario probó las lecciones en el teléfono y confirmó que todo funciona bien
el 25 de octubre de 2026. Se preparó una herramienta temporal de comprobación,
pero no se instaló ni se ejecutó.
