/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.backup

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.UserManager
import com.google.android.accessibility.talkback.backup.TalkBackBackup.PrefEntry
import com.google.android.accessibility.talkback.backup.TalkBackBackup.PrefType
import com.google.android.accessibility.talkback.labeling.LabelProvider
import com.google.android.accessibility.talkback.remote.NvdaRemoteComputer
import com.google.android.accessibility.talkback.remote.NvdaRemoteComputersStore
import com.google.android.accessibility.talkback.soundthemes.SoundThemes
import com.google.android.accessibility.utils.labeling.LabelsTable
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Coordinates portable data without copying private DB files or trusting paths from a backup. */
internal object SelectiveBackup {
  private val lock = Any()
  private val labelsUri: Uri get() = Uri.parse("content://${LabelProvider.AUTHORITY}/labels")

  /** Counts, not preference values, labels or secrets, are shown in the selection dialog. */
  fun inventory(context: Context): Map<BackupCategory, Int> = synchronized(lock) {
    unlocked(context)
    recoverPending(context)
    val result = linkedMapOf<BackupCategory, Int>()
    for (store in BackupStore.entries) {
      for ((key, value) in store.open(context).all) {
        if (value == null) continue
        val category = BackupCategory.forKey(store, key) ?: continue
        result[category] = (result[category] ?: 0) + 1
      }
    }
    val labelCount = context.contentResolver.query(labelsUri, arrayOf(LabelsTable.KEY_ID),
      "${LabelsTable.KEY_SOURCE_TYPE} != ?", arrayOf("2"), null)?.use { it.count }
      ?: throw BackupException(BackupFailure.IO)
    if (labelCount > BackupJson.MAX_ITEMS) throw BackupException(BackupFailure.TOO_LARGE)
    if (labelCount > 0) result[BackupCategory.CUSTOM_LABELS] = labelCount
    val files = themeFiles(context)
    if (files.isNotEmpty()) result[BackupCategory.SOUND_THEMES] = files.size
    val computers = runCatching { NvdaRemoteComputersStore(context).list() }.getOrNull().orEmpty()
    if (computers.isNotEmpty()) result[BackupCategory.NVDA_REMOTE_COMPUTERS] = computers.size
    result
  }

