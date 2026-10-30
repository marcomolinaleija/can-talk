/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.brailleime

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.brailleime.settings.TypingSoundPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es-rUS")
class TypingSoundPreferencesTest {
  class SoundFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
      preferenceManager.sharedPreferencesName = BrailleUserPreferences.BRAILLE_SHARED_PREFS_FILENAME
      preferenceManager.setStorageDeviceProtected()
      setPreferencesFromResource(R.xml.brailleime_preferences, rootKey)
      preferenceManager.sharedPreferences!!.registerOnSharedPreferenceChangeListener(listener)
      TypingSoundPreferences.configure(this)
    }
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
      TypingSoundPreferences.updateEnabled(this)
    }
    override fun onDestroy() {
      preferenceManager.sharedPreferences!!.unregisterOnSharedPreferenceChangeListener(listener)
      super.onDestroy()
    }
  }

  private lateinit var activity: FragmentActivity
  private lateinit var fragment: SoundFragment

  @Before fun setup() {
    TypingSoundSettings.prefs(RuntimeEnvironment.getApplication()).edit().clear().commit()
    val controller = Robolectric.buildActivity(FragmentActivity::class.java)
    controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
    activity = controller.setup().get()
    fragment = SoundFragment()
    activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
  }

  @After fun cleanup() {
    activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()
    BrailleImeTypingSounds.release()
  }

  @Test fun defaults_areSpanishAndKeepManualVolumeDisabled() {
    val profile = fragment.findPreference<ListPreference>(TypingSoundSettings.PROFILE_KEY)!!
    assertEquals("Sonidos del teclado", profile.title)
    assertEquals("theme", profile.value)
    assertEquals("Tema de sonidos actual", profile.summary)
    assertEquals(listOf("theme", "android"), profile.entryValues.map { it.toString() })
    assertFalse(profile.isEnabled)
    assertTrue(fragment.findPreference<SwitchPreferenceCompat>(TypingSoundSettings.FOLLOW_KEY)!!.isChecked)
    val volume = fragment.findPreference<SeekBarPreference>(TypingSoundSettings.VOLUME_KEY)!!
    assertEquals(0, volume.min)
    assertEquals(200, volume.max)
    assertEquals(50, volume.value)
    assertFalse(volume.isEnabled)
  }

  @Test fun controlsPersist_inKeyboardStorageAndManualSliderFollowsBothSwitches() {
    val enabled = fragment.findPreference<SwitchPreferenceCompat>(
      getString(com.google.android.accessibility.braille.common.R.string.pref_brailleime_typing_sounds))!!
    enabled.isChecked = true
    val profile = fragment.findPreference<ListPreference>(TypingSoundSettings.PROFILE_KEY)!!
    assertTrue(profile.isEnabled)
    val follow = fragment.findPreference<SwitchPreferenceCompat>(TypingSoundSettings.FOLLOW_KEY)!!
    val volume = fragment.findPreference<SeekBarPreference>(TypingSoundSettings.VOLUME_KEY)!!
    assertFalse(volume.isEnabled)
    follow.isChecked = false
    assertTrue(volume.isEnabled)
    volume.value = 80
    profile.value = "android"
    assertEquals(0.8f, TypingSoundSettings.volume(activity), 0f)
    assertEquals(TypingSoundSettings.Profile.ANDROID, TypingSoundSettings.profile(activity))
    enabled.isChecked = false
    assertFalse(volume.isEnabled)
    assertEquals(80, TypingSoundSettings.prefs(activity).getInt(TypingSoundSettings.VOLUME_KEY, 0))
  }

  private fun getString(id: Int) = activity.getString(id)

  @Test fun amplifiedVolume_isSavedAndRetainedWhenPreferencesAreReopened() {
    fragment.findPreference<SwitchPreferenceCompat>(
      getString(com.google.android.accessibility.braille.common.R.string.pref_brailleime_typing_sounds))!!
      .isChecked = true
    fragment.findPreference<SwitchPreferenceCompat>(TypingSoundSettings.FOLLOW_KEY)!!.isChecked = false
    fragment.findPreference<SeekBarPreference>(TypingSoundSettings.VOLUME_KEY)!!.value = 200
    assertEquals(2f, TypingSoundSettings.volume(activity), 0f)
    activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()
    fragment = SoundFragment()
    activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
    val volume = fragment.findPreference<SeekBarPreference>(TypingSoundSettings.VOLUME_KEY)!!
    assertEquals(200, volume.value)
    assertTrue(volume.isEnabled)
    assertEquals(200, TypingSoundSettings.prefs(activity).getInt(TypingSoundSettings.VOLUME_KEY, 0))
    assertEquals(2f, TypingSoundSettings.volume(activity), 0f)
  }
}
