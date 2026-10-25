/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.backup

import android.content.Context
import android.content.SharedPreferences
import com.google.android.accessibility.braille.common.BrailleImeAction
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.brailleime.BrailleImeGestureAction
import com.google.android.accessibility.brailleime.BrailleIme
import com.google.android.accessibility.brailleime.input.DotHoldSwipe
import com.google.android.accessibility.brailleime.input.Swipe

/** Phone-only owners stay out of main/Wear code, and gesture validation uses the real parser. */
internal object BackupBrailleSupport {
  fun beginRestore() = BrailleIme.beginBackupRestore()
  fun finishRestore() = BrailleIme.finishBackupRestore()
  fun preferences(context: Context): SharedPreferences = BrailleUserPreferences.getSharedPreferences(
    context, BrailleUserPreferences.BRAILLE_SHARED_PREFS_FILENAME)

  fun validate(entry: TalkBackBackup.PrefEntry) {
    if (entry.key == "pref_bd_gesture_action_key") {
      if (entry.type != TalkBackBackup.PrefType.STRING) throw BackupException(BackupFailure.TYPE_MISMATCH)
      val value = entry.value as String
      if (value.isEmpty()) return // The owner's empty value means default assignments.
      val map = BackupJson.parse(value)
      for (action in map.keys()) {
        if (BrailleImeAction.values().none { it.name == action }) throw BackupException(BackupFailure.UNSUPPORTED_VERSION)
        val ids = map.optJSONArray(action) ?: throw BackupException(BackupFailure.INVALID_FORMAT)
        if (ids.length() > 32) throw BackupException(BackupFailure.INVALID_FORMAT)
        for (i in 0 until ids.length()) {
          val id = ids.opt(i) as? String ?: throw BackupException(BackupFailure.INVALID_FORMAT)
          if (id == "unassigned") continue
          try {
            val gesture = if (id.startsWith("hold_dot")) DotHoldSwipe(id) else Swipe(id)
            if (!BrailleImeGestureAction.isValid(gesture)) throw BackupException(BackupFailure.INVALID_FORMAT)
          } catch (_: IllegalArgumentException) { throw BackupException(BackupFailure.INVALID_FORMAT) }
        }
      }
    } else if (entry.key.startsWith("pref_brailleime_calibration_points_")) {
      if (entry.type != TalkBackBackup.PrefType.STRING) throw BackupException(BackupFailure.TYPE_MISMATCH)
      val raw = entry.value as String
      if (raw.isEmpty()) return
      val obj = BackupJson.parse(raw)
      BackupJson.fields(obj, setOf("orientation", "points"), setOf("screen_width", "screen_height"))
      if (BackupJson.integer(obj.opt("orientation")) !in 1..2)
        throw BackupException(BackupFailure.INVALID_FORMAT)
      for (dimension in listOf("screen_width", "screen_height")) if (obj.has(dimension)) {
        if (BackupJson.integer(obj.opt(dimension)) !in 1..100_000)
          throw BackupException(BackupFailure.INVALID_FORMAT)
      }
      val points = obj.optJSONArray("points") ?: throw BackupException(BackupFailure.INVALID_FORMAT)
      val count = if (entry.key.contains("eightDot")) 8 else 6
      if (points.length() != count) throw BackupException(BackupFailure.INVALID_FORMAT)
      for (i in 0 until points.length()) {
        val point = points.optJSONObject(i) ?: throw BackupException(BackupFailure.INVALID_FORMAT)
        BackupJson.fields(point, setOf("x", "y"))
        for (axis in listOf("x", "y")) {
          val coordinate = (point.opt(axis) as? Number)?.toDouble()
            ?: throw BackupException(BackupFailure.INVALID_FORMAT)
          if (!coordinate.isFinite() || coordinate !in 0.0..100_000.0)
            throw BackupException(BackupFailure.INVALID_FORMAT)
        }
      }
    }
  }
}
