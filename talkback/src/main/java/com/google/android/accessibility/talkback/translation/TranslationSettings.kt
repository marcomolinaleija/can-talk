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

import android.content.SharedPreferences
import android.content.res.Resources
import com.google.android.accessibility.talkback.BuildConfig
import java.util.Locale

object TranslationSettings {
  const val CONSENT = "translation_network_consent"
  const val PROVIDER = "translation_provider"
  const val TARGET = "translation_target"
  const val SOURCE = "translation_source"
  const val CACHE = "translation_cache_scope"
  const val EXCLUDED = "translation_excluded_apps"

  fun source(prefs: SharedPreferences) = prefs.getString(SOURCE, "auto") ?: "auto"
  fun target(prefs: SharedPreferences): String {
    val configured = prefs.getString(TARGET, "system") ?: "system"
    return if (configured == "system") languageCode(Resources.getSystem().configuration.locales[0]) else configured
  }
  fun languageCode(locale: Locale): String = when (locale.language) {
    "zh" -> if (locale.country == "TW" || locale.country == "HK" || locale.script == "Hant") "zh-TW" else "zh-CN"
    "iw" -> "he"
    "in" -> "id"
    "ji" -> "yi"
    else -> locale.language.ifBlank { "en" }
  }

  // Metadata/credentials remain provider-specific. No transport-specific assumptions in the queue.
  fun createProvider(id: String): TranslationProvider = when (id) {
    "google_web" -> GoogleInstantTranslationProvider(BuildConfig.TRANSLATION_INSTANT_KEY)
    else -> throw IllegalArgumentException("Provider is not installed")
  }
}
