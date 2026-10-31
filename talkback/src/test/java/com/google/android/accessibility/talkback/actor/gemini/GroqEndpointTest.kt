/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.actor.gemini

import com.android.volley.Network
import com.android.volley.RequestQueue
import com.android.volley.toolbox.NoCache
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.gemini.GeminiActor.*
import com.google.android.accessibility.talkback.actor.gemini.local.OnDeviceAiSettings
import com.google.android.accessibility.talkback.actor.gemini.screenqa.OverviewResponse
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GroqEndpointTest {
  private val context = RuntimeEnvironment.getApplication()
  private fun endpoint(): GeminiRestEndpoint {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs.edit().clear().putString(OnDeviceAiSettings.PREF_PROVIDER, GroqSettings.PROVIDER).commit()
    val queue = RequestQueue(NoCache(), Network { throw AssertionError("No network in tests") })
    return GeminiRestEndpoint(context, "unused-google-key", GeminiRestRequestPerformer(queue, prefs))
  }

  @Test fun missingKeyRejectsImageBeforeStartingProgressOrTakingNetwork() {
    var reason: FinishReason? = null
    var message: String? = null
    val listener = object : GeminiResponseListener {
      override fun onResponse(finishReason: FinishReason, response: String) {
        reason = finishReason
        message = response
      }
      override fun onError(errorReason: ErrorReason) { fail("Must explain how to add the Groq key") }
    }
    val started = endpoint().createRequestGeminiCommand(
      GeminiCommand.CommonRequest(Action.IMAGE_DESCRIPTION, "Describe", byteArrayOf(1), true, listener)
    )
    assertFalse(started)
    assertEquals(FinishReason.ERROR_RESPONSE, reason)
    assertTrue(message!!.contains("Groq"))
  }

  @Test fun missingKeyRejectsScreenOverviewWithProviderSpecificError() {
    var result: OverviewResponse? = null
    val listener = object : GeminiResponseCallback<OverviewResponse> {
      override fun onResponse(finishReason: FinishReason, response: OverviewResponse) { result = response }
      override fun onError(errorReason: ErrorReason) { fail("Must explain how to add the Groq key") }
    }
    val started = endpoint().createRequestGeminiCommand(
      GeminiCommand.ScreenOverview(byteArrayOf(1), AccessibilityNodeInfoCompat.obtain(), listener)
    )
    assertFalse(started)
    assertTrue(result is OverviewResponse.Error)
    assertTrue((result as OverviewResponse.Error).message!!.contains("Groq"))
  }

  @Test fun imageAndScreenConsentNameSelectedProvider() {
    endpoint()
    for (resource in listOf(R.string.dialog_message_detailed_ai_description,
      R.string.dialog_message_detailed_ai_promotion, R.string.dialog_message_screen_overview_promotion)) {
      val link = GeminiFunctionUtils.termsLinkText(context, resource)
      val message = GeminiFunctionUtils.descriptionConsentText(context, resource, link)
      assertTrue(link.contains("Groq"))
      assertTrue(message.contains("Groq"))
      assertFalse(message.contains("Google"))
    }
    OnDeviceAiSettings.setOnDevice(SharedPreferencesUtils.getSharedPreferences(context), false)
    assertFalse(GeminiFunctionUtils.termsLinkText(context,
      R.string.dialog_message_detailed_ai_description).contains("Groq"))
  }
}
