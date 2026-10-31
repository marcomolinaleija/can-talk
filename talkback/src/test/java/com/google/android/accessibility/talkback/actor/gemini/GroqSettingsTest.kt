/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.actor.gemini

import com.google.android.accessibility.talkback.actor.gemini.local.OnDeviceAiSettings
import com.google.android.accessibility.talkback.backup.BackupCategory
import com.google.android.accessibility.talkback.backup.BackupStore
import com.google.android.accessibility.talkback.directtouch.FakeSharedPreferences
import org.junit.Assert.*
import org.junit.Test

class GroqSettingsTest {
  @Test fun savingKeyDoesNotChangeProviderOrGeminiCredential() {
    val prefs = FakeSharedPreferences()
    GeminiApiKey.setUserKey(prefs, "gemini")
    GroqSettings.setUserKey(prefs, " groq\n")
    assertEquals("groq", GroqSettings.userKey(prefs))
    assertEquals("gemini", GeminiApiKey.userKey(prefs))
    assertFalse(GroqSettings.isSelected(prefs))
    GroqSettings.setUserKey(prefs, " ")
    assertFalse(prefs.contains(GroqSettings.PREF_KEY))
  }

  @Test fun existingLocalPreferenceAndGroqSelectionAreMutuallyExclusive() {
    val prefs = FakeSharedPreferences()
    OnDeviceAiSettings.setOnDevice(prefs, true)
    assertFalse(GroqSettings.isSelected(prefs))
    prefs.edit().putString(OnDeviceAiSettings.PREF_PROVIDER, GroqSettings.PROVIDER).apply()
    assertTrue(GroqSettings.isSelected(prefs))
    assertFalse(OnDeviceAiSettings.isOnDevice(prefs))
  }

  @Test fun groqKeyBelongsToSensitiveApiKeysCategory() {
    assertEquals(BackupCategory.API_KEYS, BackupCategory.forKey(BackupStore.READER, GroqSettings.PREF_KEY))
    assertTrue(BackupCategory.API_KEYS.sensitive)
  }
}
