/*
 * Copyright 2026 can-talk contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.google.android.accessibility.talkback.audio

import com.google.android.accessibility.talkback.audio.AudioDeviceRouter.AudioTarget
import com.google.android.accessibility.talkback.audio.BluetoothAudioDeviceRules.Device
import com.google.android.accessibility.talkback.audio.BluetoothAudioDeviceRules.Role
import com.google.android.accessibility.talkback.audio.BluetoothAudioDeviceRules.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothAudioDeviceRulesTest {
  private val speaker = Device("address:AA:BB:CC:DD:EE:01", "Sala")
  private val headphones = Device("address:AA:BB:CC:DD:EE:02", "Auriculares")
  private val rules = listOf(Rule(speaker, Role.SPEAKER), Rule(headphones, Role.HEADPHONES))

  @Test fun unconfiguredDevice_keepsEveryExistingTarget() {
    val targets = listOf(AudioTarget.SystemDefault, AudioTarget.PhoneSpeaker,
      AudioTarget.AnyExternal, AudioTarget.SpecificDevice(8, "Otro dispositivo"))
    targets.forEach { baseline ->
      assertEquals(baseline, BluetoothAudioDeviceRules.resolveTarget(baseline, listOf("new"), rules))
    }
  }

  @Test fun switchingFromSpeakerToHeadphonesAndDisconnecting_restoresBaseline() {
    val baseline = AudioTarget.PhoneSpeaker
    assertEquals(AudioTarget.PhoneSpeaker,
      BluetoothAudioDeviceRules.resolveTarget(baseline, listOf(speaker.identity), rules))
    assertEquals(AudioTarget.SystemDefault,
      BluetoothAudioDeviceRules.resolveTarget(baseline, listOf(headphones.identity), rules))
    assertEquals(baseline, BluetoothAudioDeviceRules.resolveTarget(baseline, emptyList(), rules))
  }

  @Test fun speakerRule_overridesSystemDefault() {
    assertEquals(AudioTarget.PhoneSpeaker, BluetoothAudioDeviceRules.resolveTarget(
      AudioTarget.SystemDefault, listOf(speaker.identity), rules))
  }

  @Test fun unchangedRole_disablesOverrideWithoutChangingBaseline() {
    assertEquals(AudioTarget.AnyExternal, BluetoothAudioDeviceRules.resolveTarget(
      AudioTarget.AnyExternal, listOf(speaker.identity), listOf(Rule(speaker, Role.UNCHANGED))))
  }

  @Test fun simultaneousDifferentDevices_doNotPickArbitraryRule() {
    assertEquals(AudioTarget.SystemDefault, BluetoothAudioDeviceRules.resolveTarget(
      AudioTarget.SystemDefault, listOf(speaker.identity, headphones.identity), rules))
  }

  @Test fun multipleProfilesOfSameDevice_shareRule() {
    assertEquals(AudioTarget.PhoneSpeaker, BluetoothAudioDeviceRules.resolveTarget(
      AudioTarget.SystemDefault, listOf(speaker.identity, speaker.identity), rules))
  }

  @Test fun sameNameDifferentAddresses_remainSeparate() {
    val first = BluetoothAudioDeviceRules.identity("aa:bb:cc:dd:ee:01", "Audio")!!
    val second = BluetoothAudioDeviceRules.identity("AA:BB:CC:DD:EE:02", "Audio")!!
    val classified = listOf(Rule(Device(first, "Audio"), Role.SPEAKER))
    assertEquals(AudioTarget.SystemDefault, BluetoothAudioDeviceRules.resolveTarget(
      AudioTarget.SystemDefault, listOf(second), classified))
  }

  @Test fun addressIdentity_survivesRenameAndCaseChanges() {
    assertEquals(BluetoothAudioDeviceRules.identity("aa:bb:cc:dd:ee:01", "Old name"),
      BluetoothAudioDeviceRules.identity(" AA:BB:CC:DD:EE:01 ", "New name"))
  }

  @Test fun unavailableAddress_usesNameAndRejectsMissingIdentity() {
    assertEquals("name:Sala", BluetoothAudioDeviceRules.identity("", " Sala "))
    assertEquals("name:Sala", BluetoothAudioDeviceRules.identity("02:00:00:00:00:00", "Sala"))
    assertEquals("name:Sala", BluetoothAudioDeviceRules.identity("00:00:00:00:00:00", "Sala"))
    assertNull(BluetoothAudioDeviceRules.identity(null, "unknown"))
    assertNull(BluetoothAudioDeviceRules.identity(null, ""))
  }

  @Test fun savedRules_surviveRestartAndKeepDisconnectedNames() {
    val saved = rules + Rule(Device("name:Sin configurar", "Sin configurar"), Role.UNCHANGED)
    assertEquals(saved, BluetoothAudioDeviceRules.decode(BluetoothAudioDeviceRules.encode(saved)))
  }

  @Test fun malformedOrFutureSettings_doNotForceAnOutput() {
    assertEquals(emptyList<Rule>(), BluetoothAudioDeviceRules.decode("invalid json"))
    assertEquals(emptyList<Rule>(), BluetoothAudioDeviceRules.decode(null))
    assertEquals(Role.UNCHANGED, Role.parse("future-role"))
  }
}
