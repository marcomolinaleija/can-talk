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

import com.google.android.accessibility.talkback.directtouch.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatGptAuthManagerTest {

  private lateinit var prefs: FakeSharedPreferences

  @Before
  fun setUp() {
    prefs = FakeSharedPreferences()
  }

  @Test
  fun defaultProvider_isChatGpt() {
    assertTrue(ChatGptAuthManager.isChatGptActive(prefs))
  }

  @Test
  fun setAiProvider_updatesActiveState() {
    ChatGptAuthManager.setAiProvider(prefs, ChatGptAuthManager.PROVIDER_GEMINI)
    assertFalse(ChatGptAuthManager.isChatGptActive(prefs))

    ChatGptAuthManager.setAiProvider(prefs, ChatGptAuthManager.PROVIDER_CHATGPT)
    assertTrue(ChatGptAuthManager.isChatGptActive(prefs))
  }

  @Test
  fun modelSelection_defaultsToLuna() {
    assertEquals(ChatGptAuthManager.MODEL_GPT_6_LUNA, ChatGptAuthManager.getModel(prefs))

    ChatGptAuthManager.setModel(prefs, ChatGptAuthManager.MODEL_GPT_6_1_SOL)
    assertEquals(ChatGptAuthManager.MODEL_GPT_6_1_SOL, ChatGptAuthManager.getModel(prefs))
  }

  @Test
  fun manualApiKey_managesReadyState() {
    assertFalse(ChatGptAuthManager.isReady(prefs))

    ChatGptAuthManager.setManualApiKey(prefs, "sk-test123456789")
    assertTrue(ChatGptAuthManager.isReady(prefs))
    assertEquals("sk-test123456789", ChatGptAuthManager.getManualApiKey(prefs))
    assertEquals("sk-test123456789", ChatGptAuthManager.getValidAccessToken(prefs))
    assertTrue(ChatGptAuthManager.getUserEmail(prefs).contains("sk-test"))

    ChatGptAuthManager.setManualApiKey(prefs, "")
    assertFalse(ChatGptAuthManager.isReady(prefs))
  }

  @Test
  fun disconnect_clearsTokens() {
    prefs.edit()
      .putString(ChatGptAuthManager.PREF_CHATGPT_ACCESS_TOKEN, "sample_token")
      .putString(ChatGptAuthManager.PREF_CHATGPT_REFRESH_TOKEN, "sample_refresh")
      .putString(ChatGptAuthManager.PREF_CHATGPT_USER_EMAIL, "user@chatgpt.com")
      .commit()

    assertTrue(ChatGptAuthManager.isReady(prefs))
    assertEquals("user@chatgpt.com", ChatGptAuthManager.getUserEmail(prefs))

    ChatGptAuthManager.disconnect(prefs)
    assertFalse(ChatGptAuthManager.isReady(prefs))
    assertEquals("", ChatGptAuthManager.getUserEmail(prefs))
  }
}
