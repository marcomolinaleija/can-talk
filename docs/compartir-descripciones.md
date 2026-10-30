# Compartir imágenes y descripciones

Las ventanas de descripción de imagen y pantalla incluyen un botón **Compartir**.
Abre estas opciones:

- **Compartir imagen**: envía la imagen usada para generar la descripción.
- **Compartir descripción**: envía el texto del resultado, incluidas las etiquetas
  de iconos y el texto reconocido cuando aparecen en ese resultado.
- **Compartir imagen y descripción**: envía ambos en una misma solicitud.

Después se abre el selector de aplicaciones de Android. El usuario elige la
aplicación y el destinatario. Cada aplicación decide cómo presentar el texto
que acompaña a una imagen.

Cancelar las opciones de compartir permite volver al resultado. Al abrir el
selector de Android se cierran las ventanas de descripción para poder usarlo.
Las opciones sin imagen o sin texto disponible quedan deshabilitadas.

Se comparte la imagen analizada, que puede ser el recorte de un elemento o una
captura de pantalla. No se toma una captura nueva de la ventana del resultado.
El texto compartido es la descripción inicial; no incluye las preguntas y
respuestas posteriores.

## Implementación

`CaptionSharing` reúne el texto y ofrece las tres opciones a los diálogos
simples y al panel con preguntas. `GeminiActor` conserva los bytes hasta abrir
el resultado; la descripción avanzada de pantalla también entrega su imagen
al panel.

La imagen se escribe fuera del hilo de interfaz, solo al elegir compartirla,
en la caché privada `shared_captures`. El FileProvider entrega una URI con
permiso de lectura al receptor. Los archivos de más de un día se eliminan al
preparar otra imagen. No se solicitan permisos de almacenamiento.

Los errores se muestran y anuncian dentro del menú, que permite reintentar.
Cancelar mientras se prepara el archivo impide abrir después el selector.
Se incluyen textos en inglés, español y español latinoamericano.

## Validación

El 25 de octubre de 2026 se comprobaron los siete XML afectados y los cinco
textos nuevos, con los mismos nombres en los tres idiomas. `git diff --check`
terminó sin errores. La revisión cubrió el paso de los bytes a los diálogos
simples, al panel de imagen y a las dos variantes de descripción de pantalla.

La compilación `:assemblePhoneDebug` con `-Prueba` terminó con
`BUILD SUCCESSFUL` en 6 min 29 s, sin ejecutar lint ni pruebas unitarias.
Registro local: `.git/preparacion/compartir-descripciones-build.log`.

APK: `build/apks/cantalk-compartir-descripciones-prueba.apk`. Paquete
`fyi.quin.backtalk.cantalktest`, versión
`TfPu_release_16_2-2026_10_25_0255`, código `70000298`. La firma APK v2 se
verificó y coincide con la de la instalación de pruebas existente.

`adb install -r` terminó con `Success`. El Samsung SM-S928B confirmó la nueva
versión y la actualización a las 03:02:19. CAN Talk volvió a aparecer conectado
como servicio de accesibilidad, sin servicios caídos.

Después de instalar la actualización, el usuario probó la función en el
teléfono y confirmó que funciona correctamente el 25 de octubre de 2026.
