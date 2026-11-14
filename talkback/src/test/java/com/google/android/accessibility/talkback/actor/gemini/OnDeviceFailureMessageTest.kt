/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.actor.gemini

import com.google.android.accessibility.talkback.actor.gemini.local.OnDeviceAiSettings
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class OnDeviceFailureMessageTest {
  @Test
  fun theLocalReasonIsPreservedRegardlessOfTheSelectedProvider() {
    val context = RuntimeEnvironment.getApplication()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val reason = "No hay suficiente memoria libre para Gemma. Elige un modelo más pequeño."
    for (provider in listOf("device", "cloud", "chatgpt", "groq")) {
      prefs.edit().putString(OnDeviceAiSettings.PREF_PROVIDER, provider).apply()
      val failure = GeminiFailure.onDevice(reason)
      assertEquals(provider, GeminiFailure.Kind.ON_DEVICE, failure.kind)
      assertEquals(provider, reason, failure.userMessage(context))
      assertEquals(provider, provider, prefs.getString(OnDeviceAiSettings.PREF_PROVIDER, null))
    }
  }
}
