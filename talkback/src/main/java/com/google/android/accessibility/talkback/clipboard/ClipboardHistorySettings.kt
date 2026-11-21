/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.clipboard

import android.content.Context
import android.content.SharedPreferences
import com.google.android.accessibility.utils.SharedPreferencesUtils

/** User preferences governing clipboard history capture, storage limit, and speech verbosity. */
object ClipboardHistorySettings {
  const val PREF_CAPTURE_ENABLED = "pref_clipboard_history_capture_enabled"
  const val PREF_SPEAK_CONTENT_WITH_LABEL = "pref_clipboard_history_speak_content_with_label"
  const val PREF_MAX_ENTRIES = "pref_clipboard_history_max_entries"

  fun prefs(context: Context): SharedPreferences =
    SharedPreferencesUtils.getSharedPreferences(context)

  fun isCaptureEnabled(context: Context): Boolean =
    prefs(context).getBoolean(PREF_CAPTURE_ENABLED, true)

  fun setCaptureEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(PREF_CAPTURE_ENABLED, enabled).apply()
  }

  fun speakContentWithLabel(context: Context): Boolean =
    prefs(context).getBoolean(PREF_SPEAK_CONTENT_WITH_LABEL, true)

  fun setSpeakContentWithLabel(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(PREF_SPEAK_CONTENT_WITH_LABEL, enabled).apply()
  }

  fun getMaxEntries(context: Context): Int =
    prefs(context)
      .getInt(PREF_MAX_ENTRIES, ClipboardHistoryStore.DEFAULT_MAX_ENTRIES)
      .coerceIn(ClipboardHistoryStore.MIN_ENTRIES_LIMIT, ClipboardHistoryStore.MAX_ENTRIES_LIMIT)

  fun setMaxEntries(context: Context, limit: Int) {
    val clamped = limit.coerceIn(
      ClipboardHistoryStore.MIN_ENTRIES_LIMIT,
      ClipboardHistoryStore.MAX_ENTRIES_LIMIT,
    )
    prefs(context).edit().putInt(PREF_MAX_ENTRIES, clamped).apply()
  }
}
