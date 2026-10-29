# CAN Talk 1.0.0-rc.1: borrador de notas del candidato

Documento de preparación para probar antes de la versión definitiva. La fecha
y los enlaces de descarga se completarán después de validar los APK. No es un
anuncio de disponibilidad.

## Descripción

CAN Talk es un lector de pantalla para Android basado en Backtalk y en el código
abierto de TalkBack. Ofrece lectura por voz, navegación mediante gestos, teclado
braille y funciones de conexión con NVDA Remote. El proyecto es independiente
de Google.

## Archivos previstos

- `cantalk.apk`: teléfono Android, con Android 8.0 o posterior.
- Identificador de la aplicación: `can.cantalk`.
- Versión de teléfono: `1.0.0-rc.1`.
- Número de compilación, commit y huella del certificado: pendientes de completar
  con los datos del candidato verificado.
- Sumas SHA-256: pendientes de generar a partir de los archivos que se entregarán.

Este candidato se prepara exclusivamente para teléfono. La variante Wear OS
queda aplazada y no se incluye un APK de reloj.

## Instalación y configuración inicial

1. Instalar el APK correspondiente al dispositivo.
2. Abrir los ajustes de Accesibilidad de Android y localizar can-talk.
3. Cambiar del lector anterior a CAN Talk para evitar que ambos lectores hablen
   al mismo tiempo.
4. Habilitar por separado el teclado braille y los accesos de accesibilidad si
   se van a utilizar.

Las compilaciones anteriores de CAN Talk con identificador `fyi.quin.backtalk`
o `fyi.quin.backtalk.cantalktest` siguen siendo aplicaciones distintas. Sus
ajustes no se trasladan automáticamente; se puede exportar una copia desde
CAN Talk anterior e importarla en la aplicación nueva. No se importan
automáticamente los ajustes privados de TalkBack ni de otros lectores.

## Estado de las actualizaciones

El actualizador está conectado al repositorio del proyecto, que permanece
privado. La aplicación no incorpora credenciales de GitHub y todavía no dispone
de un canal público de descarga automática. El lugar de distribución y el canal
estable se indicarán antes de anunciar el lanzamiento. Una búsqueda fallida
puede mostrar un error de conexión; no confirma que la versión esté al día.

## Antes de publicar estas notas

- Completar la fecha y los enlaces del candidato verificado.
- Incluir solo funciones confirmadas en las pruebas del candidato.
- Adjuntar los APK verificados, sus SHA-256 y la huella pública del certificado.
- Añadir el medio de reporte de errores y la información de privacidad y licencias.
