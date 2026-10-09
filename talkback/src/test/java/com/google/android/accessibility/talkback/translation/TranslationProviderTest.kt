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
import java.net.URLDecoder
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TranslationProviderTest {
  @Test fun parsesAllSegmentsAndDetectionWithoutHtmlUnescaping() {
    val parsed = GoogleWebTranslationProvider.parse("""[[["Hola ","Hello"],["&amp; mundo 😀","world"]],null,"en"]""")
    assertEquals("Hola &amp; mundo 😀", parsed.text)
    assertEquals("en", parsed.detectedSource)
  }
  @Test fun postsTextInBodyNotUrl() {
    val text = "& + = / café 😀\nnext"
    val provider = GoogleWebTranslationProvider { url, body, _ ->
      assertTrue(url.startsWith("https://translate.googleapis.com/translate_a/single?"))
      assertTrue(url.contains("sl=auto&tl=es&dt=t"))
      assertFalse(url.contains("key="))
      assertFalse(url.contains("café"))
      assertEquals(text, URLDecoder.decode(body.removePrefix("q="), "UTF-8"))
      """[[["resultado",""]],null,"en"]"""
    }
    assertEquals("resultado", provider.translate(TranslationRequest(text, "auto", "es"), TranslationCancellation()).text)
  }
  @Test fun chunksPreserveWhitespaceAndSurrogatePairs() {
    val text = "😀 a\r\n".repeat(800)
    val chunks = GoogleWebTranslationProvider.chunks(text)
    assertEquals(text, chunks.joinToString(""))
    assertTrue(chunks.all { it.length <= 1_200 })
    assertTrue(chunks.all { !Character.isHighSurrogate(it.last()) })
    assertTrue(chunks.all { !Character.isLowSurrogate(it.first()) })
  }
  @Test fun chunksLongWordsWithoutTruncating() {
    val text = "𝄞".repeat(2_000)
    assertEquals(text, GoogleWebTranslationProvider.chunks(text, 101).joinToString(""))
  }
  @Test fun longTextUsesMultipleRequestsInOrder() {
    var count = 0
    val provider = GoogleWebTranslationProvider { _, body, _ ->
      count++
      val chunk = URLDecoder.decode(body.removePrefix("q="), "UTF-8")
      org.json.JSONArray().put(org.json.JSONArray().put(org.json.JSONArray().put(chunk))).toString()
    }
    val text = "long text. ".repeat(600)
    assertEquals(text, provider.translate(TranslationRequest(text, "en", "es"), TranslationCancellation()).text)
    assertTrue(count > 1)
  }
  @Test fun malformedResponsesFailInsteadOfSpeakingNullOrHtml() {
    for (body in listOf("[]", "[null]", "[[[]]]", "[[[null]]]", "[[[3]]]", "[[[\"\"]]]", "<html>captcha</html>")) {
      assertThrows(Exception::class.java) { GoogleWebTranslationProvider.parse(body) }
    }
  }
  @Test fun rejectsInvalidLanguageAndOversizedInputsBeforeTransport() {
    val provider = GoogleWebTranslationProvider { _, _, _ -> fail("No transport expected"); "" }
    assertThrows(IllegalArgumentException::class.java) {
      provider.translate(TranslationRequest("hello", "auto", "es&key=x"), TranslationCancellation())
    }
    assertThrows(IllegalArgumentException::class.java) {
      provider.translate(TranslationRequest("x".repeat(16_001), "auto", "es"), TranslationCancellation())
    }
  }
  @Test fun cancellationStopsBeforeNextChunk() {
    val cancellation = TranslationCancellation()
    var requests = 0
    val provider = GoogleWebTranslationProvider { _, _, _ ->
      requests++
      cancellation.cancel()
      """[[["result",""]]]"""
    }
    assertThrows(CancellationException::class.java) {
      provider.translate(TranslationRequest("x".repeat(2_000), "en", "es"), cancellation)
    }
    assertEquals(1, requests)
  }
  @Test fun failureIsNotRetried() {
    var requests = 0
    val provider = GoogleWebTranslationProvider { _, _, _ -> requests++; throw IOException("unavailable") }
    assertThrows(IOException::class.java) { provider.translate(TranslationRequest("hi", "en", "es"), TranslationCancellation()) }
    assertEquals(1, requests)
  }
  @Test fun preservesOuterWhitespaceEvenIfServerTrimsIt() {
    val provider = GoogleWebTranslationProvider { _, body, _ ->
      assertEquals("hello", URLDecoder.decode(body.removePrefix("q="), "UTF-8"))
      """[[["hola",""]]]"""
    }
    assertEquals(" \n hola\r\n", provider.translate(TranslationRequest(" \n hello\r\n", "en", "es"), TranslationCancellation()).text)
  }
}
