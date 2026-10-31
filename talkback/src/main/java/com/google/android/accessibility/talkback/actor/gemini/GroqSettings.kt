/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.actor.gemini

import android.content.SharedPreferences
import com.google.android.accessibility.talkback.actor.gemini.local.OnDeviceAiSettings

/** Groq uses its own key; choosing it never sends the Gemini key to another service. */
object GroqSettings {
  const val PROVIDER = "groq"
  const val PREF_KEY = "pref_groq_api_key"
  const val MODEL = "qwen/qwen3.8-27b"
  const val URL = "https://api.groq.com/openai/v1/chat/completions"
  const val GET_KEY_URL = "https://console.groq.com/keys"
  const val TERMS_URL = "https://console.groq.com/docs/legal"

  @JvmStatic
  fun isSelected(prefs: SharedPreferences): Boolean =
    prefs.getString(OnDeviceAiSettings.PREF_PROVIDER, OnDeviceAiSettings.PROVIDER_CLOUD) == PROVIDER

  @JvmStatic
  fun userKey(prefs: SharedPreferences): String = prefs.getString(PREF_KEY, null)?.trim().orEmpty()

  fun setUserKey(prefs: SharedPreferences, key: String) {
    val editor = prefs.edit()
    val trimmed = key.trim()
    if (trimmed.isEmpty()) editor.remove(PREF_KEY) else editor.putString(PREF_KEY, trimmed)
    editor.apply()
  }
}
