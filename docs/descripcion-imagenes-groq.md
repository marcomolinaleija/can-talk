# Descripción de imágenes y pantallas con Groq

En Ajustes de can-talk → Descripciones automáticas, «IA para describir imágenes y
pantallas» permite elegir Gemini, Groq o el modelo del dispositivo. Las instalaciones
existentes conservan su proveedor. Para ejecutar un modelo local primero hay que
descargarlo o importarlo desde sus ajustes.

Groq tiene su propio campo de clave. La clave se obtiene en
https://console.groq.com/keys y se consulta en cada petición; no hace falta reiniciar
el lector. Elegir Groq no envía la clave de Gemini a ese servicio. Si falta la clave
o falla Groq, se informa del error sin cambiar de proveedor automáticamente.

Se reutilizan las capturas, el recorte de la imagen enfocada, los mensajes y las
preguntas de seguimiento existentes. La imagen se envía como JPEG en base64 a
`https://api.groq.com/openai/v1/chat/completions`. La descripción estructurada de
pantalla solicita JSON e incluye el contexto de accesibilidad. Con Groq se piden
solo los campos que muestra la interfaz: resumen, imágenes destacadas o respuesta.

Las preguntas posteriores, tanto escritas como dictadas, usan la misma imagen y
el historial de la conversación con el proveedor seleccionado. Los botones, las
etiquetas accesibles y el campo de escritura muestran «Preguntar a Groq», Gemini
o la IA local según esa selección. Las páginas de resultado, escucha y chat
actualizan esos textos al enlazar sus vistas; conservan el texto de una pregunta
que se esté escribiendo.

## Modelo y costo

Verificado en la documentación oficial el 30 de octubre de 2026:

- Modelo: `qwen/qwen3.8-27b`, con entrada de texto e imágenes, en vista previa.
- Es la opción con visión publicada para cuentas normales. Muse Spark Open
  requiere acceso empresarial; los antiguos Llama 4 Scout y Maverick se retiraron
  para cuentas sin contrato empresarial.
- Precio: US$0.80 por millón de tokens de entrada y US$4 por millón de salida.
- El mismo modelo está incluido en el plan gratuito. Sus límites publicados son
  30 solicitudes por minuto, 1000 al día, 8000 tokens por minuto y 200000 al día.
  La cuenta puede tener límites distintos. Los precios anteriores corresponden
  al plan de pago; la integración también acepta una clave gratuita.
- Groq cuenta cada imagen como 2048 tokens de entrada: unos US$0.00164 por la
  imagen, antes del texto y la respuesta. Una respuesta de 500 tokens agrega
  US$0.002. El costo real depende del contexto y del uso de la cuenta.
- Se usa el modo instruct sin razonamiento para reducir latencia y consumo. Las
  respuestas se limitan a 768 tokens y se solicitan menos de 250 palabras, tanto
  para imágenes como para pantalla en formato JSON. Esto evita reservar 2048 o
  4096 tokens de salida cuando la cuenta tiene un límite de 1000 tokens de salida
  por minuto. Los documentos largos se resumen indicando que se trata de un resumen.

Fuentes: [visión](https://console.groq.com/docs/vision),
[modelo](https://console.groq.com/docs/model/qwen/qwen3.8-27b),
[catálogo y precios](https://console.groq.com/docs/models),
[límites del plan gratuito](https://console.groq.com/docs/rate-limits),
[retiros](https://console.groq.com/docs/deprecations).

## Peticiones y errores

La clave solo viaja en la cabecera Authorization. Las respuestas no se guardan en
la caché de Volley. Si Groq rechaza una petición con HTTP 429, se permiten hasta
dos reintentos dentro del presupuesto original de 30 segundos. Se respeta
`retry-after`, o la espera indicada en el mensaje del servidor. Para un límite
de tokens por minuto también puede usarse `x-ratelimit-reset-tokens`. Sin una
indicación utilizable se esperan dos y cuatro segundos. Una espera indicada por
el servidor superior a veinte segundos se anuncia sin reintentar automáticamente;
nunca se acorta. Las peticiones que agotan el tiempo de espera no se reenvían.

Mientras siga vigente la pausa indicada por Groq, otro intento manual informa
del tiempo pendiente sin enviar otra petición. Los límites diarios y de cuenta
no disparan reintentos automáticos. Tampoco se repite una petición que Groq
identifica como demasiado grande: esperar no reduce su tamaño. Se distingue este
caso y se conservan solo los contadores numéricos de tokens solicitados y límite.
Los mensajes distinguen esos límites de la
limitación temporal de solicitudes/tokens. Los límites publicados por Groq se
comparten por organización; las cabeceras de solicitudes restantes corresponden
al día y las de tokens restantes al minuto. Algunas cuentas tienen límites
separados de entrada (ITPM) y salida (OTPM). El saldo TPM combinado no garantiza
que quede capacidad de salida; su cabecera de reinicio tampoco se usa como espera
para los límites separados.

Cancelar o sustituir una petición cancela también su reintento pendiente e impide
entregar su respuesta anterior. Un cambio de proveedor o de clave durante la
espera impide reenviar la petición antigua. Se registran únicamente el estado
HTTP y metadatos derivados (tipo de límite, espera y contadores); el cuerpo de
error de Groq, la clave y el identificador de organización no se conservan ni
se anuncian. La clave se incluye en las copias únicamente en la categoría
sensible de claves de API.

Las pruebas locales comprueban el contrato HTTP con una cola sin red, el formato
multimodal y JSON, cancelación, aislamiento de claves y compatibilidad de las
preferencias. Una respuesta real y su calidad visual necesitan una clave válida
de Groq y una prueba en el dispositivo.

### Verificación en el dispositivo, 30 de octubre de 2026

El rechazo real mostraba 2048 tokens solicitados frente a un límite de 1000,
aunque quedaba saldo en la cabecera TPM combinada. Tras reducir la reserva a
768 se completaron tres descripciones, con 436, 241 y 165 tokens de salida.
La segunda encontró un límite OTPM explícito de 1000, esperó los tres segundos
indicados por Groq y se completó en el primer reintento. El usuario confirmó
que las tres funcionaron. Las 106 pruebas de Groq y errores pasaron, incluidas
las de pantalla estructurada y las de reintento en Android 10 y 11.

## Instalación actual

Se compila la versión debug normal, con el identificador habitual `can.cantalk`:

```powershell
.\compilar.local.ps1 -Tareas @(':assemblePhoneDebug')
```

La instalación actualiza ese paquete y conserva «can-talk — prueba», que usa
`fyi.quin.backtalk.cantalktest`. No se vuelven a instalar variantes adicionales.
