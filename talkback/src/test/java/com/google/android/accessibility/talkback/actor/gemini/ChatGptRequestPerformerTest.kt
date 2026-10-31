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

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatGptRequestPerformerTest {

  @Test
  fun extractContent_plainTextFromSseDeltas_doesNotThrowJsonException() {
    val sseAccumulatedText = "La imagen muestra un fondo negro uniforme con el logo de Spotify en verde brillante."
    val extracted = ChatGptRequestPerformer.extractContentFromResponse(sseAccumulatedText)
    assertEquals(sseAccumulatedText, extracted)
  }

  @Test
  fun extractContent_responsesApiTopLevelOutputText() {
    val json = """{"output_text": "Un gato sentado en un sillón rojo."}"""
    val extracted = ChatGptRequestPerformer.extractContentFromResponse(json)
    assertEquals("Un gato sentado en un sillón rojo.", extracted)
  }

  @Test
  fun extractContent_responsesApiOutputContentArray() {
    val json = """
      {
        "output": [
          {
            "content": [
              {
                "type": "output_text",
                "text": "Una taza de café caliente sobre la mesa."
              }
            ]
          }
        ]
      }
    """.trimIndent()
    val extracted = ChatGptRequestPerformer.extractContentFromResponse(json)
    assertEquals("Una taza de café caliente sobre la mesa.", extracted)
  }

  @Test
  fun extractContent_chatCompletionsApiMessageContent() {
    val json = """
      {
        "choices": [
          {
            "message": {
              "role": "assistant",
              "content": "Un hermoso atardecer en la playa con gaviotas volando."
            }
          }
        ]
      }
    """.trimIndent()
    val extracted = ChatGptRequestPerformer.extractContentFromResponse(json)
    assertEquals("Un hermoso atardecer en la playa con gaviotas volando.", extracted)
  }

  @Test
  fun extractContent_modelGeneratedStructuredJson_returnsRawJson() {
    val structuredJson = """{"screen_summary": "Pantalla de inicio", "elements": [{"text": "Buscar"}]}"""
    val extracted = ChatGptRequestPerformer.extractContentFromResponse(structuredJson)
    assertEquals(structuredJson, extracted)
  }

  @Test
  fun extractContent_emptyOrBlank_returnsEmpty() {
    assertEquals("", ChatGptRequestPerformer.extractContentFromResponse(""))
    assertEquals("", ChatGptRequestPerformer.extractContentFromResponse("   "))
  }
}
