# Copias de seguridad de CAN Talk

En **Copia de seguridad y restauración**, crear una copia permite marcar las áreas que se quieren
guardar. **Seleccionar todo** incluye las áreas disponibles; la clave de Gemini y el portapapeles
están desmarcados inicialmente porque contienen datos privados. La calibración braille también se
elige expresamente: normalmente solo conviene trasladarla al mismo dispositivo.

La copia puede guardar ajustes del lector, voz, audio, traducción e IA; gestos táctiles y de huellas;
atajos del teclado físico; ajustes del teclado y de la línea braille; gestos y calibración braille;
la clave personal de Gemini; emojis favoritos; historial y favoritos del portapapeles; etiquetas
personalizadas; y temas de sonido con sus archivos y licencias. Solo aparecen áreas con datos.
La clave de Gemini integrada al compilar no se exporta: únicamente la que haya guardado el usuario.

## Protección

Las copias que incluyen la clave o el portapapeles requieren una contraseña, confirmada dos veces.
También puede protegerse cualquier otra copia. Se cifra toda la copia, no solamente la clave.
Guarda la contraseña aparte: no puede recuperarse si se olvida. No compartas una copia sin proteger
que incluya textos privados, instrucciones de IA o etiquetas personales.

## Restauración

Se abre y valida el archivo antes de ofrecer las casillas. Si está cifrado, primero se desbloquea.
Solo se muestran las áreas encontradas. Los valores presentes en las áreas seleccionadas reemplazan
los valores correspondientes; no se eliminan ajustes ausentes ni se tocan áreas desmarcadas.
Las etiquetas se combinan sin duplicarlas; solo se sustituyen los temas incluidos en la selección.
Se conserva la lectura del formato antiguo, aunque ese formato no guardaba los ajustes braille.

La app recarga sus componentes. Si cambian ajustes de inicio, reinicia su propio proceso después
de completar la restauración y avisa antes de la breve interrupción de voz. No cambia los servicios
de accesibilidad habilitados ni el teclado predeterminado. Si Android impide recuperar la voz,
reinicia el lector desde los ajustes de accesibilidad. Un fallo de almacenamiento utiliza un diario
privado y cifrado para recuperar los datos previos. Si la app solicita reintentar la recuperación,
no la desinstales ni borres sus datos.

## Límites

No se copian permisos de Android, activación de servicios, motores de voz externos, modelos de IA
descargados ni cachés. Esos componentes se instalan o descargan por separado.
El límite es 128 MiB por archivo JSON, 64 MiB de archivos de temas, 5 MiB por archivo de sonido y
10 000 elementos guardados. La memoria disponible puede imponer un límite menor. Cuando se supera
un límite, se informa del error: no se genera una copia truncada. Selecciona menos áreas y crea
copias separadas. Importa únicamente copias de una fuente en la que confíes.
