/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.translation

import android.content.DialogInterface
import androidx.fragment.app.FragmentActivity
import androidx.preference.ListPreference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationPreferencesTest {
  private lateinit var host: ActivityController<FragmentActivity>
  private lateinit var fragment: TranslationFragment
  @Before fun setup() {
    host = Robolectric.buildActivity(FragmentActivity::class.java)
    host.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
    host.setup()
    SharedPreferencesUtils.getSharedPreferences(host.get()).edit().clear().commit()
    fragment = TranslationFragment()
    host.get().supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
  }
  @After fun cleanup() { host.pause().stop().destroy() }

  @Test fun settingsAreInitiallyOffAndUseServicePreferenceStorage() {
    val consent = fragment.findPreference<SwitchPreferenceCompat>(TranslationSettings.CONSENT)!!
    assertFalse(consent.isChecked)
    val continuous = fragment.findPreference<SwitchPreferenceCompat>("translation_continuous_session")!!
    assertFalse(continuous.isPersistent)
    assertFalse(continuous.isChecked)
    assertFalse(continuous.isEnabled)
    fragment.findPreference<ListPreference>(TranslationSettings.TARGET)!!.value = "fr"
    assertEquals("fr", SharedPreferencesUtils.getSharedPreferences(host.get()).getString(TranslationSettings.TARGET, null))
    assertEquals("app", fragment.findPreference<ListPreference>(TranslationSettings.CACHE)!!.value)
  }
  @Test fun turningOnNetworkRequiresExplicitDialogAcceptance() {
    val preference = fragment.findPreference<SwitchPreferenceCompat>(TranslationSettings.CONSENT)!!
    assertFalse(preference.onPreferenceChangeListener!!.onPreferenceChange(preference, true))
    assertFalse(SharedPreferencesUtils.getSharedPreferences(host.get()).getBoolean(TranslationSettings.CONSENT, false))
    val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
    dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    assertTrue(preference.isChecked)
    assertTrue(SharedPreferencesUtils.getSharedPreferences(host.get()).getBoolean(TranslationSettings.CONSENT, false))
  }
  @Test fun mexicanSpanishSelectsTranslatedCommands() {
    RuntimeEnvironment.setQualifiers("es-rMX")
    assertEquals("Traducción", host.get().getString(R.string.translation_title))
    assertEquals("Traducir la última frase verbalizada", host.get().getString(R.string.translation_last))
  }
}
