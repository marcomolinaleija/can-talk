/*
 * Copyright 2026 can-talk contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.google.android.accessibility.talkback.update

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.preference.PreferenceManager
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.accessibility.talkback.BuildConfig
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.TalkBackPreferenceFilter
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class UpdaterPreferencesTest {
  @Before
  fun setUp() {
    val context = RuntimeEnvironment.getApplication()
    FormFactorUtils.initialize(context)
    SharedPreferencesUtils.getSharedPreferences(context).edit().clear().commit()
  }

  @After
  fun tearDown() {
    Updater.stopAutomaticChecks()
  }

  @Test
  fun settingsShowBuildAndPersistAutomaticCheckChoice() {
    val controller = Robolectric.buildActivity(UpdatePreferencesActivity::class.java)
    controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
    controller.setup()
    try {
      val fragment = UpdatePreferencesFragment()
      controller.get().supportFragmentManager.beginTransaction()
        .add(android.R.id.content, fragment).commitNow()
      val manual = fragment.findPreference<Preference>(fragment.getString(R.string.pref_check_for_updates_key))!!
      val automatic = fragment.findPreference<SwitchPreferenceCompat>(fragment.getString(R.string.pref_auto_update_check_key))!!
      assertTrue(manual.isVisible)
      assertTrue(automatic.isVisible)
      assertNotNull(manual.onPreferenceClickListener)
      assertEquals(fragment.getString(R.string.summary_pref_check_for_updates, BuildConfig.VERSION_NAME), manual.summary)
      assertTrue(automatic.isChecked)
      automatic.isChecked = false
      val prefs = SharedPreferencesUtils.getSharedPreferences(fragment.requireContext())
      assertEquals(prefs, fragment.preferenceManager.sharedPreferences)
      assertFalse(prefs.getBoolean(fragment.getString(R.string.pref_auto_update_check_key), true))
      // Starting the service with checks turned off must leave update history untouched.
      Updater.startAutomaticChecks(fragment.requireContext())
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
      assertFalse(prefs.contains(Updater.PREF_LAST_CHECK_TIME))
    } finally {
      controller.pause().stop().destroy()
    }
  }

  @Test
  fun independentTestAppHidesUpdateSettings() {
    val controller = Robolectric.buildActivity(UpdatePreferencesActivity::class.java)
    controller.get().independentTestApp = true
    controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
    controller.setup()
    try {
      val fragment = UpdatePreferencesFragment()
      controller.get().supportFragmentManager.beginTransaction()
        .add(android.R.id.content, fragment).commitNow()
      assertFalse(fragment.findPreference<Preference>(fragment.getString(R.string.pref_check_for_updates_key))!!.isVisible)
      assertFalse(fragment.findPreference<Preference>(fragment.getString(R.string.pref_auto_update_check_key))!!.isVisible)
    } finally {
      controller.pause().stop().destroy()
    }
  }

  class UpdatePreferencesActivity : FragmentActivity() {
    var independentTestApp = false

    override fun getPackageName(): String =
      if (independentTestApp) "can.cantalk.cantalktest" else "can.cantalk"
  }

  class UpdatePreferencesFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
      preferenceManager.setStorageDeviceProtected()
      // The activity simulates the published app ID; storage belongs to Robolectric's sandbox.
      preferenceManager.sharedPreferencesName = PreferenceManager.getDefaultSharedPreferencesName(
        requireContext().createDeviceProtectedStorageContext())
      setPreferencesFromResource(R.xml.preferences, rootKey)
      TalkBackPreferenceFilter(requireContext()).filterPreferences(preferenceScreen)
      Updater.setUpPreferences(this)
    }
  }
}
