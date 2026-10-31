/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.actor.gemini

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.gemini.local.LocalModelManager
import com.google.android.accessibility.talkback.actor.gemini.local.OnDeviceAiSettings
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText

/** Provider selection and the separate Groq credential in Automatic descriptions. */
object GroqPreferences {
  @JvmStatic
  fun setUp(fragment: PreferenceFragmentCompat) {
    val context = fragment.requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    fragment.findPreference<ListPreference>(OnDeviceAiSettings.PREF_PROVIDER)?.apply {
      isPersistent = false
      value = prefs.getString(OnDeviceAiSettings.PREF_PROVIDER, prefs.getString("pref_ai_provider", ChatGptAuthManager.PROVIDER_CHATGPT))
      summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
      setOnPreferenceChangeListener { _, newValue ->
        val selected = newValue as String
        if (selected == OnDeviceAiSettings.PROVIDER_DEVICE && !LocalModelManager.get(context).isReady()) {
          Toast.makeText(context, R.string.pref_on_device_ai_enable_needs_model, Toast.LENGTH_LONG).show()
          false
        } else {
          prefs.edit()
            .putString(OnDeviceAiSettings.PREF_PROVIDER, selected)
            .putString("pref_ai_provider", selected)
            .apply()
          OnDeviceAiSettings.setOnDevice(prefs, selected == OnDeviceAiSettings.PROVIDER_DEVICE)
          ChatGptPreference.refreshUi(fragment)
          true
        }
      }
    }
    fragment.findPreference<Preference>(GroqSettings.PREF_KEY)?.apply {
      setSummary(if (GroqSettings.userKey(prefs).isEmpty())
        R.string.groq_api_key_not_set else R.string.groq_api_key_set)
      setOnPreferenceClickListener {
        showKeyDialog(fragment, this)
        true
      }
    }
  }

  private fun showKeyDialog(fragment: PreferenceFragmentCompat, preference: Preference) {
    val context = fragment.requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val field = KeyboardSafeEditText(context).apply {
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
      isSingleLine = true
      setHint(R.string.groq_api_key_hint)
      setText(GroqSettings.userKey(prefs))
    }
    val padding = (24 * context.resources.displayMetrics.density).toInt()
    val container = FrameLayout(context).apply {
      setPadding(padding, 0, padding, 0)
      addView(field)
    }
    AlertDialog.Builder(context)
      .setTitle(R.string.title_pref_groq_api_key)
      .setMessage(R.string.groq_api_key_message)
      .setView(container)
      .setPositiveButton(R.string.gemini_api_key_save) { _, _ ->
        GroqSettings.setUserKey(prefs, field.text?.toString().orEmpty())
        preference.setSummary(if (GroqSettings.userKey(prefs).isEmpty())
          R.string.groq_api_key_not_set else R.string.groq_api_key_set)
      }
      .setNeutralButton(R.string.gemini_api_key_get) { _, _ ->
        try {
          fragment.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GroqSettings.GET_KEY_URL)))
        } catch (_: ActivityNotFoundException) {
          // The dialog also names the address when no browser is available.
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }
}
