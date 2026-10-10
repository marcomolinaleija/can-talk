/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.backup

import com.google.android.accessibility.talkback.backup.TalkBackBackup.PrefEntry
import com.google.android.accessibility.talkback.backup.TalkBackBackup.PrefType
import com.google.android.accessibility.talkback.soundthemes.SoundThemes
import com.google.android.accessibility.talkback.soundthemes.SoundThemeManifest
import org.json.JSONArray
import org.json.JSONObject

/** Version 2 groups data by user-selectable area and logical store. Version 1 remains readable. */
internal object BackupCodec {
  fun encode(document: BackupDocument, password: CharArray? = null): String {
    if (document.sections.isEmpty()) throw BackupException(BackupFailure.INVALID_FORMAT)
    if (document.containsSensitiveData && password == null)
      throw BackupException(BackupFailure.PASSWORD_REQUIRED)
    val root = JSONObject().put("format", TalkBackBackup.MAGIC_FORMAT).put("version", 2)
      .put("sourcePackage", document.sourcePackage).put("createdAt", document.createdAt)
    val sections = JSONArray()
    for ((category, section) in document.sections.toSortedMap(compareBy { it.id })) {
      val stores = JSONArray()
      for ((store, entries) in section.preferences.toSortedMap(compareBy { it.id })) {
        stores.put(JSONObject().put("id", store.id).put("preferences", encodePreferences(entries)))
      }
      val labels = JSONArray()
      section.labels.sortedWith(compareBy({ it.packageName }, { it.viewName }, { it.locale }, { it.packageVersion }))
        .forEach { label ->
          labels.put(JSONObject().put("packageName", label.packageName)
            .put("packageSignature", label.packageSignature).put("viewName", label.viewName)
            .put("text", label.text).put("locale", label.locale)
            .put("packageVersion", label.packageVersion).put("timestamp", label.timestamp))
        }
      val files = JSONArray()
      section.assets.sortedBy { it.path }.forEach {
        files.put(JSONObject().put("path", it.path).put("sha256", BackupJson.hash(it.bytes))
          .put("data", BackupCrypto.encode(it.bytes)))
      }
      sections.put(JSONObject().put("id", category.id).put("stores", stores)
        .put("labels", labels).put("files", files))
    }
    root.put("sections", sections)
    root.put("checksum", BackupJson.hash(BackupJson.canonical(root).toByteArray(Charsets.UTF_8)))
    val json = root.toString()
    if (json.toByteArray(Charsets.UTF_8).size > BackupJson.MAX_BYTES)
      throw BackupException(BackupFailure.TOO_LARGE)
    // Validate the complete structure on export too: do not create a file this version cannot read.
    decodeRoot(BackupJson.parse(json))
    return if (password == null) json else BackupCrypto.encrypt(json, password)
  }

  fun requiresPassword(json: String): Boolean = BackupCrypto.encrypted(BackupJson.parse(json))

  fun decode(json: String, password: CharArray? = null): BackupDocument {
    val root = BackupJson.parse(json)
    if (BackupCrypto.encrypted(root)) {
      if (password == null) throw BackupException(BackupFailure.PASSWORD_REQUIRED)
      val decoded = BackupJson.parse(BackupCrypto.decrypt(root, password))
      if (BackupCrypto.encrypted(decoded)) throw BackupException(BackupFailure.INVALID_FORMAT)
      return decodeRoot(decoded)
    }
    val document = decodeRoot(root)
    // Only legacy plaintext backups are allowed to contain credentials/history. The UI warns first.
    if (!document.legacy && document.containsSensitiveData)
      throw BackupException(BackupFailure.PASSWORD_REQUIRED)
    return document
  }

