# NVDA Remote: control y teclado braille

Rama: `codex/nvda-remote`, creada desde `codex/desarrollo`.

La conexión usa NVDA Remote v2: TCP con TLS y JSON UTF-8 separado por saltos
de línea. CAN Talk entra como `master`; la computadora con NVDA como `slave`.

## Conectar

En NVDA, elegir **Permitir controlar este equipo**. En CAN Talk, abrir
**NVDA Remote (experimental)** dentro de Controles y usar el mismo servidor,
puerto y clave. Se acepta `nvdaremote.com:6837`, o el servidor sin puerto y
el campo Puerto aparte. La clave distingue mayúsculas y espacios. No se
guarda en preferencias ni en el estado de la actividad. Solo se conserva si se
elige **Recordar clave en este teléfono** al guardar un equipo. Mostrar clave permite
revisarla y vuelve a ocultarse al conectar. El teclado braille no añade
mayúsculas automáticas en contraseñas ni direcciones.

La pantalla distingue conexión al servidor de una computadora realmente
presente. Solo habilita comandos con una computadora controlada. Si hay varias,
se desconecta para evitar enviar comandos o mezclar voz de varias PCs.
Para certificados no verificables se permite comparar SHA-256 y confiar
en el certificado una vez o confiar permanentemente en el servidor guardando
su huella por nombre de host. Si el certificado cambia, se vuelve a solicitar
confirmación.

## Equipos guardados

El formulario añade una casilla **Guardar este equipo**, desmarcada al abrir,
y un botón **Equipos guardados**. La lista y la edición permanecen en diálogos
aparte, sin añadir filas por computadora a la pantalla principal.

Al marcar la casilla y pulsar Conectar se pide el nombre y se permite elegir
**Recordar clave en este teléfono**. Esta opción también empieza desmarcada
para un equipo nuevo. Se guardan servidor, puerto y tecla NVDA solo cuando
la computadora controlada está presente. Una conexión fallida o un servidor
sin PC no crean registros. Si la sesión ya está lista, marcar la casilla
permite guardarla sin desconectar.

La lista pone primero el último equipo usado y ofrece **Conectar a nombre**
y **Opciones de nombre**. Las opciones permiten editar o eliminar, con
confirmación para eliminar. Si no se guardó la clave, se pide al conectar.
Cambiar desde una sesión activa requiere confirmar su desconexión. Se
mantiene la invitación al tutorial cuando corresponde.

Al seleccionar un equipo, la casilla cambia a **Actualizar equipo: nombre**.
Marcarla actualiza ese registro sin duplicarlo. **Conexión manual**, dentro
de la lista, vuelve al formulario sin un equipo seleccionado para guardar uno
nuevo. No se admiten nombres repetidos. Los gestos y la acción al bloquear
siguen siendo ajustes generales, independientes de cada computadora.

Editar permite cambiar nombre, servidor, puerto, clave recordada y tecla NVDA.
La clave está oculta y se puede revelar explícitamente. Desmarcar Recordar
clave y guardar elimina la clave de ese registro. Editar no cambia los datos
de una conexión en curso; se aplican al siguiente intento.

`NvdaRemoteComputersStore` guarda un archivo cifrado AES-GCM con una clave de
Android Keystore en el directorio privado `noBackupFilesDir`. No utiliza
preferencias ni los almacenes exportados por la copia de seguridad de CAN Talk.
Las lecturas y escrituras se realizan fuera del hilo de interfaz; la escritura
es atómica y los errores no sustituyen una lista que no se pueda descifrar.
`NvdaRemoteComputersDialog` contiene la lista y los formularios secundarios.
Referencia: [Android Keystore y AES-GCM](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec).

## Superficie braille y modos

**Abrir teclado remoto** muestra una superficie propia de esta pantalla,
sin abrir ni cambiar el IME. Se ejecuta en modo inmersivo a pantalla completa,
ocultando las barras del sistema (recientes, inicio y atrás) para evitar toques
accidentales en los bordes que interrumpan la conexión. Usa la disposición,
inversión de puntos, orientación, acumulación y calibración de CAN Talk. Necesita
Android 11 o posterior para recibir toques directamente. Menús, salida y pérdida
de foco restauran la exploración táctil y las barras del sistema. Atrás vuelve al formulario.

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
**su tabla de entrada braille**, sin usar el portapapeles. Además, este modo
respeta las asignaciones personalizadas de gestos de escritura configuradas en
el teclado braille de CAN Talk (por ejemplo, punto 5 a la izquierda para emojis,
punto 1 a la izquierda para borrar palabras, etc.), traduciéndolas a sus acciones
y atajos equivalentes en Windows. Un dedo a la derecha
envía espacio; a la izquierda borra una celda o carácter; arriba/abajo envía
izquierda/derecha. Dos dedos a la derecha confirman y envían Enter; arriba
confirman sin espacio; a la izquierda envían Ctrl+Retroceso. Tres dedos
derecha/izquierda envían Tab/Shift+Tab. Mantener 1 y deslizar desde 4 también
permite las flechas. En ocho puntos, 7 borra, 8 confirma y envía Enter,
y 7+8 confirman sin espacio.

