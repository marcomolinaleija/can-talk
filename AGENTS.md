# Reglas y Guías de Desarrollo para CAN Talk

Este documento contiene directivas obligatorias para cualquier agente o desarrollador que trabaje en el repositorio de **CAN Talk**.

---

## 🚀 Flujo de Publicación de Lanzamientos (Releases)

Al preparar y publicar una nueva versión en GitHub, se DEBEN seguir estrictamente las siguientes reglas:

### 1. Idioma de las Notas de Versión
- **Siempre en español**: Todas las notas de lanzamiento (`release notes`), títulos y resúmenes de cambios deben estar redactados en español claro, profesional y accesible.

### 2. Estructura Obligatoria del Cuerpo de la Release (Contrato del Actualizador)
El actualizador dentro de la app ([UpdateChecker.kt](talkback/src/main/java/com/google/android/accessibility/talkback/update/UpdateChecker.kt)) parsea el cuerpo (`body`) de la release mediante expresiones regulares. **Si no tiene este formato exacto al inicio, el actualizador fallará en los teléfonos de los usuarios con un error de JSON**:

```markdown
build: <número_total_de_commits>

- <hash_corto> <mensaje_del_commit>
- <hash_corto> <mensaje_del_commit>

## CAN Talk <versión>

<Notas de versión en español explicadas para el usuario final>
```

- **`build: <número>`**: Se obtiene ejecutando `git rev-list --count HEAD`.
- **Lista de cambios**: Líneas `- <hash_corto> <mensaje_en_español>`. **IMPORTANTE**: La app muestra el texto posterior al hash directamente en el diálogo de actualización del teléfono del usuario. Aunque los commits de Git estén en inglés, los mensajes de estas líneas deben redactarse siempre en español claro y comprensible.
- **Notas en español**: Separador y secciones con novedades, correcciones y mejoras detalladas.

### 3. Sincronización Doble en GitHub
CAN Talk utiliza dos referencias en GitHub Releases:
1. **La release oficial de versión**: Con su tag semántico (ej. `1.0.2`).
2. **La release del canal `dev`**: Tag `dev` (marcada como pre-release). **Este es el endpoint exacto que consulta el botón "Buscar actualizaciones" dentro de la app**. Ambas releases deben contener el mismo APK (`cantalk.apk`) y el mismo cuerpo estructurado.

### 4. Compilación y Firma del APK
- La compilación oficial de distribución se genera con:
  ```powershell
  pwsh -File .\compilar.ps1
  ```
  (ejecuta `:assemblePhoneDebug`, con `minifyEnabled = true`, `debuggable = false` y firma compartida `keystore/debug.keystore`).
- El archivo resultante (`build/outputs/apk/phone/debug/cantalk-phone-debug.apk`) se renombra a `cantalk.apk` para subirse como asset.
- **NO cambiar la firma a una release key no coordinada**: rompería las actualizaciones automáticas para todos los usuarios existentes (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

### 5. Bump de Versión en el Código
Antes de compilar la release, actualizar la versión en:
- `version.gradle` (`talkbackVersionName = "x.y.z"`)
- `talkback/src/main/res/values/strings_update.xml`
- `talkback/src/main/res/values-es/strings_update.xml`
- `talkback/src/main/res/values-b+es+419/strings_update.xml`
- `talkback/src/main/res/values-cs/strings_update.xml`
- `lint-baseline.xml`
