# Historial del portapapeles: primera implementación

Se abre desde el menú de can-talk o asignando **Historial del portapapeles** a un gesto
en **Personalizar gestos**, dentro de las acciones de edición de texto. No se cambia
la asignación actual de ningún gesto.

Con el historial abierto, los gestos funcionan en cualquier parte de la pantalla:

| Gesto | Acción |
| --- | --- |
| Un dedo hacia izquierda / derecha | Elemento anterior / siguiente |
| Un dedo hacia arriba / abajo | Alternar de forma cíclica entre General y Favoritos |
| Un toque | Copiar y cerrar el historial |
| Doble toque | Copiar y pegar en el campo de origen; cerrar el historial |
| Toque prolongado | Marcar o quitar favorito |
| Dos dedos hacia la izquierda | Cerrar |

Al abrir se dice «Historial abierto», sin leer automáticamente el primer elemento.
El primer deslizamiento horizontal lee el primer texto; los siguientes recorren la lista.
Al recorrer elementos se anuncia el texto y, al final, la posición y el total: «Texto. 3 de 10».
Al deslizar arriba o abajo se anuncia únicamente «Favoritos» o «General»; el primer
deslizamiento horizontal posterior lee el primer elemento de esa categoría.
General contiene todos los elementos;
Favoritos filtra los marcados. La lista no salta de posición cuando llega una nueva copia.
Al llegar a un extremo, se permanece en el mismo elemento.
Al cambiar de categoría, arriba y abajo recorren General → Favoritos → General,
incluso cuando Favoritos está vacío. Al cerrar se dice «Historial cerrado».
Copiar y pegar siempre cierran el historial y anuncian el cierre junto al resultado;
si falta el campo de origen, se anuncia el problema después del cierre.
Si el campo acepta el pegado, se dice «Historial cerrado. Elemento pegado».
Este anuncio breve se protege frente a la lectura automática del texto insertado;
el usuario puede interrumpirlo con el comando de detener la voz. Si el campo rechaza
la acción, se anuncia el fallo en lugar de confirmar el pegado.

El historial conserva hasta 100 textos, incluyendo hasta 50 favoritos. Al llenarse,
se descartan primero los elementos más antiguos que no sean favoritos. Las copias
repetidas se guardan una sola vez y conservan su marca. Se admite texto de hasta
20 000 caracteres; textos mayores e imágenes no se incorporan en esta versión.
La vista y la voz muestran una vista previa de hasta 500 caracteres; se copia y pega
el texto completo. Los datos quedan en el almacenamiento privado del teléfono,
disponible después del desbloqueo. No se recogen clips marcados como sensibles por
la aplicación que los copió.

Android 10 y posteriores restringen la lectura del portapapeles. Con el teclado braille
de can-talk como teclado predeterminado, la aplicación puede recoger nuevas copias.
Con otro teclado, solo se incorporan las que Android permita leer. No se importa el
historial previo del teclado Samsung ni se recuperan textos borrados por Android.

La interfaz de gestos requiere Android 11 o posterior. Al cerrarla se restaura la región
de toque del teclado braille o del modo de toque directo. Al pegar se verifica que el
campo de entrada original siga presente y tenga el foco. Si cambió, se anuncia el problema
sin intentar pegar en otro campo. La interfaz se cierra al bloquear, pausar el lector,
cambiar de aplicación o rotar la pantalla.

## Prueba en el teléfono

1. Con el teclado braille como predeterminado, copia dos textos distintos.
2. Asigna Historial del portapapeles al gesto que prefieras y ábrelo: debe decir solo «Historial abierto».
3. Recorre ambos textos y comprueba «1 de 2», «2 de 2». Mantén pulsado uno y desliza varias veces arriba o abajo: debe alternar «Favoritos», «General»; desliza horizontalmente para leer sus textos.
4. Copia con un toque; comprueba que el historial se cierre y anuncie «Historial cerrado. Copiado».
5. Ábrelo de nuevo y ciérralo con dos dedos a la izquierda. Vuelve a abrirlo desde un campo de texto.
6. Selecciona el otro texto y da doble toque: debe pegarse en ese campo y cerrarse.
7. Comprueba que el teclado braille y los gestos normales vuelvan a responder.