  /** Called only after selection, so unselected sensitive data is not copied into the document. */
  fun capture(context: Context, selected: Set<BackupCategory>): BackupDocument = synchronized(lock) {
    unlocked(context)
    recoverPending(context)
    if (selected.isEmpty()) throw BackupException(BackupFailure.INVALID_FORMAT)
    val grouped = linkedMapOf<BackupCategory, MutableMap<BackupStore, List<PrefEntry>>>()
    for (store in BackupStore.entries) {
      val entries = store.open(context).all.mapNotNull { (key, value) ->
        if (BackupCategory.forKey(store, key) in selected && value != null) {
          entry(key, value).also {
            validateEntry(context, store, it, value)
            if (store == BackupStore.BRAILLE) BackupBrailleSupport.validate(it)
          }
        } else null
      }.groupBy { BackupCategory.forKey(store, it.key)!! }
      for ((category, values) in entries) grouped.getOrPut(category) { linkedMapOf() }[store] = values
    }
    val sections = grouped.mapValues { BackupSection(preferences = it.value) }.toMutableMap()
    if (BackupCategory.CUSTOM_LABELS in selected) {
      val labels = labelRows(context).filter { it.sourceType != 2 }.map { it.label }
      if (labels.isNotEmpty()) sections[BackupCategory.CUSTOM_LABELS] = BackupSection(labels = labels)
    }
    if (BackupCategory.SOUND_THEMES in selected) {
      val files = themeFiles(context)
      var total = 0L
      val assets = files.map { file ->
        total += file.length()
        checkMemoryBudget(total)
        if (total > BackupJson.MAX_ASSET_BYTES || file.length() > BackupJson.MAX_FILE_BYTES)
          throw BackupException(BackupFailure.TOO_LARGE)
        val bytes = file.inputStream().use { input ->
          val output = java.io.ByteArrayOutputStream()
          val buffer = ByteArray(8192)
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > BackupJson.MAX_FILE_BYTES) throw BackupException(BackupFailure.TOO_LARGE)
            output.write(buffer, 0, count)
          }
          output.toByteArray()
        }
        BackupAsset("${file.parentFile!!.name}/${file.name}", bytes)
      }
      if (assets.isNotEmpty()) sections[BackupCategory.SOUND_THEMES] = BackupSection(assets = assets)
    }
    if (BackupCategory.NVDA_REMOTE_COMPUTERS in selected) {
      val computers = NvdaRemoteComputersStore(context).list().map { pc ->
        BackupRemoteComputer(pc.id, pc.name, pc.host, pc.port, pc.nvdaModifier, pc.password, pc.lastUsed)
      }
      if (computers.isNotEmpty()) sections[BackupCategory.NVDA_REMOTE_COMPUTERS] = BackupSection(computers = computers)
    }
    if (sections.isEmpty()) throw BackupException(BackupFailure.INVALID_FORMAT)
    BackupDocument(context.packageName, System.currentTimeMillis(), sections)
  }

  /** Merge only selected present entries. Unselected areas and missing preferences stay intact. */
  fun restore(context: Context, document: BackupDocument, selected: Set<BackupCategory>): Int = synchronized(lock) {
    unlocked(context)
    recoverPending(context)
    val data = document.selected(selected)
    val byStore = linkedMapOf<BackupStore, MutableList<PrefEntry>>()
    for (section in data.sections.values) for ((store, entries) in section.preferences) {
      byStore.getOrPut(store) { mutableListOf() }.addAll(entries)
    }
    val stores = byStore.keys.associateWith { it.open(context) }
    val snapshots = linkedMapOf<BackupStore, Map<String, Any?>>()
    for ((store, entries) in byStore) {
      val current = stores.getValue(store).all
      if (entries.map { it.key }.distinct().size != entries.size) throw BackupException(BackupFailure.INVALID_FORMAT)
      val snapshot = linkedMapOf<String, Any?>()
      for (value in entries) {
        validateEntry(context, store, value, current[value.key])
        if (store == BackupStore.BRAILLE) BackupBrailleSupport.validate(value)
        snapshot[value.key] = current[value.key]?.let { entry(value.key, it).value }
      }
      snapshots[store] = snapshot
    }
    val labelPlan = planLabels(context, data.sections[BackupCategory.CUSTOM_LABELS]?.labels.orEmpty())
    val themePlan = ThemePlan(context, data.sections[BackupCategory.SOUND_THEMES]?.assets.orEmpty())
    val hasComputers = BackupCategory.NVDA_REMOTE_COMPUTERS in data.sections
    val importedComputers = data.sections[BackupCategory.NVDA_REMOTE_COMPUTERS]?.computers.orEmpty()
    val computerStore = if (hasComputers) NvdaRemoteComputersStore(context) else null
    val oldComputers = computerStore?.list().orEmpty()
    val touchedStores = mutableListOf<BackupStore>()
    var labelsCommitted = false
    var computersCommitted = false
    try {
      // Stage all file bytes before modifying any live data.
      themePlan.prepare()
      BackupRecovery.write(context, recoveryJournal(snapshots, labelPlan, themePlan, if (hasComputers) oldComputers else null))
      for ((store, entries) in byStore) {
        touchedStores.add(store) // commit(false) may still have changed its in-memory map.
        val editor = stores.getValue(store).edit()
        entries.forEach { put(editor, it) }
        if (!editor.commit()) throw BackupException(BackupFailure.WRITE_FAILED)
      }
      themePlan.install()
      if (labelPlan.operations.isNotEmpty()) {
        context.contentResolver.applyBatch(LabelProvider.AUTHORITY, labelPlan.operations)
        labelsCommitted = true
      }
      if (hasComputers && importedComputers.isNotEmpty()) {
        computerStore!!.merge(importedComputers.map { pc ->
          NvdaRemoteComputer(pc.id, pc.name, pc.host, pc.port, pc.nvdaModifier, pc.password, pc.lastUsed)
        })
        computersCommitted = true
      }
      // Check disk commits' visible values before reporting success.
      for ((store, values) in byStore) {
        if (values.any { stores.getValue(store).all[it.key] != it.value })
          throw BackupException(BackupFailure.WRITE_FAILED)
      }
      val count = data.sections.values.sumOf { it.itemCount }
      BackupRecovery.clear(context) // Commit boundary: recovery stops rolling back only here.
      // Cleanup cannot turn an already committed transaction into a partial rollback.
      try { themePlan.finish() } catch (_: Exception) { /* Private staging can be cleaned later. */ }
      count
    } catch (e: Exception) {
      var rolledBack = true
      if (computersCommitted) try {
        computerStore?.replaceAll(oldComputers)
      } catch (_: Exception) { rolledBack = false }
      if (labelsCommitted) try {
        rollbackLabels(context, labelPlan)
      } catch (_: Exception) { rolledBack = false }
      try { themePlan.rollback() } catch (_: Exception) { rolledBack = false }
      for (store in touchedStores.asReversed()) try {
        val prefs = stores.getValue(store)
        val editor = prefs.edit()
        for ((key, value) in snapshots.getValue(store)) {
          if (value == null) editor.remove(key) else put(editor, entry(key, value))
        }
        if (!editor.commit() || snapshots.getValue(store).any { prefs.all[it.key] != it.value }) rolledBack = false
      } catch (_: Exception) { rolledBack = false }
      if (!rolledBack) throw BackupException(BackupFailure.ROLLBACK_FAILED)
      if (BackupRecovery.pending(context)) BackupRecovery.clear(context)
      throw if (e is BackupException) e else BackupException(BackupFailure.WRITE_FAILED)
    }
  }

  /** Called on reader startup/unlock and before backup work; interrupted imports are undone first. */
  @JvmStatic
  fun recoverPending(context: Context): Boolean = synchronized(lock) {
    if (!context.getSystemService(UserManager::class.java).isUserUnlocked || !BackupRecovery.pending(context))
      return@synchronized false
    try {
      val journal = BackupRecovery.read(context)
      BackupJson.fields(journal, setOf("version", "preferences", "oldLabels", "insertedLabels", "themes", "computers"))
      if (BackupJson.integer(journal.opt("version")) != 1L) throw BackupException(BackupFailure.UNSUPPORTED_VERSION)
      val prefs = journal.getJSONObject("preferences")
      for (id in prefs.keys()) {
        val store = BackupStore.fromId(id) ?: throw BackupException(BackupFailure.INVALID_FORMAT)
        val snapshot = prefs.getJSONObject(id)
        val old = BackupCodec.decodePreferences(snapshot.getJSONObject("old"))
        val missing = snapshot.getJSONArray("missing")
        val target = store.open(context)
        val editor = target.edit()
        old.forEach { put(editor, it) }
        for (i in 0 until missing.length()) editor.remove(missing.getString(i))
        if (!editor.commit() || old.any { target.all[it.key] != it.value }
          || (0 until missing.length()).any { target.contains(missing.getString(it)) })
          throw BackupException(BackupFailure.ROLLBACK_FAILED)
      }
      val operations = arrayListOf<ContentProviderOperation>()
      val oldLabels = journal.getJSONArray("oldLabels")
      for (i in 0 until oldLabels.length()) {
        val row = oldLabels.getJSONObject(i)
        val values = ContentValues()
        val obj = row.getJSONObject("values")
        for (column in obj.keys()) {
          val value = obj.get(column)
          if (value == JSONObject.NULL) { values.putNull(column); continue }
          when (column) {
            LabelsTable.KEY_PACKAGE_VERSION, LabelsTable.KEY_SOURCE_TYPE -> values.put(column, BackupJson.integer(value).toInt())
            LabelsTable.KEY_TIMESTAMP -> values.put(column, BackupJson.integer(value))
            else -> values.put(column, value as String)
          }
        }
        operations.add(ContentProviderOperation.newUpdate(ContentUris.withAppendedId(labelsUri,
          BackupJson.integer(row.opt("id")))).withValues(values).withExpectedCount(1).build())
      }
      val inserted = journal.getJSONArray("insertedLabels")
      val identities = (0 until inserted.length()).map { index ->
        val array = inserted.getJSONArray(index)
        (0 until array.length()).map { array.getString(it) }
      }.toSet()
      if (identities.isNotEmpty()) for (row in labelRows(context)) {
        if (row.sourceType == 1 && row.label.identity() in identities)
          operations.add(ContentProviderOperation.newDelete(ContentUris.withAppendedId(labelsUri, row.id))
            .withExpectedCount(1).build())
      }
      if (operations.isNotEmpty()) context.contentResolver.applyBatch(LabelProvider.AUTHORITY, operations)
      if (journal.has("computers")) {
        val compArray = journal.getJSONArray("computers")
        val compList = (0 until compArray.length()).map {
          val obj = compArray.getJSONObject(it)
          NvdaRemoteComputer(
            obj.getString("id"), obj.getString("name"), obj.getString("host"),
            obj.getInt("port"), obj.getInt("nvdaModifier"),
            if (obj.isNull("password")) null else obj.getString("password"), obj.getLong("lastUsed")
          )
        }
        NvdaRemoteComputersStore(context).replaceAll(compList)
      }
      ThemePlan.recover(context, journal.getJSONObject("themes"))
      BackupRecovery.clear(context)
      true
    } catch (_: Exception) { throw BackupException(BackupFailure.ROLLBACK_FAILED) }
  }

  private fun recoveryJournal(
    snapshots: Map<BackupStore, Map<String, Any?>>,
    labels: LabelPlan,
    themes: ThemePlan,
    computers: List<NvdaRemoteComputer>? = null
  ): JSONObject {
    val prefs = JSONObject()
    for ((store, values) in snapshots) prefs.put(store.id, JSONObject()
      .put("old", BackupCodec.encodePreferences(values.mapNotNull { (key, value) -> value?.let { entry(key, it) } }))
      .put("missing", JSONArray(values.filterValues { it == null }.keys.toList())))
    val oldLabels = JSONArray()
    labels.oldRows.forEach { row ->
      oldLabels.put(JSONObject().put("id", row.id).put("values", JSONObject(
        row.values().valueSet().associate { it.key to (it.value ?: JSONObject.NULL) })))
    }
    val root = JSONObject().put("version", 1).put("preferences", prefs).put("oldLabels", oldLabels)
      .put("insertedLabels", JSONArray(labels.inserted.map { JSONArray(it.identity()) }))
      .put("themes", themes.journal())
    if (computers != null) {
      val compArray = JSONArray()
      for (pc in computers) {
        compArray.put(JSONObject()
          .put("id", pc.id).put("name", pc.name).put("host", pc.host).put("port", pc.port)
          .put("nvdaModifier", pc.nvdaModifier).put("password", pc.password ?: JSONObject.NULL)
          .put("lastUsed", pc.lastUsed))
      }
      root.put("computers", compArray)
    }
    return root
  }

  private fun validateEntry(context: Context, store: BackupStore, value: PrefEntry, current: Any?) {
    if (BackupCategory.forKey(store, value.key) == null) throw BackupException(BackupFailure.INVALID_FORMAT)
    val expected = BackupPreferenceTypes.expected(context, store, value.key)
    if (expected != null && expected != value.type
      || expected == null && current != null && entry(value.key, current).type != value.type)
      throw BackupException(BackupFailure.TYPE_MISMATCH)
    if (store == BackupStore.EMOJI || store == BackupStore.CLIPBOARD
      || store == BackupStore.NVDA_REMOTE_GESTURES || store == BackupStore.NVDA_REMOTE_SETTINGS
      || value.key == "pref_bd_gesture_action_key"
      || value.key in setOf("pref_gemini_api_key", "pref_groq_api_key", "pref_on_device_ai_provider")
      || (value.key.startsWith("pref_chatgpt_") && value.key != "pref_chatgpt_token_expires_at")
      || value.key.startsWith("pref_brailleime_calibration_points_") || value.key.startsWith("pref_shortcut_")) {
      if (value.type != PrefType.STRING) throw BackupException(BackupFailure.TYPE_MISMATCH)
    }
    if (value.key == "pref_chatgpt_token_expires_at" && value.type != PrefType.LONG) {
      throw BackupException(BackupFailure.TYPE_MISMATCH)
    }
    if (store == BackupStore.NVDA_REMOTE_GESTURES) {
      val action = value.value as String
      if (action.length > 256 || action.any { it.isISOControl() }) throw BackupException(BackupFailure.INVALID_FORMAT)
    }
    if (store == BackupStore.NVDA_REMOTE_SETTINGS) {
      val str = value.value as String
      if (str.length > 512 || str.any { it.isISOControl() }) throw BackupException(BackupFailure.INVALID_FORMAT)
      when (value.key) {
        "input_mode" -> if (str !in listOf("CONTROL", "KEYBOARD")) throw BackupException(BackupFailure.INVALID_FORMAT)
        "lock_behavior" -> if (str !in listOf("DISCONNECT", "MUTE", "KEEP")) throw BackupException(BackupFailure.INVALID_FORMAT)
        "port" -> if (str.toIntOrNull() !in 1..65535) throw BackupException(BackupFailure.INVALID_FORMAT)
        "host" -> if (str.isBlank() || str.length > 253) throw BackupException(BackupFailure.INVALID_FORMAT)
      }
    }
    if (value.key.endsWith("|key_combo_code") && value.type != PrefType.LONG)
      throw BackupException(BackupFailure.TYPE_MISMATCH)
    if ((value.key.endsWith("|trigger_modifier_used") || value.key.endsWith("|always_use_trigger_modifier"))
      && value.type != PrefType.BOOLEAN) throw BackupException(BackupFailure.TYPE_MISMATCH)
    if (store == BackupStore.CLIPBOARD) {
      val history = BackupJson.parse("{\"entries\":${value.value as String}}")
      BackupJson.fields(history, setOf("entries"))
      val array = history.optJSONArray("entries") ?: throw BackupException(BackupFailure.INVALID_FORMAT)
      if (array.length() > 100) throw BackupException(BackupFailure.INVALID_FORMAT)
      val seen = mutableSetOf<String>()
      var favorites = 0
      for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: throw BackupException(BackupFailure.INVALID_FORMAT)
        BackupJson.fields(obj, setOf("text", "favorite"))
        val text = BackupJson.string(obj, "text", 20_000)
        if (text.isBlank() || !seen.add(text) || obj.opt("favorite") !is Boolean)
          throw BackupException(BackupFailure.INVALID_FORMAT)
        if (obj.opt("favorite") == true) favorites++
      }
      if (favorites > 50) throw BackupException(BackupFailure.INVALID_FORMAT)
    }
  }

  fun entry(key: String, value: Any): PrefEntry = when (value) {
    is Boolean -> PrefEntry(key, PrefType.BOOLEAN, value)
    is Int -> PrefEntry(key, PrefType.INT, value)
    is Long -> PrefEntry(key, PrefType.LONG, value)
    is Float -> if (value.isFinite()) PrefEntry(key, PrefType.FLOAT, value) else throw BackupException(BackupFailure.INVALID_FORMAT)
    is String -> PrefEntry(key, PrefType.STRING, value)
    is Set<*> -> {
      if (value.any { it !is String }) throw BackupException(BackupFailure.INVALID_FORMAT)
      @Suppress("UNCHECKED_CAST")
      PrefEntry(key, PrefType.STRING_SET, (value as Set<String>).toSet())
    }
    else -> throw BackupException(BackupFailure.INVALID_FORMAT)
  }

  fun put(editor: SharedPreferences.Editor, entry: PrefEntry) {
    when (entry.type) {
      PrefType.BOOLEAN -> editor.putBoolean(entry.key, entry.value as Boolean)
      PrefType.INT -> editor.putInt(entry.key, entry.value as Int)
      PrefType.LONG -> editor.putLong(entry.key, entry.value as Long)
      PrefType.FLOAT -> editor.putFloat(entry.key, entry.value as Float)
      PrefType.STRING -> editor.putString(entry.key, entry.value as String)
      PrefType.STRING_SET -> {
        @Suppress("UNCHECKED_CAST")
        editor.putStringSet(entry.key, entry.value as Set<String>)
      }
    }
  }

  private fun unlocked(context: Context) {
    if (!context.getSystemService(UserManager::class.java).isUserUnlocked)
      throw BackupException(BackupFailure.STORAGE_LOCKED)
  }

  private fun checkMemoryBudget(bytes: Long) {
    val runtime = Runtime.getRuntime()
    val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
    if (bytes * 8 > available) throw BackupException(BackupFailure.TOO_LARGE)
  }

  private fun themesRoot(context: Context): File = File(BackupStore.deviceContext(context).filesDir, "sound_themes")

  private fun themeFiles(context: Context): List<File> {
    val root = themesRoot(context)
    if (Files.isSymbolicLink(root.toPath())) throw BackupException(BackupFailure.INVALID_FORMAT)
    val files = mutableListOf<File>()
    for (directory in root.listFiles().orEmpty().sortedBy { it.name }) {
      if (directory.name.startsWith(".")) continue // Never export unfinished sound imports.
      if (!directory.isDirectory || !File(directory, "theme.json").isFile) continue
      ensureContained(root, directory)
      for (file in directory.listFiles().orEmpty().sortedBy { it.name }) {
        if (!file.isFile) continue
        ensureContained(root, file)
        BackupCodec.validateAssetPath("${directory.name}/${file.name}")
        files.add(file)
        if (files.size > 1000) throw BackupException(BackupFailure.TOO_LARGE)
      }
    }
    return files
  }

  private fun ensureContained(root: File, file: File) {
    if (Files.isSymbolicLink(file.toPath()) || !file.canonicalPath.startsWith(root.canonicalPath + File.separator))
      throw BackupException(BackupFailure.INVALID_FORMAT)
  }

  private data class LabelRow(val id: Long, val label: BackupLabel, val sourceType: Int, val originalValues: ContentValues) {
    fun values(): ContentValues = ContentValues(originalValues)
  }

  private fun labelRows(context: Context): List<LabelRow> {
    val result = mutableListOf<LabelRow>()
    var estimatedBytes = 0L
    val cursor = context.contentResolver.query(labelsUri, LabelsTable.ALL_COLUMNS, null, null, null)
      ?: throw BackupException(BackupFailure.IO)
    cursor.use {
      if (it.count > BackupJson.MAX_ITEMS) throw BackupException(BackupFailure.TOO_LARGE)
      while (it.moveToNext()) {
        val values = ContentValues()
        for (column in LabelsTable.ALL_COLUMNS) if (column != LabelsTable.KEY_ID) {
          val index = it.getColumnIndexOrThrow(column)
          if (it.isNull(index)) values.putNull(column)
          else if (column == LabelsTable.KEY_PACKAGE_VERSION || column == LabelsTable.KEY_SOURCE_TYPE)
            values.put(column, it.getInt(index))
          else if (column == LabelsTable.KEY_TIMESTAMP) values.put(column, it.getLong(index))
          else values.put(column, it.getString(index))
        }
        fun text(column: String) = values.getAsString(column) ?: ""
        fun number(column: String) = values.getAsLong(column) ?: 0L
        estimatedBytes += values.valueSet().sumOf { value -> (value.value as? String)?.length?.toLong() ?: 8L } * 2
        BackupJson.checkMemoryBudget(estimatedBytes)
        result.add(LabelRow(it.getLong(it.getColumnIndexOrThrow(LabelsTable.KEY_ID)), BackupLabel(
          text(LabelsTable.KEY_PACKAGE_NAME), text(LabelsTable.KEY_PACKAGE_SIGNATURE), text(LabelsTable.KEY_VIEW_NAME),
          text(LabelsTable.KEY_TEXT), text(LabelsTable.KEY_LOCALE), number(LabelsTable.KEY_PACKAGE_VERSION).toInt(),
          number(LabelsTable.KEY_TIMESTAMP)), number(LabelsTable.KEY_SOURCE_TYPE).toInt(), values))
      }
    }
    return result
  }

  private fun labelValues(label: BackupLabel) = ContentValues().apply {
    put(LabelsTable.KEY_PACKAGE_NAME, label.packageName)
    put(LabelsTable.KEY_PACKAGE_SIGNATURE, label.packageSignature)
    put(LabelsTable.KEY_VIEW_NAME, label.viewName)
    put(LabelsTable.KEY_TEXT, label.text)
    put(LabelsTable.KEY_LOCALE, label.locale)
    put(LabelsTable.KEY_PACKAGE_VERSION, label.packageVersion)
    put(LabelsTable.KEY_TIMESTAMP, label.timestamp)
    put(LabelsTable.KEY_SOURCE_TYPE, 1) // Imported, not an inactive original/backup row.
    put(LabelsTable.KEY_SCREENSHOT_PATH, "")
  }

  private data class LabelPlan(
    val operations: ArrayList<ContentProviderOperation>,
    val oldRows: List<LabelRow>,
    val inserted: List<BackupLabel>,
  )

  private fun planLabels(context: Context, labels: List<BackupLabel>): LabelPlan {
    val operations = arrayListOf<ContentProviderOperation>()
    if (labels.isEmpty()) return LabelPlan(operations, emptyList(), emptyList())
    val current = labelRows(context).filter { it.sourceType != 2 }.groupBy { it.label.identity() }
    val oldRows = mutableListOf<LabelRow>()
    val inserted = mutableListOf<BackupLabel>()
    for (label in labels) {
      val matching = current[label.identity()].orEmpty()
      if (matching.isEmpty()) {
        inserted.add(label)
        operations.add(ContentProviderOperation.newInsert(labelsUri).withValues(labelValues(label)).build())
      } else for (row in matching) {
        oldRows.add(row)
        operations.add(ContentProviderOperation.newUpdate(ContentUris.withAppendedId(labelsUri, row.id))
          .withValues(labelValues(label)).withExpectedCount(1).build())
      }
    }
    return LabelPlan(operations, oldRows, inserted)
  }

  private fun rollbackLabels(context: Context, plan: LabelPlan) {
    val operations = arrayListOf<ContentProviderOperation>()
    plan.oldRows.forEach {
      operations.add(ContentProviderOperation.newUpdate(ContentUris.withAppendedId(labelsUri, it.id))
        .withValues(it.values()).withExpectedCount(1).build())
    }
    val identities = plan.inserted.map { it.identity() }.toSet()
    for (row in labelRows(context)) if (row.sourceType == 1 && row.label.identity() in identities) {
      operations.add(ContentProviderOperation.newDelete(ContentUris.withAppendedId(labelsUri, row.id))
        .withExpectedCount(1).build())
    }
    if (operations.isNotEmpty()) context.contentResolver.applyBatch(LabelProvider.AUTHORITY, operations)
  }

  /** Directory swaps preserve manifests/sounds as a unit; only named imported themes are replaced. */
  private class ThemePlan(context: Context, val files: List<BackupAsset>) {
    private val root = themesRoot(context)
    private val session = UUID.randomUUID().toString()
    private val staging = File(BackupStore.deviceContext(context).noBackupFilesDir, "backup-staging/$session")
    private val grouped = files.groupBy { it.path.substringBefore('/') }
    private val existing = grouped.keys.filter { File(root, it).exists() }.toSet()
    private val swapped = mutableListOf<String>()
    private val movedOriginals = mutableListOf<String>()

    fun journal(): JSONObject = JSONObject().put("session", session)
      .put("ids", JSONArray(grouped.keys.toList())).put("existing", JSONArray(existing.toList()))

    fun prepare() {
      if (files.isEmpty()) return
      if (!staging.mkdirs()) throw BackupException(BackupFailure.IO)
      for ((id, assets) in grouped) {
        val directory = File(staging, "new/$id")
        if (!directory.mkdirs()) throw BackupException(BackupFailure.IO)
        for (asset in assets) {
          BackupCodec.validateAssetPath(asset.path)
          File(directory, asset.path.substringAfter('/')).outputStream().use {
            it.write(asset.bytes)
            it.fd.sync()
          }
        }
      }
    }

    fun install() {
      if (files.isEmpty()) return
      if (!root.isDirectory && !root.mkdirs()) throw BackupException(BackupFailure.IO)
      File(staging, "old").mkdirs()
      for (id in grouped.keys) {
        val target = File(root, id)
        ensureContained(root, target)
        if (target.exists()) {
          if (!target.renameTo(File(staging, "old/$id"))) throw BackupException(BackupFailure.WRITE_FAILED)
          movedOriginals.add(id)
        }
        if (!File(staging, "new/$id").renameTo(target)) throw BackupException(BackupFailure.WRITE_FAILED)
        swapped.add(id)
      }
    }

    fun rollback() {
      for (id in swapped.asReversed()) {
        val target = File(root, id)
        ensureContained(root, target)
        if (!target.deleteRecursively()) throw BackupException(BackupFailure.ROLLBACK_FAILED)
      }
      for (id in movedOriginals.asReversed()) {
        if (!File(staging, "old/$id").renameTo(File(root, id))) throw BackupException(BackupFailure.ROLLBACK_FAILED)
      }
      finish()
    }

    fun finish() {
      if (staging.exists()) {
        // Both roots are application-owned and UUID-derived, never strings from the document.
        ensureContained(staging.parentFile!!, staging)
        staging.deleteRecursively()
      }
    }

    companion object {
      fun recover(context: Context, journal: JSONObject) {
        val session = BackupJson.string(journal, "session", 36)
        if (!session.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
          throw BackupException(BackupFailure.INVALID_FORMAT)
        val root = themesRoot(context)
        val staging = File(BackupStore.deviceContext(context).noBackupFilesDir, "backup-staging/$session")
        val ids = journal.getJSONArray("ids")
        val existingArray = journal.getJSONArray("existing")
        val existing = (0 until existingArray.length()).map { existingArray.getString(it) }.toSet()
        for (i in 0 until ids.length()) {
          val id = ids.getString(i)
          BackupCodec.validateAssetPath("$id/theme.json")
          val original = File(staging, "old/$id")
          val target = File(root, id)
          ensureContained(root, target)
          if (original.exists()) {
            ensureContained(staging, original)
            if (target.exists() && !target.deleteRecursively()) throw BackupException(BackupFailure.ROLLBACK_FAILED)
            if (!original.renameTo(target)) throw BackupException(BackupFailure.ROLLBACK_FAILED)
          } else if (id !in existing && !File(staging, "new/$id").exists() && target.exists()) {
            if (!target.deleteRecursively()) throw BackupException(BackupFailure.ROLLBACK_FAILED)
          }
        }
        if (staging.exists()) {
          ensureContained(staging.parentFile!!, staging)
          if (!staging.deleteRecursively()) throw BackupException(BackupFailure.ROLLBACK_FAILED)
        }
      }
    }
  }
}
