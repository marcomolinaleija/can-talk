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

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONArray

/** Independent adapter for the experimental Google web protocol used by InstantTranslate. */
class GoogleInstantTranslationProvider(
  private val serviceKey: String,
  private val displayLanguage: String = Locale.getDefault().toLanguageTag(),
  private val transport: (String, TranslationCancellation) -> String = ::get,
) : TranslationProvider {
  override val id = "google-instant-web-v1"
  override val sendsTextOffDevice = true

  override fun translate(request: TranslationRequest, cancellation: TranslationCancellation): TranslationResult {
    require(request.target.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z]{2,4})?")))
    require(request.source == "auto" || request.source.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z]{2,4})?")))
    require(request.text.isNotBlank() && request.text.length <= maxInputCharacters)
    if (serviceKey.isBlank()) throw IOException("Translation provider is not configured")
    val output = StringBuilder()
    var detected: String? = null
    // Smaller chunks than the desktop addon bound encoded GET URLs on Android.
    for (chunk in GoogleWebTranslationProvider.chunks(request.text)) {
      cancellation.check()
      if (chunk.isBlank()) { output.append(chunk); continue }
      val parameters = listOf(
        "params.client" to "gtx",
        "query.source_language" to request.source,
        "query.target_language" to request.target,
        "query.display_language" to displayLanguage,
        "query.text" to chunk.trim(),
        "key" to serviceKey,
        "data_types" to "TRANSLATION",
        "data_types" to "SENTENCE_SPLITS",
      )
      val query = parameters.joinToString("&") { (name, value) ->
        URLEncoder.encode(name, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
      }
      val result = parse(transport("https://translate-pa.googleapis.com/v1/translate?$query", cancellation))
      output.append(chunk.takeWhile { it.isWhitespace() })
        .append(result.text.trim()).append(chunk.takeLastWhile { it.isWhitespace() })
      detected = detected ?: result.detectedSource
    }
    cancellation.check()
    return TranslationResult(output.toString(), detected)
  }

  companion object {
    fun parse(body: String): TranslationResult {
      val data = JSONArray(body)
      val sentences = data.optJSONArray(1)
      val output = StringBuilder()
      if (sentences != null && sentences.length() > 0) {
        for (i in 0 until sentences.length()) {
          val sentence = sentences.optJSONArray(i) ?: continue
          val translated = sentence.opt(0) as? String ?: continue
          output.append(translated)
        }
      } else {
        output.append(data.opt(0) as? String ?: throw IOException("Invalid translation response"))
      }
      if (output.isBlank()) throw IOException("Empty translation response")
      return TranslationResult(output.toString(), (data.opt(5) as? String)?.takeIf { it.isNotBlank() })
    }

    private fun get(url: String, cancellation: TranslationCancellation): String {
      val connection = URI(url).toURL().openConnection() as HttpURLConnection
      cancellation.onCancel { connection.disconnect() }
      try {
        cancellation.check()
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        connection.setRequestProperty("Content-Type", "application/json+protobuf")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (connection.responseCode != 200) throw IOException("Translation service unavailable")
        val bytes = connection.inputStream.use { stream ->
          val buffer = ByteArray(4_096)
          val output = ByteArrayOutputStream()
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
      } catch (_: IOException) {
        // Never propagate request URLs: they contain speech and the service credential.
        throw IOException("Translation service unavailable")
      } finally {
        cancellation.onCancel(null)
        connection.disconnect()
      }
    }
  }
}
