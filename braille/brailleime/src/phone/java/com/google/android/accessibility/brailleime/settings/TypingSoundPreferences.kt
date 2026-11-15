/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.brailleime.settings

import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.brailleime.BrailleImeTypingSounds
import com.google.android.accessibility.brailleime.TypingSoundSettings

/** Configure in the braille preferences, including the inverse dependency of the volume slider. */
object TypingSoundPreferences {
  @JvmStatic
  fun configure(fragment: PreferenceFragmentCompat) {
    val context = fragment.requireContext()
    val profiles = TypingSoundSettings.availableProfiles()
    fragment.findPreference<ListPreference>(TypingSoundSettings.PROFILE_KEY)?.apply {
      entries = profiles.map { context.getString(it.title) }.toTypedArray()
      entryValues = profiles.map { it.value }.toTypedArray()
      value = TypingSoundSettings.profile(context).let {
        if (it in profiles) it.value else TypingSoundSettings.Profile.ANDROID.value
      }
      summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
      setOnPreferenceChangeListener { _, newValue ->
        TypingSoundSettings.prefs(context).edit()
          .putString(TypingSoundSettings.PROFILE_KEY, newValue as String).apply()
        BrailleImeTypingSounds.prepare(context)
        true
      }
    }
    fragment.findPreference<SwitchPreferenceCompat>(TypingSoundSettings.FOLLOW_KEY)?.apply {
      isChecked =
        TypingSoundSettings.prefs(context).getBoolean(TypingSoundSettings.FOLLOW_KEY, true)
      setOnPreferenceChangeListener { _, newValue ->
        val follow = newValue as Boolean
        TypingSoundSettings.prefs(context).edit()
          .putBoolean(TypingSoundSettings.FOLLOW_KEY, follow).apply()
        fragment.findPreference<SeekBarPreference>(TypingSoundSettings.VOLUME_KEY)?.isEnabled =
          BrailleUserPreferences.readTypingSounds(context) && !follow
        BrailleImeTypingSounds.prepare(context)
        true
      }
    }
    fragment.findPreference<SeekBarPreference>(TypingSoundSettings.VOLUME_KEY)?.apply {
      value = TypingSoundSettings.prefs(context).getInt(TypingSoundSettings.VOLUME_KEY, 50)
        .coerceIn(0, TypingSoundSettings.MAX_VOLUME_PERCENT)
      setOnPreferenceChangeListener { _, newValue ->
        val percent = (newValue as Int).coerceIn(0, TypingSoundSettings.MAX_VOLUME_PERCENT)
        TypingSoundSettings.prefs(context).edit()
          .putInt(TypingSoundSettings.VOLUME_KEY, percent).apply()
        BrailleImeTypingSounds.prepare(context)
        true
      }
    }
    fragment.findPreference<Preference>("pref_brailleime_sound_preview")
      ?.setOnPreferenceClickListener {
        BrailleImeTypingSounds.play(context, BrailleImeTypingSounds.Key.CHARACTER, true)
        true
      }
    updateEnabled(fragment)
    BrailleImeTypingSounds.prepare(context)
  }

  @JvmStatic
  fun updateEnabled(fragment: PreferenceFragmentCompat) {
    val context = fragment.requireContext()
    fragment.findPreference<SeekBarPreference>(TypingSoundSettings.VOLUME_KEY)?.isEnabled =
      BrailleUserPreferences.readTypingSounds(context) &&
        !TypingSoundSettings.prefs(context).getBoolean(TypingSoundSettings.FOLLOW_KEY, true)
    BrailleImeTypingSounds.prepare(context)
  }
}