  private fun decodeRoot(root: JSONObject): BackupDocument {
    if (root.opt("format") != TalkBackBackup.MAGIC_FORMAT)
      throw BackupException(BackupFailure.INVALID_FORMAT)
    val version = BackupJson.integer(root.opt("version"))
    if (version == 1L) return decodeLegacy(root)
    if (version != 2L) throw BackupException(BackupFailure.UNSUPPORTED_VERSION)
    BackupJson.fields(root, setOf("format", "version", "sourcePackage", "createdAt", "sections", "checksum"))
    val checksum = BackupJson.string(root, "checksum", 64)
    root.remove("checksum")
    if (!BackupJson.hash(BackupJson.canonical(root).toByteArray(Charsets.UTF_8)).equals(checksum, true))
      throw BackupException(BackupFailure.CHECKSUM)
    val source = BackupJson.string(root, "sourcePackage", 255)
    if (!validPackage(source)) throw BackupException(BackupFailure.INVALID_FORMAT)
    val created = BackupJson.integer(root.opt("createdAt"))
    if (created < 0) throw BackupException(BackupFailure.INVALID_FORMAT)
    val array = root.optJSONArray("sections") ?: throw BackupException(BackupFailure.INVALID_FORMAT)
    if (array.length() !in 1..BackupCategory.entries.size) throw BackupException(BackupFailure.INVALID_FORMAT)
    val sections = linkedMapOf<BackupCategory, BackupSection>()
    var assetBytes = 0L
    var items = 0
    for (i in 0 until array.length()) {
      val obj = array.optJSONObject(i) ?: throw BackupException(BackupFailure.INVALID_FORMAT)
      BackupJson.fields(obj, setOf("id", "stores", "labels", "files"))
      val category = BackupCategory.fromId(BackupJson.string(obj, "id", 80))
        ?: throw BackupException(BackupFailure.UNSUPPORTED_VERSION)
      if (category in sections) throw BackupException(BackupFailure.INVALID_FORMAT)
      val stores = obj.optJSONArray("stores") ?: throw BackupException(BackupFailure.INVALID_FORMAT)
      if (stores.length() > BackupStore.entries.size) throw BackupException(BackupFailure.INVALID_FORMAT)
      val prefs = linkedMapOf<BackupStore, List<PrefEntry>>()
      for (j in 0 until stores.length()) {
        val storeObj = stores.optJSONObject(j) ?: throw BackupException(BackupFailure.INVALID_FORMAT)
        BackupJson.fields(storeObj, setOf("id", "preferences"))
        val store = BackupStore.fromId(BackupJson.string(storeObj, "id", 40))
          ?: throw BackupException(BackupFailure.UNSUPPORTED_VERSION)
        if (store in prefs) throw BackupException(BackupFailure.INVALID_FORMAT)
        val entries = decodePreferences(storeObj.optJSONObject("preferences")
          ?: throw BackupException(BackupFailure.INVALID_FORMAT))
        if (entries.isEmpty() || entries.any { BackupCategory.forKey(store, it.key) != category })
          throw BackupException(BackupFailure.INVALID_FORMAT)
        prefs[store] = entries
      }
      val labelArray = obj.optJSONArray("labels") ?: throw BackupException(BackupFailure.INVALID_FORMAT)
      val labels = (0 until labelArray.length()).map {
        decodeLabel(labelArray.optJSONObject(it) ?: throw BackupException(BackupFailure.INVALID_FORMAT))
      }
      if (labels.isNotEmpty() && category != BackupCategory.CUSTOM_LABELS
        || labels.map { it.identity() }.distinct().size != labels.size)
        throw BackupException(BackupFailure.INVALID_FORMAT)
      val fileArray = obj.optJSONArray("files") ?: throw BackupException(BackupFailure.INVALID_FORMAT)
      if (fileArray.length() > 1000) throw BackupException(BackupFailure.TOO_LARGE)
      val files = (0 until fileArray.length()).map {
        val file = fileArray.optJSONObject(it) ?: throw BackupException(BackupFailure.INVALID_FORMAT)
        BackupJson.fields(file, setOf("path", "sha256", "data"))
        val path = BackupJson.string(file, "path", 255)
        validateAssetPath(path)
        val data = BackupCrypto.decode(BackupJson.string(file, "data", 7 * 1024 * 1024))
        if (data.size > BackupJson.MAX_FILE_BYTES) throw BackupException(BackupFailure.TOO_LARGE)
        assetBytes += data.size
        if (assetBytes > BackupJson.MAX_ASSET_BYTES) throw BackupException(BackupFailure.TOO_LARGE)
        if (!BackupJson.hash(data).equals(BackupJson.string(file, "sha256", 64), true))
          throw BackupException(BackupFailure.CHECKSUM)
        BackupAsset(path, data)
      }
      if (files.isNotEmpty() && category != BackupCategory.SOUND_THEMES
        || files.map { it.path }.distinct().size != files.size)
        throw BackupException(BackupFailure.INVALID_FORMAT)
      if (category == BackupCategory.SOUND_THEMES) {
        // A theme is a complete portable directory, never a partial set of arbitrary paths.
        for ((_, themeFiles) in files.groupBy { it.path.substringBefore('/') }) {
          val manifest = themeFiles.singleOrNull { it.path.substringAfter('/') == "theme.json" }
            ?: throw BackupException(BackupFailure.INVALID_FORMAT)
          if (manifest.bytes.size > 1024 * 1024) throw BackupException(BackupFailure.TOO_LARGE)
          val manifestText = BackupJson.decode(manifest.bytes)
          BackupJson.parse(manifestText)
          // Use the owner's existing limits/defaults, including optional names and vibration data.
          try { SoundThemeManifest.parse(manifestText, SoundThemes.VIBRATION_NAMES,
            manifest.path.substringBefore('/')) }
          catch (_: Exception) { throw BackupException(BackupFailure.INVALID_FORMAT) }
        }
      }
      val section = BackupSection(prefs, labels, files)
      if (section.itemCount == 0) throw BackupException(BackupFailure.INVALID_FORMAT)
      items += section.itemCount
      if (items > BackupJson.MAX_ITEMS) throw BackupException(BackupFailure.TOO_LARGE)
      sections[category] = section
    }
    return BackupDocument(source, created, sections)
  }

