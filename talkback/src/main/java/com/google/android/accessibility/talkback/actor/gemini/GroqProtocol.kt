/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.actor.gemini

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Adapts the existing image and screen Q&A payloads to Groq's chat completions API. */
internal object GroqProtocol {
  // Some free accounts have a separate 1,000 output-token/minute cap. Reserving 2,048 or
  // 4,096 completion tokens can fail even when the combined 8,000 TPM balance is unused.
  const val MAX_COMPLETION_TOKENS = 768

  @JvmStatic
  @Throws(JSONException::class)
  fun request(source: JSONObject): JSONObject {
    val messages = JSONArray()
    val instruction = source.optJSONObject("system_instruction")?.getJSONArray("parts")?.let { parts ->
      (0 until parts.length()).joinToString("\n") {
        parts.getJSONObject(it).getString("text")
      }
    }.orEmpty()
    messages.put(JSONObject().put("role", "system").put("content",
      "$instruction\nKeep the answer under 250 words. For long documents, summarize and clearly " +
        "say it is a summary. When JSON is requested, complete the entire JSON object."))
    val contents = source.getJSONArray("contents")
    for (i in 0 until contents.length()) {
      val turn = contents.getJSONObject(i)
      val parts = turn.getJSONArray("parts")
      val content = JSONArray()
      for (j in 0 until parts.length()) {
        val part = parts.getJSONObject(j)
        when {
          part.has("text") -> content.put(
            JSONObject().put("type", "text").put("text", part.getString("text"))
          )
          part.has("inlineData") -> {
            val image = part.getJSONObject("inlineData")
            val mime = image.getString("mimeType")
            val data = image.getString("data")
            if (mime !in setOf("image/jpeg", "image/png", "image/webp") || data.isBlank()) {
              throw JSONException("Invalid image")
            }
            content.put(JSONObject().put("type", "image_url").put(
              "image_url", JSONObject().put("url", "data:$mime;base64,$data")
            ))
          }
          else -> throw JSONException("Unsupported image description part")
        }
      }
      val role = if (turn.optString("role", "user") == "model") "assistant" else "user"
      messages.put(JSONObject().put("role", role).put("content", content))
    }
    val json = source.optJSONObject("generationConfig")?.optString("responseMimeType") ==
      "application/json"
    return JSONObject()
      .put("model", GroqSettings.MODEL)
      .put("messages", messages)
      // Instruct mode avoids paying for thinking tokens on routine visual descriptions.
      .put("reasoning_effort", "none")
      .put("reasoning_format", "hidden")
      .put("temperature", 0.7)
      .put("top_p", 0.8)
      .put("max_completion_tokens", MAX_COMPLETION_TOKENS)
      .put("stream", false)
      .apply { if (json) put("response_format", JSONObject().put("type", "json_object")) }
  }

  @JvmStatic
  @Throws(JSONException::class)
  fun response(source: JSONObject): DataFieldUtils.GeminiResponse {
    val choice = source.getJSONArray("choices").getJSONObject(0)
    val message = choice.getJSONObject("message")
    // Never treat a truncated JSON object or a reasoning-only response as a complete answer.
    if (choice.optString("finish_reason") != "stop" || message.isNull("content")) {
      throw JSONException("Groq did not complete the answer")
    }
    val text = message.getString("content").trim()
    if (text.isEmpty()) throw JSONException("Groq returned an empty answer")
    return DataFieldUtils.GeminiResponse.builder()
      .setText(text)
      .setFinishReason(DataFieldUtils.FINISH_REASON_STOP)
      .build()
  }
}
