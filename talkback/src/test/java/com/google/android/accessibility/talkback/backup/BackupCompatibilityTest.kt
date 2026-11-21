/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.backup

import com.google.android.accessibility.talkback.backup.TalkBackBackup.PrefEntry
import com.google.android.accessibility.talkback.backup.TalkBackBackup.PrefType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fixtures match v2 exports before NVDA Remote profiles added the optional computers array. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30, 34])
class BackupCompatibilityTest {
  private val setting = PrefEntry("pref_backup_compatibility_example", PrefType.BOOLEAN, true)

  private fun oldExport(): JSONObject = signed(JSONObject()
    .put("format", TalkBackBackup.MAGIC_FORMAT)
    .put("version", 2)
    .put("sourcePackage", "fyi.quin.backtalk.cantalktest")
    .put("createdAt", 1793400000000L)
    .put("sections", JSONArray().put(JSONObject()
      .put("id", BackupCategory.SETTINGS.id)
      .put("stores", JSONArray().put(JSONObject()
        .put("id", BackupStore.READER.id)
        .put("preferences", BackupCodec.encodePreferences(listOf(setting)))))
      .put("labels", JSONArray())
      .put("files", JSONArray()))))

  private fun signed(root: JSONObject): JSONObject {
    root.remove("checksum")
    return root.put("checksum", BackupJson.hash(BackupJson.canonical(root).toByteArray(Charsets.UTF_8)))
  }

  private fun section(root: JSONObject) = root.getJSONArray("sections").getJSONObject(0)

  private fun assertSetting(document: BackupDocument) {
    assertFalse(document.legacy)
    assertEquals("fyi.quin.backtalk.cantalktest", document.sourcePackage)
    val restored = document.sections.getValue(BackupCategory.SETTINGS)
    assertEquals(listOf(setting), restored.preferences.getValue(BackupStore.READER))
    assertTrue(restored.computers.isEmpty())
  }

  @Test fun earlierV2PlaintextExportsRemainReadable() {
    assertSetting(BackupCodec.decode(oldExport().toString()))
  }

  @Test fun earlierV2EncryptedExportsRemainReadable() {
    val password = "Synthetic-test-password".toCharArray()
    try {
      val encrypted = BackupCrypto.encrypt(oldExport().toString(), password)
      assertTrue(BackupCodec.requiresPassword(encrypted))
      assertSetting(BackupCodec.decode(encrypted, password))
    } finally {
      password.fill('\u0000')
    }
  }

  @Test fun presentComputersMustBeAnArray() {
    for (invalid in listOf(JSONObject.NULL, "invalid", JSONObject(), true, 1)) {
      val root = oldExport()
      section(root).put("computers", invalid)
      assertFailure(BackupFailure.INVALID_FORMAT, signed(root).toString())
    }
  }

  @Test fun earlierExportStillRequiresItsOriginalChecksum() {
    val root = oldExport().put("createdAt", 1793400000001L)
    assertFailure(BackupFailure.CHECKSUM, root.toString())
  }

  @Test fun unknownSectionFieldsRemainRejected() {
    val root = oldExport()
    section(root).put("unknownField", JSONArray())
    assertFailure(BackupFailure.INVALID_FORMAT, signed(root).toString())
  }

  @Test fun otherSectionFieldsRemainRequired() {
    for (field in listOf("id", "stores", "labels", "files")) {
      val root = oldExport()
      section(root).remove(field)
      assertFailure(BackupFailure.INVALID_FORMAT, signed(root).toString())
    }
  }

  @Test fun currentExportsKeepTheComputersArrayAndRoundTrip() {
    val document = BackupDocument("can.cantalk", 1793400000000L, mapOf(
      BackupCategory.SETTINGS to BackupSection(mapOf(BackupStore.READER to listOf(setting)))))
    val json = BackupCodec.encode(document)
    assertEquals(0, section(JSONObject(json)).getJSONArray("computers").length())
    assertEquals(document, BackupCodec.decode(json))
  }

  @Test fun v2SelectiveRestoreMigratesLegacyAiProviderAndPreservesKeys() {
    val legacySetting = PrefEntry("pref_ai_provider", PrefType.STRING, "chatgpt")
    val groqKey = PrefEntry("pref_groq_api_key", PrefType.STRING, "gsk_test123")
    val list = mutableListOf(legacySetting, groqKey)
    SelectiveBackup.migrateReaderAiPreferences(list)
    val onDevice = list.find { it.key == "pref_on_device_ai_provider" }
    assertNotNull(onDevice)
    assertEquals("chatgpt", onDevice?.value)
    assertEquals("chatgpt", list.find { it.key == "pref_ai_provider" }?.value)
    assertEquals("gsk_test123", list.find { it.key == "pref_groq_api_key" }?.value)
  }

  @Test fun clipboardBackup_acceptsLabeledAndUnlabeledEntries_andRejectsInvalidLabels() {
    val app = org.robolectric.RuntimeEnvironment.getApplication()
    org.robolectric.Shadows.shadowOf(app.getSystemService(android.os.UserManager::class.java)).setUserUnlocked(true)
    val prefs = BackupStore.CLIPBOARD.open(app)
    prefs.edit().clear().commit()

    val validJson = """[{"text":"hola@ejemplo.com","favorite":true,"label":"Correo"},{"text":"sin etiqueta","favorite":false}]"""
    prefs.edit().putString("entries_v1", validJson).commit()
    val captured = SelectiveBackup.capture(app, setOf(BackupCategory.CLIPBOARD_HISTORY))
    val password = "Synthetic-test-password".toCharArray()
    try {
      val encoded = BackupCodec.encode(captured, password)
      val decoded = BackupCodec.decode(encoded, password)
      val restoredEntries = decoded.sections.getValue(BackupCategory.CLIPBOARD_HISTORY)
        .preferences.getValue(BackupStore.CLIPBOARD)
      assertEquals(validJson, restoredEntries.single().value)
    } finally {
      password.fill('\u0000')
    }

    val invalidLabelJson = """[{"text":"abc","favorite":false,"label":"bad\nline"}]"""
    prefs.edit().putString("entries_v1", invalidLabelJson).commit()
    try {
      SelectiveBackup.capture(app, setOf(BackupCategory.CLIPBOARD_HISTORY))
      fail("Expected INVALID_FORMAT for control char in label")
    } catch (e: BackupException) {
      assertEquals(BackupFailure.INVALID_FORMAT, e.failure)
    }
  }

  private fun assertFailure(expected: BackupFailure, json: String) {
    try {
      BackupCodec.decode(json)
      fail("Expected $expected")
    } catch (e: BackupException) {
      assertEquals(expected, e.failure)
    }
  }
}
