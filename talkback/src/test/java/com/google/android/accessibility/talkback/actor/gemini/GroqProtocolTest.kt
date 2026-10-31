/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.actor.gemini

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GroqProtocolTest {
  private fun source(json: Boolean = false) = JSONObject("""{
    "system_instruction":{"parts":[{"text":"Describe for a blind user in Spanish."}]},
    "contents":[{"role":"user","parts":[
      {"text":"What is on screen? Previous question: who is there?"},
      {"inlineData":{"mimeType":"image/jpeg","data":"aW1hZ2U="}}
    ]}],
    "safetySettings":[{"category":"Google-only"}],
    "generationConfig":{"responseMimeType":"${if (json) "application/json" else "text/plain"}"}
  }""")

  @Test fun convertsImageAndInstructionsWithoutGoogleSpecificOptions() {
    val request = GroqProtocol.request(source())
    val messages = request.getJSONArray("messages")
    assertEquals("system", messages.getJSONObject(0).getString("role"))
    assertTrue(messages.getJSONObject(0).getString("content").startsWith("Describe for a blind user in Spanish."))
    val parts = messages.getJSONObject(1).getJSONArray("content")
    assertTrue(parts.getJSONObject(0).getString("text").contains("Previous question"))
    assertEquals("data:image/jpeg;base64,aW1hZ2U=", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    assertFalse(request.has("safetySettings"))
    assertFalse(request.has("generationConfig"))
    assertFalse(request.has("response_format"))
    assertEquals("qwen/qwen3.8-27b", request.getString("model"))
    assertEquals("none", request.getString("reasoning_effort"))
    assertEquals("hidden", request.getString("reasoning_format"))
    assertTrue(request.getInt("max_completion_tokens") in 1..1000)
  }

  @Test fun screenOverviewRequestsJsonWithRoomForStructuredAnswer() {
    val request = GroqProtocol.request(source(json = true))
    assertEquals("json_object", request.getJSONObject("response_format").getString("type"))
    assertTrue(request.getInt("max_completion_tokens") in 1..1000)
  }

  @Test fun completedAnswerReturnsThroughExistingImageDescriptionContract() {
    val response = GroqProtocol.response(JSONObject("""{
      "choices":[{"finish_reason":"stop","message":{"content":" Una persona. ","reasoning":"private"}}]
    }"""))
    assertEquals("Una persona.", response.text())
    assertEquals("STOP", response.finishReason())
  }

  @Test(expected = JSONException::class) fun truncatedAnswerIsNotReportedAsComplete() {
    GroqProtocol.response(JSONObject("""{"choices":[{"finish_reason":"length","message":{"content":"{unfinished"}}]}"""))
  }

  @Test(expected = JSONException::class) fun reasoningWithoutAnswerIsRejected() {
    GroqProtocol.response(JSONObject("""{"choices":[{"finish_reason":"stop","message":{"content":null,"reasoning":"thinking"}}]}"""))
  }

  @Test(expected = JSONException::class) fun blankAnswerIsRejected() {
    GroqProtocol.response(JSONObject("""{"choices":[{"finish_reason":"stop","message":{"content":" "}}]}"""))
  }
}
