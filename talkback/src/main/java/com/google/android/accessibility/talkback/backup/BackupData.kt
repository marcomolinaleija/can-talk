/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import com.google.android.accessibility.utils.SharedPreferencesUtils

/** Logical identifiers are portable; a backup never chooses a filesystem or preference filename. */
internal enum class BackupStore(val id: String) {
  READER("reader"), BRAILLE("braille"), EMOJI("emoji"), CLIPBOARD("clipboard");

  fun open(context: Context): SharedPreferences = when (this) {
    READER -> SharedPreferencesUtils.getSharedPreferences(context)
    BRAILLE -> BackupBrailleSupport.preferences(context)
    // These two owners deliberately use credential-protected storage, unlike the reader/braille.
    EMOJI -> context.applicationContext.getSharedPreferences("braille_emoji_preferences", Context.MODE_PRIVATE)
    CLIPBOARD -> context.applicationContext.getSharedPreferences("clipboard_history", Context.MODE_PRIVATE)
  }

  companion object {
    fun fromId(id: String) = entries.firstOrNull { it.id == id }
    fun deviceContext(context: Context): Context =
      ContextCompat.createDeviceProtectedStorageContext(context) ?: context
  }
}

internal enum class BackupCategory(val id: String, val sensitive: Boolean = false) {
  SETTINGS("settings"),
  READER_GESTURES("reader_gestures"),
  KEYBOARD_SHORTCUTS("keyboard_shortcuts"),
  BRAILLE_SETTINGS("braille_settings"),
  BRAILLE_GESTURES("braille_gestures"),
  BRAILLE_CALIBRATION("braille_calibration"),
  API_KEYS("api_keys", true),
  EMOJI_FAVORITES("emoji_favorites"),
  CLIPBOARD_HISTORY("clipboard_history", true),
  CUSTOM_LABELS("custom_labels"),
  SOUND_THEMES("sound_themes");

  companion object {
    fun fromId(id: String) = entries.firstOrNull { it.id == id }

    /** Includes every persisted portable preference, but not migrations/installation bookkeeping. */
    fun forKey(store: BackupStore, key: String): BackupCategory? = when (store) {
      BackupStore.READER -> when {
        key in TalkBackBackup.IGNORED_KEYS || key.startsWith("pref_previous_global_") -> null
        key == "pref_gemini_api_key" -> API_KEYS
        key.startsWith("pref_shortcut_") || key == "pref_gesture_set_key"
          || key == "pref_multiple_gesture_set_key" -> READER_GESTURES
        key.startsWith("default_key_combo_model|") || key.startsWith("new_key_combo_model|")
          || key.startsWith("pref_keymap") || key == "pref_select_keymap"
          || key == "pref_default_keymap_trigger_modifier" || key == "pref_new_keymap_trigger_modifier"
          || key.endsWith("|key_combo_code")
          || key.endsWith("|trigger_modifier_used") || key.endsWith("|always_use_trigger_modifier") -> KEYBOARD_SHORTCUTS
        else -> SETTINGS
      }
      BackupStore.BRAILLE -> when {
        key == "brailleime_shared_prefs_version" || key.startsWith("migration_") -> null
        key == "pref_bd_gesture_action_key" -> BRAILLE_GESTURES
        key.startsWith("pref_brailleime_calibration_points_") -> BRAILLE_CALIBRATION
        else -> BRAILLE_SETTINGS // Includes the braille display's settings in this same namespace.
      }
      BackupStore.EMOJI -> if (key == "user_favorite_emojis") EMOJI_FAVORITES else null
      BackupStore.CLIPBOARD -> if (key == "entries_v1") CLIPBOARD_HISTORY else null
    }
  }
}

internal data class BackupAsset(val path: String, val bytes: ByteArray)

/** Database row IDs and screenshot paths are device-local, and are never restored from a file. */
internal data class BackupLabel(
  val packageName: String,
  val packageSignature: String,
  val viewName: String,
  val text: String,
  val locale: String,
  val packageVersion: Int,
  val timestamp: Long,
) {
  fun identity(): List<String> = listOf(packageName, packageSignature, viewName, locale, packageVersion.toString())
}

internal data class BackupSection(
  val preferences: Map<BackupStore, List<TalkBackBackup.PrefEntry>> = emptyMap(),
  val labels: List<BackupLabel> = emptyList(),
  val assets: List<BackupAsset> = emptyList(),
) {
  val itemCount: Int get() = preferences.values.sumOf { it.size } + labels.size + assets.size
}

internal data class BackupDocument(
  val sourcePackage: String,
  val createdAt: Long,
  val sections: Map<BackupCategory, BackupSection>,
  val legacy: Boolean = false,
) {
  fun selected(categories: Set<BackupCategory>): BackupDocument {
    require(categories.isNotEmpty() && sections.keys.containsAll(categories))
    return copy(sections = sections.filterKeys { it in categories })
  }
  val containsSensitiveData: Boolean get() = sections.keys.any { it.sensitive }
}

internal enum class BackupFailure {
  INVALID_FORMAT, UNSUPPORTED_VERSION, CHECKSUM, TOO_LARGE, PASSWORD_REQUIRED,
  WRONG_PASSWORD, TYPE_MISMATCH, STORAGE_LOCKED, WRITE_FAILED, ROLLBACK_FAILED, IO, INTERRUPTED,
}

/** No exception messages from untrusted JSON, passwords, labels or preference values reach logs/UI. */
internal class BackupException(val failure: BackupFailure) : Exception(failure.name)
