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

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.android.accessibility.talkback.R
import com.google.android.libraries.accessibility.utils.log.LogUtils

/**
 * Handles incoming OAuth 2.0 PKCE redirects for "Sign in with ChatGPT".
 *
 * Captures URI callbacks (e.g. can.cantalk://oauth/callback), extracts the authorization
 * code, coordinates token exchange with [ChatGptAuthManager], and provides accessible feedback.
 */
class ChatGptCallbackActivity : Activity() {

  private val mainHandler = Handler(Looper.getMainLooper())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handleIntent(intent)
  }

  override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    setIntent(intent)
    intent?.let { handleIntent(it) }
  }

  private fun handleIntent(intent: Intent) {
    val data = intent.data
    if (data == null) {
      LogUtils.w(TAG, "ChatGptCallbackActivity started with null data.")
      finish()
      return
    }

    LogUtils.v(TAG, "Received OAuth callback: %s", data)

    val error = data.getQueryParameter("error")
    if (!error.isNullOrEmpty()) {
      val errorDescription = data.getQueryParameter("error_description") ?: error
      LogUtils.e(TAG, "OAuth authorization error: %s - %s", error, errorDescription)
      showFeedback(getString(R.string.chatgpt_auth_error_prefix) + " " + errorDescription)
      finish()
      return
    }

    val code = data.getQueryParameter("code")
    val state = data.getQueryParameter("state")

    if (code.isNullOrEmpty() || state.isNullOrEmpty()) {
      LogUtils.e(TAG, "Missing code or state in OAuth callback parameters.")
      showFeedback(getString(R.string.chatgpt_auth_missing_parameters))
      finish()
      return
    }

    showFeedback(getString(R.string.chatgpt_auth_connecting))

    val callbackClientId = data.getQueryParameter("client_id")

    ChatGptAuthManager.exchangeCode(
      applicationContext,
      code,
      state,
      object : ChatGptAuthManager.AuthCallback {
        override fun onSuccess(email: String) {
          mainHandler.post {
            val message = getString(R.string.chatgpt_auth_success, email)
            showFeedback(message)
            finish()
          }
        }

        override fun onError(errorMessage: String) {
          mainHandler.post {
            showFeedback(errorMessage)
            finish()
          }
        }
      },
      callbackClientId
    )
  }

  private fun showFeedback(message: String) {
    Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
  }

  companion object {
    private const val TAG = "ChatGptCallbackActivity"
  }
}
