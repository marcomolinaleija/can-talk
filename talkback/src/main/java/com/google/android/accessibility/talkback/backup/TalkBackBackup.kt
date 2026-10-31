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

  @Deprecated("Single-store v1 compatibility only; use the selective backup screen")
  @JvmStatic
  fun exportBackup(prefs: SharedPreferences, sourcePackage: String): String {
    val entries = prefs.all.mapNotNull { (key, value) ->
      val category = BackupCategory.forKey(BackupStore.READER, key)
      if (value == null || category == null || category.sensitive) null else SelectiveBackup.entry(key, value)
    }
    return JSONObject().put("format", MAGIC_FORMAT).put("version", BACKUP_VERSION)
      .put("createdAt", System.currentTimeMillis()).put("sourcePackage", sourcePackage)
      .put("checksum", computeChecksum(entries)).put("preferences", BackupCodec.encodePreferences(entries)).toString()
  }

  @Deprecated("Single-store v1 compatibility only; use the selective backup screen")
  @Suppress("UNUSED_PARAMETER")
  @JvmStatic
  fun importBackup(prefs: SharedPreferences, targetPackage: String, json: String): ImportResult {
    val entries: List<PrefEntry>
    try {
      val document = BackupCodec.decode(json)
      if (!document.legacy) return ImportResult.Failure(ImportError.UNSUPPORTED_VERSION)
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
      entries.forEach { SelectiveBackup.put(editor, it) }
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

  /** Text is user data, not a package identifier. Logical store IDs remove the need for migration. */
  @Suppress("UNUSED_PARAMETER")
  internal fun migratePackageString(value: String, sourcePackage: String?, targetPackage: String): String = value

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
