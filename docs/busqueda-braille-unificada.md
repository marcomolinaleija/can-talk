# Búsqueda braille: aplicaciones y elementos

Rama: `codex/busqueda-braille-unificada`, creada desde `codex/desarrollo`.

En **Personalizar gestos**, la acción pasa a llamarse **Búsqueda braille**.
Se conserva su identificador y cualquier gesto ya asignado a la búsqueda de aplicaciones.
La interfaz se abre desde cualquier aplicación y utiliza los puntos, idioma, disposición,
calibración y modo de acumulación del teclado braille.

## Controles

| Gesto | Acción |
| --- | --- |
| Tres dedos a izquierda o derecha | Alternar cíclicamente entre Aplicaciones y Elementos |
| Un dedo arriba o abajo | Recorrer las coincidencias de forma cíclica |
| Un dedo a la izquierda | Borrar la última celda braille |
| Un dedo a la derecha | Añadir un espacio |
| Dos dedos a la derecha | Abrir la aplicación o activar el elemento elegido |
| Dos dedos a la izquierda o hacia abajo | Cerrar la búsqueda |
| Tres dedos hacia arriba | Escuchar la ayuda |
| Atrás | Cerrar la búsqueda |

Al abrir se anuncia «Búsqueda abierta. Aplicaciones» o «Búsqueda abierta. Elementos»,
según el último modo utilizado. No se lee automáticamente un resultado al abrir ni
al cambiar de modo. Al cambiar de modo se anuncia solo su nombre y se conserva el texto
escrito. Al recorrer coincidencias se anuncia el nombre y la posición: «Enviar. 2 de 5».
La primera navegación después de cambiar de modo lee la primera coincidencia.
Una consulta vacía no activa ningún resultado.

## Elementos

Se buscan nombres, texto, descripciones de accesibilidad, etiquetas personalizadas
y pistas de campos en la ventana original de la aplicación. Se omiten los nodos
ocultos y los campos de contraseña. La primera versión no recorre contenido fuera
de la pantalla mediante desplazamiento ni hace OCR.

Los botones se pulsan. Si un texto pertenece a una fila o botón pulsable, se activa
ese contenedor. Los campos de texto reciben la acción de pulsación o foco disponible.
El texto sin acción recibe el foco de accesibilidad y la lectura habitual de can-talk.

La ventana de búsqueda desaparece antes de activar un elemento. Se comprueba la ventana,
la disponibilidad, la visibilidad, el estado habilitado y las etiquetas del nodo y del
contenedor. Si cambiaron o desaparecieron, se anuncia el problema; no se elige otro
resultado para sustituirlo. Abrir otra búsqueda, cerrar, pausar el lector o apagar la
pantalla cancela las activaciones pendientes.

## Validación

Compilación de prueba completada y 30 comprobaciones específicas aprobadas: 9 del
teclado y la consulta braille y 21 de resultados, controlador y elementos. Las pruebas
automatizadas usan traducción simulada; el usuario confirmó el funcionamiento en el teléfono.

Instalada por ADB el 9 de octubre de 2026 en el Samsung SM-S928B. Paquete de prueba:
`fyi.quin.backtalk.cantalktest`, versión `70000253`.
APK local: `build/apks/can-talk-busqueda-braille-unificada-prueba.apk`.
SHA-256: `41FD6D6D7E9DFF65C6D7F7490390A20F7AF5A53E329F186134647553571F18BC`.
Validación manual confirmada por el usuario el 9 de octubre de 2026: «funciona perfectamente».

Las comprobaciones específicas cubren la traducción y los gestos existentes, la apertura
de aplicaciones, la conservación de la consulta y del modo, los anuncios, la búsqueda
de nodos, la activación de contenedores, los campos y el texto, y la cancelación cuando
cambia la ventana o desaparece el elemento.

Escenarios para futuras comprobaciones manuales: abrir con el gesto ya asignado, buscar y abrir una aplicación;
cambiar a Elementos, buscar un botón de Ajustes o WhatsApp y activarlo con dos dedos
a la derecha. Repetir con un campo de texto, texto sin acción y varias coincidencias.
Comprobar que la consulta se conserve al alternar y que al reabrir se recuerde el modo.