Navegar confirma primero el braille pendiente en el mismo lote que la tecla;
cambiar de modo también lo confirma. La tabla de NVDA puede ser distinta a
la de CAN Talk. Una desconexión puede interrumpir un lote o dejar entrada
pendiente en NVDA. Salir, ir a Inicio o Recientes y usar otra aplicación conserva
la sesión y la voz remota. Al bloquear se aplica la opción elegida en el
formulario. Los fallos requieren reconexión manual.

## Al bloquear la pantalla

El botón **Al bloquear pantalla** cambia entre tres opciones con cada pulsación
y guarda la elección para las siguientes sesiones:

- **Desconectar**: cierra la sesión. Es el valor inicial.
- **Solo silenciar**: conserva la conexión y descarta la voz remota mientras la
  pantalla esté apagada o bloqueada. Al desbloquear vuelve al estado de voz
  anterior; un silenciado manual sigue activo. No reproduce mensajes atrasados.
- **No hacer nada y seguir conectado**: conserva la conexión y el estado de la
  voz, incluido el silenciado manual si estaba activo.

En los dos modos que conservan la conexión, el teclado remoto se retira y las
teclas mantenidas se liberan al bloquear. Los gestos y los diálogos del
portapapeles remoto no se activan sobre la pantalla de bloqueo. Al volver se
muestra el formulario, donde se puede abrir de nuevo el teclado remoto.
La conexión pertenece al lector y se mantiene al salir de Remote, incluso con
Atrás, y al volver se recuperan el estado, la voz silenciada y el equipo activo.
Los gestos y las teclas mantenidas se liberan al dejar la pantalla. El botón
**Desconectar**, desactivar CAN Talk o un fallo de red terminan la sesión. La
clave de la conexión se conserva en memoria; solo se guarda en disco si se
acepta recordarla para un equipo. No hay reconexión automática.

