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

import android.os.Bundle
import androidx.core.content.edit
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.util.Locale
import java.util.concurrent.Executors

class TranslationFragment : TalkbackBaseFragment() {
  private var continuousPreference: SwitchPreferenceCompat? = null
  private var cacheInfo: Preference? = null
  private val appLoader = Executors.newSingleThreadExecutor()
  private val controller get() = TalkBackService.getInstance()?.translationController

  public override fun getTitle(): CharSequence = getText(R.string.translation_title)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    val context = requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    preferenceManager.setStorageDeviceProtected()
    preferenceManager.sharedPreferencesName = prefsName(context)
    preferenceScreen = preferenceManager.createPreferenceScreen(context)

    preferenceScreen.addPreference(Preference(context).apply {
      setTitle(R.string.translation_provider)
      setSummary(R.string.translation_provider_description)
      isSelectable = false
    })
    preferenceScreen.addPreference(SwitchPreferenceCompat(context).apply {
      key = TranslationSettings.CONSENT
      setTitle(R.string.translation_allow_network)
      setSummary(R.string.translation_privacy)
      setDefaultValue(false)
      isChecked = prefs.getBoolean(key, false)
      setOnPreferenceChangeListener { _, value ->
        if (value == false) true else {
          AlertDialog.Builder(context).setTitle(R.string.translation_allow_network)
            .setMessage(R.string.translation_privacy)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.translation_agree) { _, _ ->
              prefs.edit { putBoolean(key, true) }
              isChecked = true
            }.show()
          false
        }
      }
    })
    continuousPreference = SwitchPreferenceCompat(context).apply {
      key = "translation_continuous_session"
      isPersistent = false
      setTitle(R.string.translation_continuous)
      setSummary(R.string.translation_continuous_description)
      setOnPreferenceChangeListener { _, value ->
        val active = controller
        if (active == null) false
        else { if (value == true) active.toggle() else active.setContinuous(false); active.continuous == value }
      }
    }
    preferenceScreen.addPreference(continuousPreference!!)
    preferenceScreen.addPreference(Preference(context).apply {
      setTitle(R.string.translation_last)
      setSummary(R.string.translation_gestures_description)
      // Settings announcements are not a meaningful last-utterance target; use the menu or gesture.
      isSelectable = false
    })

    val languageCodes = SUPPORTED.split(" ")
    val labels = languageCodes.associateWith {
      Locale.forLanguageTag(it).getDisplayName(Locale.getDefault()).ifBlank { it }
    }
    val ordered = languageCodes.sortedBy { labels[it] }
    fun languagePreference(keyName: String, title: Int, first: String, firstTitle: Int) {
      preferenceScreen.addPreference(ListPreference(context).apply {
        key = keyName
        setTitle(title)
        entries = (listOf(getString(firstTitle)) + ordered.map { labels[it]!! }).toTypedArray()
        entryValues = (listOf(first) + ordered).toTypedArray()
        setDefaultValue(first)
        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
      })
    }
    languagePreference(TranslationSettings.TARGET, R.string.translation_target, "system", R.string.translation_system)
    languagePreference(TranslationSettings.SOURCE, R.string.translation_source, "auto", R.string.translation_auto)
    preferenceScreen.addPreference(ListPreference(context).apply {
      key = TranslationSettings.CACHE
      setTitle(R.string.translation_cache)
      entries = arrayOf(getString(R.string.translation_cache_app), getString(R.string.translation_cache_global),
        getString(R.string.translation_cache_off))
      entryValues = arrayOf("app", "global", "off")
      setDefaultValue("app")
      summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
    })
    cacheInfo = Preference(context).apply {
      setTitle(R.string.translation_cache_info)
      isSelectable = false
      setSummary(R.string.translation_cache_description)
    }
    preferenceScreen.addPreference(cacheInfo!!)
    preferenceScreen.addPreference(Preference(context).apply {
      setTitle(R.string.translation_clear_app)
      setSummary(R.string.translation_clear_app_description)
      setOnPreferenceClickListener { controller?.clearCache(false); updateState(); true }
    })
    preferenceScreen.addPreference(Preference(context).apply {
      setTitle(R.string.translation_clear_all)
      setOnPreferenceClickListener { controller?.clearCache(true); updateState(); true }
    })
    val excluded = MultiSelectListPreference(context).apply {
      key = TranslationSettings.EXCLUDED
      setTitle(R.string.translation_excluded)
      setSummary(R.string.translation_excluded_description)
      setDefaultValue(emptySet<String>())
      entries = emptyArray()
      entryValues = emptyArray()
      isEnabled = false
    }
    preferenceScreen.addPreference(excluded)
    val known = controller?.lastExternalPackage
    appLoader.execute {
      val pm = context.packageManager
      val apps = pm.getInstalledApplications(0).associate { it.packageName to pm.getApplicationLabel(it).toString() }.toMutableMap()
      if (!known.isNullOrBlank()) apps.putIfAbsent(known, known)
      for (name in prefs.getStringSet(TranslationSettings.EXCLUDED, emptySet())!!) apps.putIfAbsent(name, name)
      val sorted = apps.entries.sortedBy { it.value }
      activity?.runOnUiThread {
        if (isAdded) {
          excluded.entries = sorted.map { it.value }.toTypedArray()
          excluded.entryValues = sorted.map { it.key }.toTypedArray()
          excluded.isEnabled = true
        }
      }
    }
    updateState()
  }

  // Match the service's shared preference file and device-protected storage.
  private fun prefsName(context: android.content.Context) =
    context.packageName + "_preferences"

  override fun onResume() { super.onResume(); updateState() }
  private fun updateState() {
    continuousPreference?.isChecked = controller?.continuous == true
    continuousPreference?.isEnabled = controller != null
  }
  override fun onDestroy() { appLoader.shutdownNow(); super.onDestroy() }

  companion object {
    const val SUPPORTED = "af sq ar hy az eu be bn bs bg ca ceb ny zh-CN zh-TW co hr cs da nl en eo et tl fi fr fy gl ka de el gu ht ha haw he hi hmn hu is ig id ga it ja jw kn kk km ko ku ky lo la lv lt lb mk mg ms ml mt mi mr mn my ne no ps fa pl pt pa ro ru sm gd sr st sn sd si sk sl so es su sw sv tg ta te th tr uk ur uz vi cy xh yi yo zu"
  }
}
