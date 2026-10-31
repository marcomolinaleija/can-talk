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
import androidx.test.core.app.ApplicationProvider
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatGptAuthManagerTest {

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs.edit().clear().commit()
  }

  @Test
  fun defaultProvider_isChatGpt() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    assertTrue(ChatGptAuthManager.isChatGptActive(prefs))
  }

  @Test
  fun setAiProvider_updatesActiveState() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    ChatGptAuthManager.setAiProvider(prefs, ChatGptAuthManager.PROVIDER_GEMINI)
    assertFalse(ChatGptAuthManager.isChatGptActive(prefs))

    ChatGptAuthManager.setAiProvider(prefs, ChatGptAuthManager.PROVIDER_CHATGPT)
    assertTrue(ChatGptAuthManager.isChatGptActive(prefs))
  }

  @Test
  fun modelSelection_defaultsToGpt4o() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    assertEquals(ChatGptAuthManager.MODEL_GPT_4O, ChatGptAuthManager.getModel(prefs))

    ChatGptAuthManager.setModel(prefs, ChatGptAuthManager.MODEL_GPT_4O_MINI)
    assertEquals(ChatGptAuthManager.MODEL_GPT_4O_MINI, ChatGptAuthManager.getModel(prefs))
  }

  @Test
  fun manualApiKey_managesReadyState() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    assertFalse(ChatGptAuthManager.isReady(context))

    ChatGptAuthManager.setManualApiKey(prefs, "sk-test123456789")
    assertTrue(ChatGptAuthManager.isReady(context))
    assertEquals("sk-test123456789", ChatGptAuthManager.getManualApiKey(prefs))
    assertEquals("sk-test123456789", ChatGptAuthManager.getValidAccessToken(context))
    assertTrue(ChatGptAuthManager.getUserEmail(prefs).contains("sk-test"))

    ChatGptAuthManager.setManualApiKey(prefs, "")
    assertFalse(ChatGptAuthManager.isReady(context))
  }

  @Test
  fun disconnect_clearsTokens() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs.edit()
      .putString(ChatGptAuthManager.PREF_CHATGPT_ACCESS_TOKEN, "sample_token")
      .putString(ChatGptAuthManager.PREF_CHATGPT_REFRESH_TOKEN, "sample_refresh")
      .putString(ChatGptAuthManager.PREF_CHATGPT_USER_EMAIL, "user@chatgpt.com")
      .commit()

    assertTrue(ChatGptAuthManager.isReady(context))
    assertEquals("user@chatgpt.com", ChatGptAuthManager.getUserEmail(prefs))

    ChatGptAuthManager.disconnect(context)
    assertFalse(ChatGptAuthManager.isReady(context))
    assertEquals("", ChatGptAuthManager.getUserEmail(prefs))
  }
}
