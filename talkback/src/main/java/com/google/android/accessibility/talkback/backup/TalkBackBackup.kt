/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.backup

import android.content.SharedPreferences
import com.google.android.accessibility.talkback.migration.MigrationFiles
import com.google.android.accessibility.talkback.update.Updater
import com.google.android.accessibility.talkback.updatetasks.TalkBackUpdateHelper
import java.security.MessageDigest
import org.json.JSONObject

/** Shared primitive types and exact v1 checksum compatibility. The UI uses the selective v2 engine. */
object TalkBackBackup {
  const val MAGIC_FORMAT = "CAN_TALK_BACKUP"
  const val BACKUP_VERSION = 1
  enum class PrefType(val typeString: String) {
    BOOLEAN("boolean"), INT("int"), LONG("long"), FLOAT("float"), STRING("string"), STRING_SET("string_set");
    companion object { fun fromString(str: String): PrefType? = entries.firstOrNull { it.typeString == str } }
  }
  data class PrefEntry(val key: String, val type: PrefType, val value: Any)
  enum class ImportError {
    INVALID_JSON, INVALID_FORMAT, UNSUPPORTED_VERSION, CHECKSUM_MISMATCH, COMMIT_FAILED, IO_ERROR,
  }
  sealed class ImportResult {
    data class Success(val preferenceCount: Int) : ImportResult()
    data class Failure(val error: ImportError, val exception: Throwable? = null) : ImportResult()
  }
  val IGNORED_KEYS = setOf(
    Updater.PREF_LAST_CHECK_TIME, Updater.PREF_LAST_NOTIFIED_BUILD, MigrationFiles.STATE_PREFS,
    "migration_done", "migration_attempts", "status_location_permission_requested",
    "pref_chatgpt_pkce_verifier", "pref_chatgpt_pkce_state",
    TalkBackUpdateHelper.PREF_APP_VERSION, TalkBackUpdateHelper.PREF_APP_PREV_VERSION_NAME,
  )

  private val KNOWN_TALKBACK_APP_IDS = listOf(
    "fyi.quin.backtalk.cantalktest",
    "fyi.quin.backtalk",
    "com.android.talkback",
  )

  @Deprecated("Single-store v1 compatibility only; use the selective backup screen")
  @JvmStatic
  fun exportBackup(prefs: SharedPreferences, sourcePackage: String): String {
    val entries = prefs.all.mapNotNull { (key, value) ->
      val category = BackupCategory.forKey(BackupStore.READER, key)
      if (value == null || category == null || category.sensitive) null else SelectiveBackup.entry(key, value)
    }
    return JSONObject().put("format", MAGIC_FORMAT).put("version", BACKUP_VERSION)
      .put("createdAt", System.currentTimeMillis()).put("sourcePackage", sourcePackage)
      .put("checksum", computeChecksum(entries)).put("preferences", BackupCodec.encodePreferences(entries)).toString(2)
  }

  @Deprecated("Single-store v1 compatibility only; use the selective backup screen")
  @JvmStatic
  fun importBackup(prefs: SharedPreferences, targetPackage: String, json: String): ImportResult {
    val root: JSONObject
    try {
      root = JSONObject(json)
    } catch (e: org.json.JSONException) {
      return ImportResult.Failure(ImportError.INVALID_JSON, e)
    }
    if (root.optString("format") != MAGIC_FORMAT) {
      return ImportResult.Failure(ImportError.INVALID_FORMAT)
    }
    val version = root.optInt("version", -1)
    if (version != BACKUP_VERSION) {
      return ImportResult.Failure(ImportError.UNSUPPORTED_VERSION)
    }
    val entries: List<PrefEntry>
    val sourcePackage: String
    try {
      val document = BackupCodec.decode(json)
      if (!document.legacy) return ImportResult.Failure(ImportError.UNSUPPORTED_VERSION)
      sourcePackage = document.sourcePackage
      entries = document.sections.values.flatMap { it.preferences[BackupStore.READER].orEmpty() }
    } catch (e: BackupException) {
      return ImportResult.Failure(when (e.failure) {
        BackupFailure.CHECKSUM -> ImportError.CHECKSUM_MISMATCH
        BackupFailure.UNSUPPORTED_VERSION -> ImportError.UNSUPPORTED_VERSION
        else -> ImportError.INVALID_FORMAT
      })
    }
    val all = prefs.all
    val before = entries.associate { it.key to all[it.key] }
    if (entries.any { all[it.key] != null && SelectiveBackup.entry(it.key, all[it.key]!!).type != it.type })
      return ImportResult.Failure(ImportError.INVALID_FORMAT)
    try {
      val editor = prefs.edit()
      for (entry in entries) {
        val migrated = when (entry.type) {
          PrefType.STRING -> PrefEntry(
            entry.key,
            PrefType.STRING,
            migratePackageString(entry.value as String, sourcePackage, targetPackage)
          )
          PrefType.STRING_SET -> {
            @Suppress("UNCHECKED_CAST")
            val setVal = (entry.value as Set<String>).map {
              migratePackageString(it, sourcePackage, targetPackage)
            }.toSet()
            PrefEntry(entry.key, PrefType.STRING_SET, setVal)
          }
          else -> entry
        }
        SelectiveBackup.put(editor, migrated)
      }
      val provider = entries.find { it.key == "pref_ai_provider" }?.value as? String
      val onDevice = entries.find { it.key == "pref_on_device_ai_provider" }?.value as? String
      if (provider != null && onDevice == null) {
        val mapped = when (provider) {
          "gemini" -> "cloud"
          "chatgpt" -> "chatgpt"
          "groq" -> "groq"
          "device" -> "device"
          else -> provider
        }
        editor.putString("pref_on_device_ai_provider", mapped)
      } else if (onDevice != null && provider == null) {
        val mapped = when (onDevice) {
          "cloud" -> "gemini"
          "chatgpt" -> "chatgpt"
          "groq" -> "groq"
          "device" -> "device"
          else -> onDevice
        }
        editor.putString("pref_ai_provider", mapped)
      }
      if (editor.commit()) return ImportResult.Success(entries.size)
    } catch (_: Exception) { }
    try {
      val rollback = prefs.edit()
      for ((key, value) in before) {
        if (value == null) rollback.remove(key) else SelectiveBackup.put(rollback, SelectiveBackup.entry(key, value))
      }
      if (!rollback.commit()) return ImportResult.Failure(ImportError.COMMIT_FAILED)
    } catch (_: Exception) { }
    return ImportResult.Failure(ImportError.COMMIT_FAILED)
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
