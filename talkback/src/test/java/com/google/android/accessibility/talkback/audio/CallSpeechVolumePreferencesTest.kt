/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.audio

import android.app.Application
import android.content.Context
import androidx.appcompat.view.ContextThemeWrapper
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.TalkBackPreferenceFilter
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.CallSpeechVolumeController
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es-rUS", application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CallSpeechVolumePreferencesTest {
  private lateinit var context: Context

  @Before fun setUp() {
    context = ContextThemeWrapper(RuntimeEnvironment.getApplication(),
      androidx.appcompat.R.style.Theme_AppCompat)
    FormFactorUtils.initialize(context)
    SharedPreferencesUtils.getSharedPreferences(context).edit().clear().commit()
  }

  private fun screen(): PreferenceScreen {
    val manager = PreferenceManager(context)
    manager.setStorageDeviceProtected()
    val screen = manager.inflateFromResource(context, R.xml.text_to_speech_preferences, null)
    manager.setPreferences(screen)
    screen.onAttached()
    TalkBackPreferenceFilter(context).filterPreferences(screen)
    return screen
  }

  @Test fun spanishPreferences_surviveFilteringAndStartDisabled() {
    val screen = screen()
    val switch = screen.findPreference<SwitchPreferenceCompat>(CallSpeechVolumeController.ENABLED_KEY)!!
    val slider = screen.findPreference<SeekBarPreference>(CallSpeechVolumeController.PERCENT_KEY)!!
    assertEquals("Estabilizar voz durante llamadas (experimental)", switch.title)
    assertFalse(switch.isChecked)
    assertFalse(slider.isEnabled)
    assertEquals(25, slider.min)
    assertEquals(100, slider.max)
    assertEquals(50, slider.value)
    screen.onDetached()
  }

  @Test fun choicesPersist_inSameStorageUsedBySpeechController() {
    val screen = screen()
    val switch = screen.findPreference<SwitchPreferenceCompat>(CallSpeechVolumeController.ENABLED_KEY)!!
    val slider = screen.findPreference<SeekBarPreference>(CallSpeechVolumeController.PERCENT_KEY)!!
    switch.isChecked = true
    assertTrue(slider.isEnabled)
    slider.value = 75
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    assertTrue(prefs.getBoolean(CallSpeechVolumeController.ENABLED_KEY, false))
    assertEquals(75, prefs.getInt(CallSpeechVolumeController.PERCENT_KEY, 0))
    screen.onDetached()
    val recreated = screen()
    assertTrue(recreated.findPreference<SwitchPreferenceCompat>(CallSpeechVolumeController.ENABLED_KEY)!!.isChecked)
    assertEquals(75, recreated.findPreference<SeekBarPreference>(CallSpeechVolumeController.PERCENT_KEY)!!.value)
    recreated.onDetached()
  }
}