La detección combina los avisos de pantalla apagada, encendida y desbloqueada
con el estado interactivo y el bloqueo del sistema. La detección sigue activa
mientras exista una conexión, aunque no se muestre Remote. Referencias de Android:
[avisos de pantalla](https://developer.android.com/reference/android/content/Intent#ACTION_SCREEN_OFF)
y [estado de bloqueo](https://developer.android.com/reference/android/app/KeyguardManager#isKeyguardLocked()).

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

Comportamiento de bloqueo añadido el 25 de octubre de 2026. Se verificaron
los tres XML y los nombres y parámetros de los cinco textos nuevos en inglés,
español y español latinoamericano. `git diff --check` terminó sin errores.
Compilación `:assemblePhoneDebug` con `-Prueba`: `BUILD SUCCESSFUL` en
7 min 19 s, sin lint ni pruebas unitarias. Registro:
`.git/preparacion/nvda-bloqueo-pantalla-build.log`.

APK: `build/apks/cantalk-nvda-bloqueo-prueba.apk`. Versión
`TfPu_release_16_2-2026_10_25_1932`, código `70000300`, paquete
`fyi.quin.backtalk.cantalktest`. Firma APK v2 verificada con la clave de la
instalación existente. `adb install -r` terminó con `Success`; el Samsung
SM-S928B confirmó la versión y la actualización a las 19:40:17. CAN Talk
quedó conectado como servicio de accesibilidad, sin servicios caídos.

Pendiente probar las tres opciones con una sesión real: bloquear y desbloquear,
comprobar que el silenciado manual se conserva, salir a otra aplicación y
bloquear manteniendo un modificador. También falta comprobar la persistencia
de la elección al volver a abrir NVDA Remote. Estas pruebas de uso no se han
realizado todavía.

Equipos guardados añadidos el 25 de octubre de 2026. Se verificaron los seis
XML afectados y los 23 textos nuevos con los mismos nombres y parámetros en
los tres idiomas. La revisión del formulario confirma dos controles nuevos:
la casilla de guardar/actualizar y el botón que abre la lista.

Compilación `:assemblePhoneDebug` con `-Prueba`: `BUILD SUCCESSFUL` en
6 min 35 s, sin lint ni pruebas unitarias de Gradle. Registro:
`.git/preparacion/nvda-equipos-guardados-build.log`.
APK: `build/apks/cantalk-nvda-equipos-guardados-prueba.apk`. Versión
`TfPu_release_16_2-2026_10_25_2018`, código `70000300`. Firma APK v2 verificada
con la clave de pruebas existente. `adb install -r` terminó con `Success` y el
Samsung SM-S928B confirmó la actualización a las 20:26:14. CAN Talk quedó
conectado, sin servicios de accesibilidad caídos.

Un verificador temporal utilizó las clases compiladas de producción del almacén
en una aplicación aislada, sin permisos de red ni acceso a los datos de CAN Talk.
Pasaron 23 comprobaciones en Android: crear y volver a leer, conservar una clave
con espacios y Unicode, no guardar una clave opcional, ausencia de campos en
texto plano, ubicación fuera de copias automáticas, nombres duplicados,
actualización sin duplicar, eliminación de la clave recordada, orden por uso,
borrado persistente, IPv6, validación de direcciones y puertos, rechazo de un
archivo alterado y conservación de un archivo que no se puede descifrar.
El verificador fue desinstalado después de las comprobaciones.

La prueba de navegación de la interfaz quedó inconclusa porque el teléfono
estaba en reposo y mostraba la pantalla de bloqueo. Se observó la casilla
desmarcada como control marcable, pero no se confirma el recorrido completo
por la lista. Quedan pendientes las pruebas con una PC real: guardar solo tras
conectar, conectar con y sin clave recordada, editar y eliminar desde la lista,
y confirmar el cambio desde otra sesión activa.

El usuario confirmó el funcionamiento de equipos guardados. Detectó después
que ir a Inicio, Recientes u otra pantalla desconectaba la sesión.

La corrección separa la conexión y la voz de `NvdaRemoteActivity`. Las pantallas
se vinculan mientras están visibles y se desvinculan al detenerse, sin cerrar
TLS. La conexión se cierra explícitamente o al desactivar el servicio lector.
Los datos de conexión y el silenciado se mantienen en memoria al recrear la
pantalla; los gestos y las teclas mantenidas se liberan al dejarla.

Un verificador temporal reutilizó las clases compiladas de conexión y protocolo
en una aplicación con datos aislados y un servidor TLS local de prueba. Pasaron
23 comprobaciones: conexión verificada, voz recibida sin una pantalla vinculada,
restauración del estado al vincular otra pantalla, silenciado y reactivación sin
interfaz, las tres opciones de bloqueo, conservación del silenciado manual al
desbloquear, desconexión explícita, cierre al apagar el lector y fallo de red
sin interfaz. La salida de voz se contó con un sustituto del sintetizador; no
se usó una computadora real con NVDA. El verificador se desinstaló y se retiró
el reenvío de red después de la prueba.

Compilación de la corrección: `:assemblePhoneDebug` con `-Prueba`,
`BUILD SUCCESSFUL` en 6 min 34 s, sin lint ni pruebas unitarias de Gradle.
Registro: `.git/preparacion/nvda-session-background-build.log`.
APK: `build/apks/cantalk-nvda-conexion-persistente-prueba.apk`.
Versión `TfPu_release_16_2-2026_10_25_2114`, código `70000300`. Firma
verificada con la clave de pruebas existente. `adb install -r` terminó con
`Success`; el Samsung SM-S928B confirmó la actualización a las 21:22:28.
CAN Talk quedó vinculado como servicio de accesibilidad, sin servicios caídos.
Los seis XML de Remote y equipos guardados se analizaron sin errores y
`git diff --check` pasó. Queda la comprobación de uso con la PC del usuario:
Inicio, Recientes, otra aplicación, Atrás y volver a Remote.

Referencias de protocolo y comportamiento:
[sesiones](https://github.com/nvaccess/nvda/blob/master/source/_remoteClient/session.py),
[entrada remota](https://github.com/nvaccess/nvda/blob/master/source/_remoteClient/input.py),
[comandos braille](https://github.com/nvaccess/nvda/blob/master/source/globalCommands.py).