  private fun decodeLegacy(root: JSONObject): BackupDocument {
    BackupJson.fields(root, setOf("format", "version", "createdAt", "sourcePackage", "checksum", "preferences"))
    val entries = decodePreferences(root.optJSONObject("preferences")
      ?: throw BackupException(BackupFailure.INVALID_FORMAT))
    if (!TalkBackBackup.computeChecksum(entries).equals(BackupJson.string(root, "checksum", 64), true))
      throw BackupException(BackupFailure.CHECKSUM)
    val source = BackupJson.string(root, "sourcePackage", 255)
    if (!validPackage(source)) throw BackupException(BackupFailure.INVALID_FORMAT)
    val grouped = entries.filter { BackupCategory.forKey(BackupStore.READER, it.key) != null }
      .groupBy { BackupCategory.forKey(BackupStore.READER, it.key)!! }
      .mapValues { BackupSection(mapOf(BackupStore.READER to it.value)) }
    if (grouped.isEmpty()) throw BackupException(BackupFailure.INVALID_FORMAT)
    return BackupDocument(source, BackupJson.integer(root.opt("createdAt")), grouped, legacy = true)
  }

  fun encodePreferences(entries: List<PrefEntry>): JSONObject {
    val obj = JSONObject()
    for (entry in entries.sortedBy { it.key }) {
      val value = if (entry.type == PrefType.STRING_SET) {
        @Suppress("UNCHECKED_CAST")
        JSONArray((entry.value as Set<String>).sorted())
      } else entry.value
      obj.put(entry.key, JSONObject().put("type", entry.type.typeString).put("val", value))
    }
    return obj
  }

  internal fun decodePreferences(obj: JSONObject): List<PrefEntry> {
    if (obj.length() > BackupJson.MAX_ITEMS) throw BackupException(BackupFailure.TOO_LARGE)
    return obj.keys().asSequence().toList().sorted().map { key ->
      if (key.isBlank() || key.length > 512 || key.any { it.isISOControl() })
        throw BackupException(BackupFailure.INVALID_FORMAT)
      val entry = obj.optJSONObject(key) ?: throw BackupException(BackupFailure.INVALID_FORMAT)
      BackupJson.fields(entry, setOf("type", "val"))
      val type = PrefType.fromString(BackupJson.string(entry, "type", 20))
        ?: throw BackupException(BackupFailure.INVALID_FORMAT)
      val raw = entry.opt("val")
      val value: Any = when (type) {
        PrefType.BOOLEAN -> raw as? Boolean ?: throw BackupException(BackupFailure.INVALID_FORMAT)
        PrefType.INT -> BackupJson.integer(raw).also {
          if (it !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw BackupException(BackupFailure.INVALID_FORMAT)
        }.toInt()
        PrefType.LONG -> BackupJson.integer(raw)
        PrefType.FLOAT -> (raw as? Number)?.toFloat()?.takeIf { it.isFinite() }
          ?: throw BackupException(BackupFailure.INVALID_FORMAT)
        PrefType.STRING -> BackupJson.string(entry, "val")
        PrefType.STRING_SET -> {
          val arr = raw as? JSONArray ?: throw BackupException(BackupFailure.INVALID_FORMAT)
          if (arr.length() > BackupJson.MAX_ITEMS) throw BackupException(BackupFailure.TOO_LARGE)
          val list = (0 until arr.length()).map {
            (arr.opt(it) as? String)?.takeIf { it.length <= BackupJson.MAX_STRING }
              ?: throw BackupException(BackupFailure.INVALID_FORMAT)
          }
          if (list.distinct().size != list.size) throw BackupException(BackupFailure.INVALID_FORMAT)
          list.toSet()
        }
      }
      PrefEntry(key, type, value)
    }
  }

  private fun decodeLabel(obj: JSONObject): BackupLabel {
    BackupJson.fields(obj, setOf("packageName", "packageSignature", "viewName", "text", "locale", "packageVersion", "timestamp"))
    val pkg = BackupJson.string(obj, "packageName", 255)
    val signature = BackupJson.string(obj, "packageSignature", 256)
    val view = BackupJson.string(obj, "viewName", 1024)
    val text = BackupJson.string(obj, "text", 20_000)
    val locale = BackupJson.string(obj, "locale", 100)
    val version = BackupJson.integer(obj.opt("packageVersion"))
    val timestamp = BackupJson.integer(obj.opt("timestamp"))
    if (!validPackage(pkg) || signature.isNotEmpty() && !signature.matches(Regex("[A-Fa-f0-9]{40,128}"))
      || view.isBlank() || text.isBlank()
      || version !in 0..Int.MAX_VALUE.toLong() || timestamp < 0)
      throw BackupException(BackupFailure.INVALID_FORMAT)
    return BackupLabel(pkg, signature, view, text, locale, version.toInt(), timestamp)
  }

  fun validateAssetPath(path: String) {
    val parts = path.split('/')
    if (parts.size != 2 || !parts[0].matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))
      || !parts[1].matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,190}"))
      || parts[1].contains("..") || (parts[1] != "theme.json" && !SoundThemes.isDocument(parts[1])
        && parts[1].substringAfterLast('.', "").lowercase() !in SoundThemes.EXTENSIONS))
      throw BackupException(BackupFailure.INVALID_FORMAT)
  }

  private fun validPackage(value: String): Boolean = value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
}
