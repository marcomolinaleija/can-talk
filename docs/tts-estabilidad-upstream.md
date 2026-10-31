# Estabilidad de voz frente a upstream

Jansen informa que CAN Talk cambia de sintetizador después de unos minutos con
la pantalla apagada, con cualquier motor, en un HONOR X6b Plus con Android 14.
Desactivar audio de baja latencia no evita el cambio. En Backtalk no lo observa.
La versión concreta de Backtalk y el estado de la preferencia guardada durante
el cambio no están confirmados. No se dispone de un registro de ese teléfono.

## Comparación y alcance

Comparación del código de CAN Talk `fe7eccd5` con `upstream/master` `8ac94608`.
La recuperación que cambia de motor tras tres fallos está en ambos lectores;
su existencia no explica por sí sola la diferencia del informe. Upstream también
tiene cambios de audio después de llamadas (#201 y #340), pero dependen de otra
ruta y no se incorporan como explicación del caso con baja latencia desactivada.

Se adapta solo la parte de seguridad de voz de upstream
`238ec8bb2fee3daf03de8c088d3e31d5187caedd` (#313), en estos tres archivos:

- `FailoverTextToSpeech.java`: ignorar identificadores nulos, conservar el ID de
  cada solicitud en espera, continuar la cola cuando falla un audio de caché y
  retirar los fragmentos de motores o solicitudes que ya terminaron.
- `SpeechCacheManager.java`: aceptar sin excepción los callbacks sin ID.
- `SpeechControllerImpl.java`: manejar ID nulos y aislar los parámetros de cada
  anuncio, conservando la preparación de traducción propia de CAN Talk.

No se fusionan las funciones de perfiles de voz ni otros cambios de upstream.
Estas correcciones reparan fallos reproducibles, pero todavía no prueban la
causa del cambio de motor en el HONOR. Hace falta repetir el caso en ese teléfono.

## Reproducción local

`SpeechUtteranceSafetyTest` prueba ocho casos en Android 10 (API 29) y Android 14
(API 34), con audio de baja latencia desactivado y un motor de prueba sin red.
Antes de la corrección fallaron las 16 ejecuciones:

- Registrar un ID nulo, recibir un inicio sin ID y analizar un ID nulo producen
  excepciones. Los callbacks de caché sin ID también fallan.
- Tras terminar un audio de caché, la siguiente solicitud se envía con el ID del
  audio anterior, en vez de su propio ID.
- Tras fallar un audio de caché, la solicitud que esperaba no se ejecuta.

La validación incluye además las pruebas existentes de traducción de voz,
progreso, división de anuncios y ajuste de volumen en llamadas.
Después de adaptar la corrección pasan las 16 ejecuciones nuevas y las otras
61 pruebas seleccionadas: 77 en total, sin fallos ni errores.

## APK para comprobar el caso

Compilación `:assemblePhoneDebug` correcta. APK preparado en
`build/apks/can-talk-tts-estabilidad-debug.apk`, con paquete `can.cantalk`, versión
`1.0.2` y código `70000332`. Se verificó la firma y coincide con el APK anterior.
No se ha instalado en el dispositivo Samsung conectado; la comprobación pendiente
es repetir el uso con la pantalla apagada en el HONOR de Jansen.

SHA-256: `D3F192170E24E7C40DF522580BAA2EB01E7D334851B19EA65BBA6AF955B332A9`.
