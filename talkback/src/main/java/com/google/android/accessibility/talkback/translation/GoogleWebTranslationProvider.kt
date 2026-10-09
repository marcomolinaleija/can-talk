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

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import org.json.JSONArray

/** Experimental Google web JSON endpoint, not the billed Cloud Translation API. No mirrors. */
class GoogleWebTranslationProvider(
  private val transport: (String, String, TranslationCancellation) -> String = ::post,
) : TranslationProvider {
  override val id = "google-web-gtx-v1"
  override val sendsTextOffDevice = true

  override fun translate(request: TranslationRequest, cancellation: TranslationCancellation): TranslationResult {
    require(request.target.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z]{2,4})?")))
    require(request.source == "auto" || request.source.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z]{2,4})?")))
    require(request.text.isNotBlank() && request.text.length <= 16_000)
    val output = StringBuilder()
    var detected: String? = null
    for (chunk in chunks(request.text)) {
      cancellation.check()
      if (chunk.isBlank()) { output.append(chunk); continue }
      val content = chunk.trim()
      val leading = chunk.takeWhile { it.isWhitespace() }
      val trailing = chunk.takeLastWhile { it.isWhitespace() }
      val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
        "&sl=${request.source}&tl=${request.target}&dt=t"
      val result = parse(transport(url, "q=" + URLEncoder.encode(content, "UTF-8"), cancellation))
      output.append(leading).append(result.text.trim()).append(trailing)
      detected = detected ?: result.detectedSource
    }
    cancellation.check()
    return TranslationResult(output.toString(), detected)
  }

  companion object {
    /** Preserve all text and never split a surrogate pair. Prefer a nearby sentence boundary. */
    fun chunks(text: String, limit: Int = 1_200): List<String> {
      require(limit >= 2)
      val result = ArrayList<String>()
      var start = 0
      while (start < text.length) {
        var end = minOf(start + limit, text.length)
        if (end < text.length) {
          if (Character.isHighSurrogate(text[end - 1])) end--
          val boundary = (end - 1 downTo start + limit / 2).firstOrNull {
            text[it] == '\n' || text[it] == '.' || text[it] == ' '
          }
          if (boundary != null) end = boundary + 1
        }
        result.add(text.substring(start, end))
        start = end
      }
      return result
    }

    fun parse(body: String): TranslationResult {
      val data = JSONArray(body)
      val parts = data.optJSONArray(0) ?: throw IOException("Invalid translation response")
      val output = StringBuilder()
      for (i in 0 until parts.length()) {
        val part = parts.optJSONArray(i) ?: throw IOException("Invalid translation segment")
        if (part.isNull(0) || part.opt(0) !is String) throw IOException("Missing translation text")
        output.append(part.getString(0))
      }
      if (output.isBlank()) throw IOException("Empty translation response")
      return TranslationResult(output.toString(), data.opt(2) as? String)
    }

    private fun post(url: String, body: String, cancellation: TranslationCancellation): String {
      val connection = URI(url).toURL().openConnection() as HttpURLConnection
      cancellation.onCancel { connection.disconnect() }
      try {
        cancellation.check()
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (connection.responseCode != 200) throw IOException("Translation service unavailable")
        // Bounded response; no HTML/CAPTCHA parsing, redirect following, logging or retries.
        val bytes = connection.inputStream.use { stream ->
          val buffer = ByteArray(4_096)
          val output = java.io.ByteArrayOutputStream()
          while (true) {
            cancellation.check()
            val count = stream.read(buffer)
            if (count < 0) break
            if (output.size() + count > 256 * 1024) throw IOException("Translation response too large")
            output.write(buffer, 0, count)
          }
          output.toByteArray()
        }
        cancellation.check()
        return bytes.toString(Charsets.UTF_8)
      } finally {
        cancellation.onCancel(null)
        connection.disconnect()
      }
    }
  }
}
