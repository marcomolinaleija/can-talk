/*
 * Copyright 2026 can-talk contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.google.android.accessibility.talkback.audio

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.view.ContextThemeWrapper
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.audio.BluetoothAudioDeviceRules.Device
import com.google.android.accessibility.talkback.audio.BluetoothAudioDeviceRules.Role
import com.google.android.accessibility.talkback.audio.BluetoothAudioDeviceRules.Rule
import com.google.android.accessibility.talkback.preference.TalkBackPreferenceFilter
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercises the real preference XML, filtering and device-protected settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es-rUS", application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BluetoothAudioDevicePreferencesTest {
  private lateinit var context: Context
  private lateinit var prefs: SharedPreferences

  @Before fun setUp() {
    context = ContextThemeWrapper(RuntimeEnvironment.getApplication(),
      androidx.appcompat.R.style.Theme_AppCompat)
    FormFactorUtils.initialize(context)
    prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs.edit().clear().commit()
  }

  private fun createCategory(): PreferenceCategory {
    val manager = PreferenceManager(context)
    val screen = manager.inflateFromResource(context, R.xml.sound_and_vibration_preferences, null)
    manager.setPreferences(screen)
    TalkBackPreferenceFilter(context).filterPreferences(screen)
    val category = screen.findPreference<PreferenceCategory>(BluetoothAudioDeviceRules.CATEGORY_KEY)
    assertNotNull("Bluetooth settings must survive the empty-group filter", category)
    BluetoothAudioDeviceRules.updatePreferences(category!!, context)
    return category
  }

  @Test fun noConnectedDevices_sectionStillAppearsWithSpanishInstructions() {
    val category = createCategory()
    assertEquals("Dispositivos Bluetooth", category.title)
    assertEquals(2, category.preferenceCount)
    assertEquals("Conecta un dispositivo de audio Bluetooth para configurarlo.",
      category.getPreference(1).title)
  }

  @Test
  @Config(sdk = [28])
  fun olderAndroid_sectionAlsoSurvivesFiltering() {
    assertEquals("Dispositivos Bluetooth", createCategory().title)
  }

  @Test fun disconnectedDevice_canChangeRoleAndPersistsAcrossScreenRecreation() {
    val device = Device("address:AA:BB:CC:DD:EE:01", "Altavoz de sala")
    val outputKey = context.getString(R.string.pref_audio_output_device_key)
    prefs.edit().putString(outputKey, "speaker").putString(BluetoothAudioDeviceRules.PREF_KEY,
      BluetoothAudioDeviceRules.encode(listOf(Rule(device, Role.SPEAKER)))).commit()
    val category = createCategory()
    val preference = category.getPreference(1) as ListPreference
    assertEquals("Altavoz de sala (desconectado)", preference.title)
    assertFalse(preference.isPersistent)
    assertTrue(preference.callChangeListener("headphones"))
    assertEquals(Role.HEADPHONES, BluetoothAudioDeviceRules.decode(
      prefs.getString(BluetoothAudioDeviceRules.PREF_KEY, null)).single().role)
    assertEquals("speaker", prefs.getString(outputKey, null))
    val recreated = createCategory().getPreference(1) as ListPreference
    assertEquals("headphones", recreated.value)
    assertEquals("Auriculares: can-talk en Bluetooth", recreated.summary)
    assertTrue(recreated.callChangeListener("unchanged"))
    assertEquals(Role.UNCHANGED, BluetoothAudioDeviceRules.decode(
      prefs.getString(BluetoothAudioDeviceRules.PREF_KEY, null)).single().role)
    assertEquals("speaker", prefs.getString(outputKey, null))
  }
}
