/*
 * Copyright 2026 CAN Talk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.actor.gemini

import android.content.Context
import android.util.Base64
import com.google.android.accessibility.talkback.actor.gemini.GeminiRestRequestPerformer.GeminiRestResponseCallback
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Handles vision and text requests directed to OpenAI / ChatGPT.
 *
 * Intercepts Gemini-style request objects from [GeminiRestEndpoint] and [ScreenOverviewRequester],
 * maps them to OpenAI Chat Completions multimodal payload format, and executes them with automatic
 * token refresh, resilient error handling, and JSON parsing.
 */
internal class ChatGptRequestPerformer(
  private val context: Context,
  private val callbackExecutor: Executor,
) {

  private class RequestJob(
    val prompt: String,
    val jpeg: ByteArray?,
    val isJson: Boolean,
    val model: String,
  ) {
    val cancelled = AtomicBoolean(false)
    var currentConnection: HttpURLConnection? = null
  }

  private val worker: ExecutorService =
    Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "chatgpt-vision-request").apply { isDaemon = true }
    }

  private val pending = AtomicReference<RequestJob?>(null)

  val hasPending: Boolean
    get() = pending.get() != null

  /** Cancels any in-flight request. */
  fun cancel() {
    pending.get()?.let { job ->
      job.cancelled.set(true)
      try {
        job.currentConnection?.disconnect()
      } catch (e: Exception) {
        LogUtils.w(TAG, "Error disconnecting request: %s", e.message)
      }
    }
  }

  /**
   * Performs an AI request using ChatGPT.
   */
  fun performRequest(postData: JSONObject, callback: GeminiRestResponseCallback) {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val model = ChatGptAuthManager.getModel(prefs)

    val job =
      try {
        parseGeminiPostData(postData, model)
      } catch (e: JSONException) {
        callbackExecutor.execute {
          callback.onFailure(GeminiFailure.other("Solicitud inválida: ${e.message}"))
        }
        return
      }

    cancel()
    pending.set(job)

    worker.execute {
      executeRequestWithRetry(job, callback, isRetry = false)
    }
  }

  private fun executeRequestWithRetry(
    job: RequestJob,
    callback: GeminiRestResponseCallback,
    isRetry: Boolean
  ) {
    if (job.cancelled.get()) {
      reportFinished(job) { callback.onCancelled() }
      return
    }

    val token = ChatGptAuthManager.getValidAccessToken(context)
    if (token.isNullOrEmpty()) {
      reportFinished(job) {
        callback.onFailure(
          GeminiFailure(
            GeminiFailure.Kind.API_KEY,
            statusCode = 401,
            status = "UNAUTHORIZED",
            message = "No hay cuenta de ChatGPT vinculada ni clave API configurada."
          )
        )
      }
      return
    }

    var connection: HttpURLConnection? = null
    try {
      val url = URL(ChatGptAuthManager.CHAT_COMPLETIONS_ENDPOINT)
      connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 30_000
        readTimeout = 45_000
        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Authorization", "Bearer $token")
      }
      job.currentConnection = connection

      val payload = buildOpenAiPayload(job)
      OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
        writer.write(payload.toString())
        writer.flush()
      }

      val statusCode = connection.responseCode
      if (job.cancelled.get()) {
        reportFinished(job) { callback.onCancelled() }
        return
      }

      // If unauthorized and we haven't retried yet, attempt token refresh and retry
      if (statusCode == 401 && !isRetry) {
        LogUtils.w(TAG, "Received HTTP 401 from OpenAI. Attempting token refresh...")
        if (ChatGptAuthManager.refreshAccessToken(context)) {
          executeRequestWithRetry(job, callback, isRetry = true)
          return
        }
      }

      val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
      val responseText =
        if (stream != null) {
          BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
        } else {
          ""
        }

      if (job.cancelled.get()) {
        reportFinished(job) { callback.onCancelled() }
        return
      }

      if (statusCode in 200..299) {
        val jsonResponse = JSONObject(responseText)
        val choices = jsonResponse.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
          reportFinished(job) {
            callback.onFailure(GeminiFailure.other("ChatGPT no devolvió respuestas."))
          }
          return
        }

        val messageObj = choices.getJSONObject(0).optJSONObject("message")
        val rawContent = messageObj?.optString("content", "") ?: ""
        val processedText =
          if (job.isJson) {
            LocalGemmaRunner.extractJson(rawContent)
          } else {
            rawContent.trim()
          }

        val geminiResponse =
          DataFieldUtils.GeminiResponse.builder()
            .setText(processedText)
            .setFinishReason(DataFieldUtils.FINISH_REASON_STOP)
            .build()

        reportFinished(job) { callback.onResponse(geminiResponse) }
      } else {
        val failure = mapHttpError(statusCode, responseText)
        reportFinished(job) { callback.onFailure(failure) }
      }
    } catch (e: Exception) {
      if (job.cancelled.get()) {
        reportFinished(job) { callback.onCancelled() }
      } else {
        LogUtils.e(TAG, "Request exception: %s", e.message)
        reportFinished(job) {
          callback.onFailure(GeminiFailure.other("Error de red con ChatGPT: ${e.message}"))
        }
      }
    } finally {
      try {
        connection?.disconnect()
      } catch (_: Exception) {
      }
    }
  }

  private fun reportFinished(job: RequestJob, action: Runnable) {
    pending.compareAndSet(job, null)
    callbackExecutor.execute(action)
  }

  private fun parseGeminiPostData(postData: JSONObject, model: String): RequestJob {
    val parts = postData.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
    var prompt = ""
    var jpeg: ByteArray? = null
    for (i in 0 until parts.length()) {
      val part = parts.getJSONObject(i)
      if (part.has("text")) {
        prompt = part.getString("text")
      } else if (part.has("inlineData")) {
        val base64Data = part.getJSONObject("inlineData").getString("data")
        jpeg = Base64.decode(base64Data, Base64.DEFAULT)
      }
    }
    val isJson =
      postData.optJSONObject("generationConfig")?.optString("responseMimeType") ==
        "application/json"
    return RequestJob(prompt, jpeg, isJson, model)
  }

  private fun buildOpenAiPayload(job: RequestJob): JSONObject {
    val payload = JSONObject()
    payload.put("model", job.model)

    val contentArray = JSONArray()

    val textPart = JSONObject()
    textPart.put("type", "text")
    textPart.put("text", job.prompt)
    contentArray.put(textPart)

    if (job.jpeg != null && job.jpeg.isNotEmpty()) {
      val base64Image = Base64.encodeToString(job.jpeg, Base64.NO_WRAP)
      val imagePart = JSONObject()
      imagePart.put("type", "image_url")
      val imageUrlObj = JSONObject()
      imageUrlObj.put("url", "data:image/jpeg;base64,$base64Image")
      imageUrlObj.put("detail", "high")
      imagePart.put("image_url", imageUrlObj)
      contentArray.put(imagePart)
    }

    val userMessage = JSONObject()
    userMessage.put("role", "user")
    userMessage.put("content", contentArray)

    val messagesArray = JSONArray()
    messagesArray.put(userMessage)

    payload.put("messages", messagesArray)
    payload.put("max_tokens", 1500)

    if (job.isJson) {
      val formatObj = JSONObject()
      formatObj.put("type", "json_object")
      payload.put("response_format", formatObj)
    }

    return payload
  }

  private fun mapHttpError(statusCode: Int, responseBody: String): GeminiFailure {
    var errorMsg = ""
    var errorType = ""
    try {
      if (responseBody.isNotEmpty()) {
        val json = JSONObject(responseBody)
        val errObj = json.optJSONObject("error")
        if (errObj != null) {
          errorMsg = errObj.optString("message", "")
          errorType = errObj.optString("type", "")
        }
      }
    } catch (_: Exception) {
    }

    val kind =
      when (statusCode) {
        401 -> GeminiFailure.Kind.API_KEY
        429 -> GeminiFailure.Kind.QUOTA
        500, 502, 503 -> GeminiFailure.Kind.BUSY
        504 -> GeminiFailure.Kind.TIMEOUT
        else -> GeminiFailure.Kind.OTHER
      }

    val displayMessage =
      when {
        statusCode == 429 -> "Límite de uso o cuota de ChatGPT alcanzado."
        statusCode == 401 -> "La sesión de ChatGPT caducó o la clave API es inválida."
        statusCode in 500..599 -> "Los servidores de ChatGPT están ocupados. Intenta en un momento."
        errorMsg.isNotEmpty() -> errorMsg
        else -> "Error HTTP $statusCode al comunicar con ChatGPT."
      }

    return GeminiFailure(
      kind = kind,
      statusCode = statusCode,
      status = errorType,
      message = displayMessage
    )
  }

  companion object {
    private const val TAG = "ChatGptRequestPerformer"
  }
}
