/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.backup

import android.content.Context
import android.content.SharedPreferences
import com.google.android.accessibility.utils.SharedPreferencesUtils

/** Wear has no braille keyboard owner; do not pretend that phone mappings were applied to it. */
internal object BackupBrailleSupport {
  fun beginRestore() = Unit
  fun finishRestore() = Unit
  fun preferences(context: Context): SharedPreferences =
    SharedPreferencesUtils.getSharedPreferences(context, "braille_keyboard")
  fun validate(entry: TalkBackBackup.PrefEntry) {
    throw BackupException(BackupFailure.UNSUPPORTED_VERSION)
  }
}
