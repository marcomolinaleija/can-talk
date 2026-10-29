/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.backup

import android.content.Context
import com.google.android.accessibility.talkback.labeling.LabelProvider
import com.google.android.accessibility.talkback.remote.NvdaRemoteActivity
import com.google.android.accessibility.talkback.remote.NvdaRemoteBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectiveBackupRemoteTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Before
  fun setUp() {
    Robolectric.buildContentProvider(LabelProvider::class.java).create(LabelProvider.AUTHORITY)
  }

  @Test
  fun testInventoryIncludesRemoteSettingsAndGestures() {
    val remotePrefs = NvdaRemoteActivity.preferences(context)
    remotePrefs.edit()
      .putString("host", "custom.server.com")
      .putString("port", "1234")
      .putString("trusted_cert_custom.server.com", "AA:BB:CC:DD")
      .apply()

    val bindingsPrefs = NvdaRemoteBindings.preferences(context)
    bindingsPrefs.edit()
      .putString("CONTROL/tap_1", "key:0x41")
      .putString("KEYBOARD/hold_5", "alt")
      .apply()

    val inv = SelectiveBackup.inventory(context)
    assertEquals(3, inv[BackupCategory.NVDA_REMOTE_SETTINGS]) // host, port, trusted_cert_...
    assertEquals(2, inv[BackupCategory.NVDA_REMOTE_GESTURES]) // 2 gestures
  }

  @Test
  fun testExportAndCaptureSettingsAndGestures() {
    val settingsPrefs = NvdaRemoteActivity.preferences(context)
    settingsPrefs.edit()
      .putString("host", "office.remote")
      .putString("port", "6837")
      .putString("input_mode", "KEYBOARD")
      .putString("lock_behavior", "MUTE")
      .putString("trusted_cert_office.remote", "F1:F2:F3:F4")
      .apply()

    val gesturesPrefs = NvdaRemoteBindings.preferences(context)
    gesturesPrefs.edit()
      .putString("CONTROL/tap_12", "key:0x42")
      .apply()

    val selected = setOf(
      BackupCategory.NVDA_REMOTE_SETTINGS,
      BackupCategory.NVDA_REMOTE_GESTURES,
    )

    val doc = SelectiveBackup.capture(context, selected)
    assertFalse(doc.containsSensitiveData)

    val json = BackupCodec.encode(doc, null)
    assertFalse(BackupCodec.requiresPassword(json))

    val decodedDoc = BackupCodec.decode(json)
    val settingsSection = decodedDoc.sections[BackupCategory.NVDA_REMOTE_SETTINGS]
    val gesturesSection = decodedDoc.sections[BackupCategory.NVDA_REMOTE_GESTURES]
    assertNotNull(settingsSection)
    assertNotNull(gesturesSection)

    val settingsEntries = settingsSection!!.preferences[BackupStore.NVDA_REMOTE_SETTINGS]
    assertNotNull(settingsEntries)
    val settingsMap = settingsEntries!!.associate { it.key to it.value }
    assertEquals("office.remote", settingsMap["host"])
    assertEquals("6837", settingsMap["port"])
    assertEquals("KEYBOARD", settingsMap["input_mode"])
    assertEquals("MUTE", settingsMap["lock_behavior"])
    assertEquals("F1:F2:F3:F4", settingsMap["trusted_cert_office.remote"])

    val gesturesEntries = gesturesSection!!.preferences[BackupStore.NVDA_REMOTE_GESTURES]
    assertNotNull(gesturesEntries)
    val gesturesMap = gesturesEntries!!.associate { it.key to it.value }
    assertEquals("key:0x42", gesturesMap["CONTROL/tap_12"])
  }

  @Test
  fun testComputersCodecRequiresPasswordAndPreservesProfiles() {
    val computers = listOf(
      BackupRemoteComputer("comp-1", "Work Desktop", "work.desktop.org", 6837, 0x2D, "topsecret", 123456789L),
      BackupRemoteComputer("comp-2", "Home Laptop", "home.remote", 6837, 0x14, null, 987654321L),
    )

    val section = BackupSection(computers = computers)
    val doc = BackupDocument(
      sourcePackage = context.packageName,
      createdAt = System.currentTimeMillis(),
      sections = mapOf(BackupCategory.NVDA_REMOTE_COMPUTERS to section),
    )

    assertTrue(doc.containsSensitiveData)

    // Unencrypted export must fail
    try {
      BackupCodec.encode(doc, null)
      fail("Expected PASSWORD_REQUIRED")
    } catch (e: BackupException) {
      assertEquals(BackupFailure.PASSWORD_REQUIRED, e.failure)
    }

    val password = "SecretPassword123!".toCharArray()
    val encrypted = BackupCodec.encode(doc, password)
    assertTrue(BackupCodec.requiresPassword(encrypted))

    // Decode with wrong password fails
    try {
      BackupCodec.decode(encrypted, "WrongPassword!".toCharArray())
      fail("Expected WRONG_PASSWORD")
    } catch (e: BackupException) {
      assertEquals(BackupFailure.WRONG_PASSWORD, e.failure)
    }

    // Decode with correct password succeeds
    val decoded = BackupCodec.decode(encrypted, password)
    val restoredComputers = decoded.sections[BackupCategory.NVDA_REMOTE_COMPUTERS]?.computers
    assertNotNull(restoredComputers)
    assertEquals(2, restoredComputers!!.size)

    val home = restoredComputers.first { it.id == "comp-2" }
    assertEquals("Home Laptop", home.name)
    assertEquals("home.remote", home.host)
    assertEquals(6837, home.port)
    assertEquals(0x14, home.nvdaModifier)
    assertNull(home.password)
    assertEquals(987654321L, home.lastUsed)

    val work = restoredComputers.first { it.id == "comp-1" }
    assertEquals("Work Desktop", work.name)
    assertEquals("topsecret", work.password)
  }

  @Test
  fun testComputersCodecRejectsDuplicatesAndInvalidData() {
    val invalidModifier = listOf(
      BackupRemoteComputer("comp-1", "PC", "host.com", 6837, 0x99, null),
    )
    val docInvalidModifier = BackupDocument(
      sourcePackage = context.packageName,
      createdAt = System.currentTimeMillis(),
      sections = mapOf(BackupCategory.NVDA_REMOTE_COMPUTERS to BackupSection(computers = invalidModifier)),
    )
    val password = "Password123!".toCharArray()
    try {
      BackupCodec.encode(docInvalidModifier, password)
      fail("Expected INVALID_FORMAT for invalid modifier")
    } catch (e: BackupException) {
      assertEquals(BackupFailure.INVALID_FORMAT, e.failure)
    }
  }
}
