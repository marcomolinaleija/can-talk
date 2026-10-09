/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.backup

import android.content.Context
import android.content.SharedPreferences
import com.google.android.accessibility.talkback.migration.MigrationFiles
import com.google.android.accessibility.talkback.update.Updater
import com.google.android.accessibility.talkback.updatetasks.TalkBackUpdateHelper
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Resilient, type-safe backup and restoration engine for TalkBack/Backtalk
 * settings and gesture shortcuts.
 *
 * Preserves exact primitive and collection types (boolean, int, long, float, string, string_set),
 * prevents delimiter collisions with canonical length-prefixed hashing, validates integrity
 * via SHA-256, provides atomic transactional rollback in case of commit failure, and translates
 * package identifiers for cross-package and debug-to-release migrations.
 */
object TalkBackBackup {

  const val MAGIC_FORMAT = "CAN_TALK_BACKUP"
  const val BACKUP_VERSION = 1

  enum class PrefType(val typeString: String) {
    BOOLEAN("boolean"),
    INT("int"),
    LONG("long"),
    FLOAT("float"),
    STRING("string"),
    STRING_SET("string_set");

    companion object {
      fun fromString(str: String): PrefType? = entries.firstOrNull { it.typeString == str }
    }
  }

  data class PrefEntry(val key: String, val type: PrefType, val value: Any)

  enum class ImportError {
    INVALID_JSON,
    INVALID_FORMAT,
    UNSUPPORTED_VERSION,
    CHECKSUM_MISMATCH,
    COMMIT_FAILED,
    IO_ERROR,
  }

  sealed class ImportResult {
    data class Success(val preferenceCount: Int) : ImportResult()
    data class Failure(val error: ImportError, val exception: Throwable? = null) : ImportResult()
  }

  /**
   * Internal or ephemeral preference keys that should not be carried over or wiped during restore.
   */
  val IGNORED_KEYS: Set<String> = setOf(
    Updater.PREF_LAST_CHECK_TIME,
    Updater.PREF_LAST_NOTIFIED_BUILD,
    MigrationFiles.STATE_PREFS,
    "migration_done",
    "migration_attempts",
    "status_location_permission_requested",
    TalkBackUpdateHelper.PREF_APP_VERSION,
    TalkBackUpdateHelper.PREF_APP_PREV_VERSION_NAME,
  )

  private val KNOWN_TALKBACK_APP_IDS = listOf(
    "fyi.quin.backtalk.cantalktest",
    "fyi.quin.backtalk",
    "com.android.talkback",
  )

  /**
   * Exports all current preferences to a portable JSON string.
   */
  @JvmStatic
  fun exportBackup(context: Context): String =
    exportBackup(SharedPreferencesUtils.getSharedPreferences(context), context.packageName)

  @JvmStatic
  fun exportBackup(prefs: SharedPreferences, sourcePackage: String): String {
    val allPrefs = prefs.all
    val entries = mutableListOf<PrefEntry>()
    for ((key, value) in allPrefs) {
      if (key in IGNORED_KEYS || value == null) continue
      val entry = when (value) {
        is Boolean -> PrefEntry(key, PrefType.BOOLEAN, value)
        is Int -> PrefEntry(key, PrefType.INT, value)
        is Long -> PrefEntry(key, PrefType.LONG, value)
        is Float -> PrefEntry(key, PrefType.FLOAT, value)
        is String -> PrefEntry(key, PrefType.STRING, value)
        is Set<*> -> {
          @Suppress("UNCHECKED_CAST")
          val setVal = (value as? Set<String>) ?: value.map { it.toString() }.toSet()
          PrefEntry(key, PrefType.STRING_SET, setVal)
        }
        else -> null
      }
      if (entry != null) {
        entries.add(entry)
      }
    }

    entries.sortBy { it.key }
    val checksum = computeChecksum(entries)

    val root = JSONObject()
    root.put("format", MAGIC_FORMAT)
    root.put("version", BACKUP_VERSION)
    root.put("createdAt", System.currentTimeMillis())
    root.put("sourcePackage", sourcePackage)
    root.put("checksum", checksum)

    val prefsObj = JSONObject()
    for (entry in entries) {
      val entryObj = JSONObject()
      entryObj.put("type", entry.type.typeString)
      when (entry.type) {
        PrefType.STRING_SET -> {
          @Suppress("UNCHECKED_CAST")
          val setVal = (entry.value as Set<String>).sorted()
          entryObj.put("val", JSONArray(setVal))
        }
        else -> entryObj.put("val", entry.value)
      }
      prefsObj.put(entry.key, entryObj)
    }
    root.put("preferences", prefsObj)

    return root.toString(2)
  }

  /**
   * Imports preferences from JSON. Validates format, version, and checksum.
   * Takes a full snapshot for atomic rollback if the commit fails.
   */
  @JvmStatic
  fun importBackup(context: Context, json: String): ImportResult =
    importBackup(SharedPreferencesUtils.getSharedPreferences(context), context.packageName, json)

