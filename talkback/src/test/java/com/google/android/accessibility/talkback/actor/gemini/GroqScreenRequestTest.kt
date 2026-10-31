/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.actor.gemini

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.actor.gemini.DataFieldUtils.GeminiResponse
import com.google.android.accessibility.talkback.actor.gemini.GeminiActor.ErrorReason
import com.google.android.accessibility.talkback.actor.gemini.GeminiActor.FinishReason
import com.google.android.accessibility.talkback.actor.gemini.GeminiRestRequestPerformer.GeminiRestResponseCallback
import com.google.android.accessibility.talkback.actor.gemini.screenqa.OverviewResponse
import com.google.android.accessibility.talkback.actor.gemini.screenqa.ScreenTree
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GroqScreenRequestTest {
  private var payload: JSONObject? = null
  private var callback: GeminiRestResponseCallback? = null
  private var result: OverviewResponse? = null
  private val listener = object : GeminiResponseCallback<OverviewResponse> {
    override fun onResponse(finishReason: FinishReason, response: OverviewResponse) { result = response }
    override fun onError(errorReason: ErrorReason) { fail("Unexpected error: $errorReason") }
  }
  private fun requester(style: PromptStyle) = ScreenOverviewRequester(
    style = { style }, performRequest = { body, response -> payload = body; callback = response })

  private fun answer(text: String) = callback!!.onResponse(GeminiResponse.builder()
    .setText(text).setFinishReason(DataFieldUtils.FINISH_REASON_STOP).build())

  @Test fun overviewKeepsTheImageAndParsesTheVisibleFieldsWithASmallOutputBudget() {
    requester(PromptStyle.GROQ).requestOverview(GeminiCommand.ScreenOverview(
      byteArrayOf(1, 2, 3), AccessibilityNodeInfoCompat.obtain(), listener), JSONArray())
    val request = GroqProtocol.request(payload!!)
    assertTrue(request.getInt("max_completion_tokens") < 1000)
    val parts = request.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
    assertFalse(parts.getJSONObject(0).getString("text").contains("top_ui_elements"))
    assertEquals("data:image/jpeg;base64,AQID", parts.getJSONObject(1)
      .getJSONObject("image_url").getString("url"))
    answer("""{"summary":"A settings screen.","top_images":[]}""")
    assertTrue(result is OverviewResponse.Success)
  }

  @Test fun queryKeepsTheQuestionAndParsesAnAnswerWithoutUnusedControls() {
    val tree = ScreenTree.fromNode(AccessibilityNodeInfoCompat.obtain())
    requester(PromptStyle.GROQ).requestQuery(GeminiCommand.ScreenQuery(
      byteArrayOf(1), tree, "What color is the button?", null, listener), JSONArray())
    val request = GroqProtocol.request(payload!!)
    val prompt = request.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
      .getJSONObject(0).getString("text")
    assertTrue(prompt.contains("What color is the button?"))
    assertFalse(prompt.contains("ui_elements"))
    answer("""{"answer":"The button is blue."}""")
    assertTrue(result is OverviewResponse.QueryAnswer)
  }

  @Test fun geminiStillUsesItsFullScreenResponseFormat() {
    requester(PromptStyle.CLOUD).requestOverview(GeminiCommand.ScreenOverview(
      byteArrayOf(1), AccessibilityNodeInfoCompat.obtain(), listener), JSONArray())
    assertTrue(payload!!.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
      .getJSONObject(0).getString("text").contains("top_ui_elements"))
  }
}
