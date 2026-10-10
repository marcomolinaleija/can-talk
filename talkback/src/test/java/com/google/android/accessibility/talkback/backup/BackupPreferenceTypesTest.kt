/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.backup

import android.content.Context
import com.google.android.accessibility.talkback.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** New upstream settings must retain their owners' types even on an empty installation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupPreferenceTypesTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Test
  fun upstreamSettingsHaveExplicitTypesAndSettingsCategory() {
    val types = mapOf(
      context.getString(R.string.pref_interrupt_enter_key) to TalkBackBackup.PrefType.BOOLEAN,
      // ListPreference persists a string even though its owner parses it as an integer.
      context.getString(R.string.pref_interrupt_typing_key) to TalkBackBackup.PrefType.STRING,
      context.getString(R.string.pref_reserve_vibration_watch_gestures_key) to TalkBackBackup.PrefType.BOOLEAN,
      context.getString(R.string.pref_table_column_headers_key) to TalkBackBackup.PrefType.STRING,
      context.getString(R.string.pref_table_speak_row_column_numbers_key) to TalkBackBackup.PrefType.BOOLEAN,
      "pref_speak_nav_bar_buttons" to TalkBackBackup.PrefType.BOOLEAN,
    )
    for ((key, type) in types) {
      assertEquals(key, type, BackupPreferenceTypes.expected(context, BackupStore.READER, key))
      assertEquals(key, BackupCategory.SETTINGS, BackupCategory.forKey(BackupStore.READER, key))
    }
  }

  @Test
  fun navigationBarLiteralDoesNotDefineABraillePreference() {
    assertNull(BackupPreferenceTypes.expected(context, BackupStore.BRAILLE, "pref_speak_nav_bar_buttons"))
  }

  @Test
  fun existingSpeechRateTypeIsPreserved() {
    assertEquals(
      TalkBackBackup.PrefType.STRING,
      BackupPreferenceTypes.expected(context, BackupStore.READER, context.getString(R.string.pref_speech_rate_key)),
    )
  }
}