  @JvmStatic
  fun importBackup(prefs: SharedPreferences, targetPackage: String, json: String): ImportResult {
    val root: JSONObject
    try {
      root = JSONObject(json)
    } catch (e: JSONException) {
      return ImportResult.Failure(ImportError.INVALID_JSON, e)
    }

    if (root.optString("format") != MAGIC_FORMAT) {
      return ImportResult.Failure(ImportError.INVALID_FORMAT)
    }

    val version = root.optInt("version", -1)
    if (version != BACKUP_VERSION) {
      return ImportResult.Failure(ImportError.UNSUPPORTED_VERSION)
    }

    val sourcePackage = root.optString("sourcePackage", null)
    val expectedChecksum = root.optString("checksum", "")
    val prefsObj = root.optJSONObject("preferences")
      ?: return ImportResult.Failure(ImportError.INVALID_FORMAT)

    val parsedEntries = mutableListOf<PrefEntry>()
    val keys = prefsObj.keys()
    while (keys.hasNext()) {
      val key = keys.next()
      val entryObj = prefsObj.optJSONObject(key)
        ?: return ImportResult.Failure(ImportError.INVALID_FORMAT)

      val typeStr = entryObj.optString("type", "")
      val type = PrefType.fromString(typeStr)
        ?: return ImportResult.Failure(ImportError.INVALID_FORMAT)

      try {
        val value: Any = when (type) {
          PrefType.BOOLEAN -> entryObj.getBoolean("val")
          PrefType.INT -> entryObj.getInt("val")
          PrefType.LONG -> entryObj.getLong("val")
          PrefType.FLOAT -> entryObj.getDouble("val").toFloat()
          PrefType.STRING -> entryObj.getString("val")
          PrefType.STRING_SET -> {
            val arr = entryObj.getJSONArray("val")
            (0 until arr.length()).map { arr.getString(it) }.toSet()
          }
        }
        parsedEntries.add(PrefEntry(key, type, value))
      } catch (e: JSONException) {
        return ImportResult.Failure(ImportError.INVALID_FORMAT, e)
      }
    }

    parsedEntries.sortBy { it.key }
    val calculatedChecksum = computeChecksum(parsedEntries)
    if (!calculatedChecksum.equals(expectedChecksum, ignoreCase = true)) {
      return ImportResult.Failure(ImportError.CHECKSUM_MISMATCH)
    }

    // Snapshot for atomic rollback
    val snapshot: Map<String, *> = HashMap(prefs.all)

    try {
      val editor = prefs.edit()

      // Clear existing non-ignored keys
      for (k in snapshot.keys) {
        if (k !in IGNORED_KEYS) {
          editor.remove(k)
        }
      }

      // Apply imported entries with package migration
      for (entry in parsedEntries) {
        if (entry.key in IGNORED_KEYS) continue
        when (entry.type) {
          PrefType.BOOLEAN -> editor.putBoolean(entry.key, entry.value as Boolean)
          PrefType.INT -> editor.putInt(entry.key, entry.value as Int)
          PrefType.LONG -> editor.putLong(entry.key, entry.value as Long)
          PrefType.FLOAT -> editor.putFloat(entry.key, entry.value as Float)
          PrefType.STRING -> {
            val migrated = migratePackageString(entry.value as String, sourcePackage, targetPackage)
            editor.putString(entry.key, migrated)
          }
          PrefType.STRING_SET -> {
            @Suppress("UNCHECKED_CAST")
            val setVal = (entry.value as Set<String>).map {
              migratePackageString(it, sourcePackage, targetPackage)
            }.toSet()
            editor.putStringSet(entry.key, setVal)
          }
        }
      }

      val committed = editor.commit()
      if (!committed) {
        rollback(prefs, snapshot)
        return ImportResult.Failure(ImportError.COMMIT_FAILED)
      }

      return ImportResult.Success(parsedEntries.size)
    } catch (e: Throwable) {
      rollback(prefs, snapshot)
      return ImportResult.Failure(ImportError.COMMIT_FAILED, e)
    }
  }

  private fun rollback(prefs: SharedPreferences, snapshot: Map<String, *>) {
    try {
      val editor = prefs.edit()
      editor.clear()
      for ((key, value) in snapshot) {
        if (value == null) continue
        when (value) {
          is Boolean -> editor.putBoolean(key, value)
          is Int -> editor.putInt(key, value)
          is Long -> editor.putLong(key, value)
          is Float -> editor.putFloat(key, value)
          is String -> editor.putString(key, value)
          is Set<*> -> {
            @Suppress("UNCHECKED_CAST")
            val setVal = (value as? Set<String>) ?: value.map { it.toString() }.toSet()
            editor.putStringSet(key, setVal)
          }
        }
      }
      editor.commit()
    } catch (_: Throwable) {
      // Best-effort rollback
    }
  }

  internal fun migratePackageString(value: String, sourcePackage: String?, targetPackage: String): String {
    if (targetPackage.isEmpty()) return value
    var result = value
    val candidates = (listOfNotNull(sourcePackage) + KNOWN_TALKBACK_APP_IDS)
      .filter { it.isNotEmpty() && it != targetPackage }
      .distinct()
      .sortedByDescending { it.length }

    for (cand in candidates) {
      if (result.contains(cand)) {
        result = result.replace(cand, targetPackage)
      }
    }
    return result
  }

  internal fun computeChecksum(entries: List<PrefEntry>): String {
    val sorted = entries.sortedBy { it.key }
    val digest = MessageDigest.getInstance("SHA-256")
    for (entry in sorted) {
      val typeStr = entry.type.typeString
      val keyStr = entry.key
      val header = "${typeStr.length}:$typeStr:${keyStr.length}:$keyStr:"
      digest.update(header.toByteArray(Charsets.UTF_8))
      when (entry.type) {
        PrefType.STRING_SET -> {
          @Suppress("UNCHECKED_CAST")
          val setVal = (entry.value as Set<String>).sorted()
          digest.update("${setVal.size}:".toByteArray(Charsets.UTF_8))
          for (item in setVal) {
            digest.update("${item.length}:$item:".toByteArray(Charsets.UTF_8))
          }
        }
        else -> {
          val valStr = entry.value.toString()
          digest.update("${valStr.length}:$valStr\n".toByteArray(Charsets.UTF_8))
        }
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}
