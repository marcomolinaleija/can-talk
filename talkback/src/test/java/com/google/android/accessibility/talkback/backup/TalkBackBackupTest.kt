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

import android.content.SharedPreferences
import com.google.android.accessibility.talkback.directtouch.FakeSharedPreferences
import com.google.android.accessibility.talkback.update.Updater
import com.google.android.accessibility.talkback.updatetasks.TalkBackUpdateHelper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TalkBackBackupTest {

  @Test
  fun exportAndImportRoundTripAllTypes() {
    val sourcePrefs = FakeSharedPreferences()
    sourcePrefs.edit()
      .putBoolean("pref_bool", true)
      .putInt("pref_int", 42)
      .putLong("pref_long", 9876543210L)
      .putFloat("pref_float", 3.14f)
      .putString("pref_str", "read_from_top")
      .putStringSet("pref_set", setOf("item1", "item2", "item3"))
      .apply()

    val json = TalkBackBackup.exportBackup(sourcePrefs, "fyi.quin.backtalk")
    val targetPrefs = FakeSharedPreferences()
    val result = TalkBackBackup.importBackup(targetPrefs, "fyi.quin.backtalk", json)

    assertTrue(result is TalkBackBackup.ImportResult.Success)
    assertEquals(6, (result as TalkBackBackup.ImportResult.Success).preferenceCount)

    assertEquals(true, targetPrefs.getBoolean("pref_bool", false))
    assertEquals(42, targetPrefs.getInt("pref_int", 0))
    assertEquals(9876543210L, targetPrefs.getLong("pref_long", 0L))
    assertEquals(3.14f, targetPrefs.getFloat("pref_float", 0f), 0.0001f)
    assertEquals("read_from_top", targetPrefs.getString("pref_str", null))
    assertEquals(setOf("item1", "item2", "item3"), targetPrefs.getStringSet("pref_set", null))
  }

  @Test
  fun ignoredKeysExcludedFromExportAndPreservedOnImport() {
    val sourcePrefs = FakeSharedPreferences()
    sourcePrefs.edit()
      .putBoolean("user_pref", true)
      .putLong(Updater.PREF_LAST_CHECK_TIME, 12345L)
      .putInt(TalkBackUpdateHelper.PREF_APP_VERSION, 999)
      .apply()

    val json = TalkBackBackup.exportBackup(sourcePrefs, "fyi.quin.backtalk")
    val root = JSONObject(json)
    val prefsObj = root.getJSONObject("preferences")

    assertTrue(prefsObj.has("user_pref"))
    assertFalse(prefsObj.has(Updater.PREF_LAST_CHECK_TIME))
    assertFalse(prefsObj.has(TalkBackUpdateHelper.PREF_APP_VERSION))

    val targetPrefs = FakeSharedPreferences()
    targetPrefs.edit().putInt(TalkBackUpdateHelper.PREF_APP_VERSION, 500).apply()

    val result = TalkBackBackup.importBackup(targetPrefs, "fyi.quin.backtalk", json)
    assertTrue(result is TalkBackBackup.ImportResult.Success)
    assertEquals(500, targetPrefs.getInt(TalkBackUpdateHelper.PREF_APP_VERSION, 0))
    assertEquals(true, targetPrefs.getBoolean("user_pref", false))
  }

  @Test
  fun packageMigrationBetweenDebugTestAndRelease() {
    val sourcePrefs = FakeSharedPreferences()
    sourcePrefs.edit()
      .putString(
        "pref_service",
        "fyi.quin.backtalk.cantalktest/com.google.android.marvin.talkback.TalkBackService"
      )
      .putStringSet(
        "pref_apps",
        setOf("fyi.quin.backtalk.cantalktest.settings", "com.external.app")
      )
      .apply()

    val json = TalkBackBackup.exportBackup(sourcePrefs, "fyi.quin.backtalk.cantalktest")
    val targetPrefs = FakeSharedPreferences()
    val result = TalkBackBackup.importBackup(targetPrefs, "fyi.quin.backtalk", json)

    assertTrue(result is TalkBackBackup.ImportResult.Success)
    assertEquals(
      "fyi.quin.backtalk/com.google.android.marvin.talkback.TalkBackService",
      targetPrefs.getString("pref_service", null)
    )
    assertEquals(
      setOf("fyi.quin.backtalk.settings", "com.external.app"),
      targetPrefs.getStringSet("pref_apps", null)
    )
  }

  @Test
  fun checksumValidationRejectsTampering() {
    val sourcePrefs = FakeSharedPreferences()
    sourcePrefs.edit().putInt("pref_val", 10).apply()

    val json = TalkBackBackup.exportBackup(sourcePrefs, "fyi.quin.backtalk")
    val tamperedJson = json.replace("\"val\": 10", "\"val\": 20")

    val targetPrefs = FakeSharedPreferences()
    targetPrefs.edit().putString("existing_key", "existing_val").apply()

    val result = TalkBackBackup.importBackup(targetPrefs, "fyi.quin.backtalk", tamperedJson)

    assertTrue(result is TalkBackBackup.ImportResult.Failure)
    assertEquals(
      TalkBackBackup.ImportError.CHECKSUM_MISMATCH,
      (result as TalkBackBackup.ImportResult.Failure).error
    )
    // Target preferences must remain untouched
    assertEquals("existing_val", targetPrefs.getString("existing_key", null))
    assertFalse(targetPrefs.contains("pref_val"))
  }

  @Test
  fun invalidJsonAndMalformedFormatRejection() {
    val targetPrefs = FakeSharedPreferences()

    val r1 = TalkBackBackup.importBackup(targetPrefs, "fyi.quin.backtalk", "invalid json")
    assertTrue(r1 is TalkBackBackup.ImportResult.Failure)
    assertEquals(TalkBackBackup.ImportError.INVALID_JSON, (r1 as TalkBackBackup.ImportResult.Failure).error)

    val r2 = TalkBackBackup.importBackup(targetPrefs, "fyi.quin.backtalk", "{\"format\":\"WRONG\"}")
    assertTrue(r2 is TalkBackBackup.ImportResult.Failure)
    assertEquals(TalkBackBackup.ImportError.INVALID_FORMAT, (r2 as TalkBackBackup.ImportResult.Failure).error)

    val r3 = TalkBackBackup.importBackup(
      targetPrefs,
      "fyi.quin.backtalk",
      "{\"format\":\"CAN_TALK_BACKUP\",\"version\":2}"
    )
    assertTrue(r3 is TalkBackBackup.ImportResult.Failure)
    assertEquals(TalkBackBackup.ImportError.UNSUPPORTED_VERSION, (r3 as TalkBackBackup.ImportResult.Failure).error)
  }

  @Test
  fun atomicRollbackOnCommitFailure() {
    class FailingPreferences : SharedPreferences {
      val map = mutableMapOf<String, Any?>()
      var failCommit = false

      override fun getAll(): MutableMap<String, *> = map.toMutableMap()
      override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
      @Suppress("UNCHECKED_CAST")
      override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? Set<String>)?.toMutableSet() ?: defValues
      override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
      override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
      override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
      override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
      override fun contains(key: String): Boolean = map.containsKey(key)
      override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
      override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

      override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val pending = mutableMapOf<String, Any?>()
        val removed = mutableSetOf<String>()
        var cleared = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { cleared = true }

        override fun commit(): Boolean {
          if (failCommit) {
            failCommit = false // Allow subsequent rollback commit to succeed
            return false
          }
          if (cleared) map.clear()
          removed.forEach { map.remove(it) }
          map.putAll(pending)
          return true
        }

        override fun apply() { commit() }
      }
    }

    val failingPrefs = FailingPreferences()
    failingPrefs.map["original_key"] = "original_val"

    val sourcePrefs = FakeSharedPreferences()
    sourcePrefs.edit().putString("new_key", "new_val").apply()
    val validJson = TalkBackBackup.exportBackup(sourcePrefs, "fyi.quin.backtalk")

    failingPrefs.failCommit = true
    val result = TalkBackBackup.importBackup(failingPrefs, "fyi.quin.backtalk", validJson)

    assertTrue(result is TalkBackBackup.ImportResult.Failure)
    assertEquals(TalkBackBackup.ImportError.COMMIT_FAILED, (result as TalkBackBackup.ImportResult.Failure).error)
    // Rollback restored original state
    assertEquals("original_val", failingPrefs.map["original_key"])
    assertNull(failingPrefs.map["new_key"])
  }

  @Test
  fun aiProviderRetrocompatibilityLegacyToOnDevice() {
    val sourcePrefs = FakeSharedPreferences()
    sourcePrefs.edit()
      .putString("pref_ai_provider", "chatgpt")
      .apply()

    val json = TalkBackBackup.exportBackup(sourcePrefs, "fyi.quin.backtalk")
    val targetPrefs = FakeSharedPreferences()
    val result = TalkBackBackup.importBackup(targetPrefs, "fyi.quin.backtalk", json)

    assertTrue(result is TalkBackBackup.ImportResult.Success)
    assertEquals("chatgpt", targetPrefs.getString("pref_ai_provider", null))
    assertEquals("chatgpt", targetPrefs.getString("pref_on_device_ai_provider", null))
  }

  @Test
  fun aiProviderRetrocompatibilityGeminiToCloud() {
    val sourcePrefs = FakeSharedPreferences()
    sourcePrefs.edit()
      .putString("pref_ai_provider", "gemini")
      .apply()

    val json = TalkBackBackup.exportBackup(sourcePrefs, "fyi.quin.backtalk")
    val targetPrefs = FakeSharedPreferences()
    val result = TalkBackBackup.importBackup(targetPrefs, "fyi.quin.backtalk", json)

    assertTrue(result is TalkBackBackup.ImportResult.Success)
    assertEquals("gemini", targetPrefs.getString("pref_ai_provider", null))
    assertEquals("cloud", targetPrefs.getString("pref_on_device_ai_provider", null))
  }

  @Test
  fun aiProviderRetrocompatibilityOnDeviceToLegacy() {
    val sourcePrefs = FakeSharedPreferences()
    sourcePrefs.edit()
      .putString("pref_on_device_ai_provider", "groq")
      .apply()

    val json = TalkBackBackup.exportBackup(sourcePrefs, "fyi.quin.backtalk")
    val targetPrefs = FakeSharedPreferences()
    val result = TalkBackBackup.importBackup(targetPrefs, "fyi.quin.backtalk", json)

    assertTrue(result is TalkBackBackup.ImportResult.Success)
    assertEquals("groq", targetPrefs.getString("pref_on_device_ai_provider", null))
    assertEquals("groq", targetPrefs.getString("pref_ai_provider", null))
  }
}
