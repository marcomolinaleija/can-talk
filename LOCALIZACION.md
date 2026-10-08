# Español de Latinoamérica

Los recursos de teléfono en `values-b+es+419` contienen la traducción regional al español de Latinoamérica. Android usa estos recursos también para variantes como `es-MX` y `es-US`; las pruebas `BrailleImeSpanishResourcesTest` verifican esa selección y los anuncios compuestos del teclado.

Esta revisión incorpora 396 cadenas y 5 grupos de plurales que faltaban: 366 recursos de can-talk, 34 del teclado braille y 1 de braille común. Incluye temas de sonido, IA en el dispositivo, actualizaciones, toque directo, pausa, estado del teléfono, orientación del teclado y cinco textos de ayuda con códigos QR usados por el reloj. Se mantienen las variables de formato, los identificadores XLIFF, los saltos de línea y las cantidades singular y plural.

Los textos visibles en las variantes española y latinoamericana usan `can-talk`. Los identificadores de recursos, nombres de clases, paquetes Android y enlaces de ayuda conservan sus valores técnicos. El nombre `Private Compute Services` corresponde a una app externa y se conserva.

La cobertura se cuenta sobre los recursos traducibles de los conjuntos `main` y `phone`, incluidos los específicos de versión de Android y los textos compartidos con el reloj. Se excluyen dependencias externas, archivos `donottranslate`, recursos `translatable="false"` y arreglos de referencias a otras cadenas. La compilación, las pruebas y la instalación se verifican para teléfono.

Para añadir mensajes, crea el recurso predeterminado y su equivalente en `values-b+es+419` del mismo módulo. Conserva los marcadores como `%1$s`, `%2$d` y `%%`, y los elementos `xliff:g`. Para los plurales en español, añade las cantidades `one`, `many` y `other`; usa el mismo texto plural para `many` y `other` cuando corresponda. Usa «configuración», «presiona», «vínculo» y «app» para mantener la terminología existente.

La lista de incidencias históricas de lint (`lint-baseline.xml`) conserva las traducciones pendientes en otros idiomas. Al completar estas traducciones, se retiró únicamente el español de los mensajes existentes; no se agregaron incidencias ni se desactivó la comprobación de traducciones.

En Windows, ejecuta las comprobaciones y compila el paquete de prueba siguiendo [COMPILACION.md](COMPILACION.md):

```powershell
.\compilar.ps1 -Prueba -Tareas @('testPhoneDebugUnitTest', ':assemblePhoneDebug', ':lintPhoneDebug')
```

Instala con `adb install -r` para conservar los ajustes de la app de prueba. El paquete es `fyi.quin.backtalk.cantalktest` y su etiqueta es «can-talk — prueba».
