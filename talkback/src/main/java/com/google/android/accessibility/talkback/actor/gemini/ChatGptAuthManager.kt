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
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Manages OAuth 2.0 PKCE authentication and token lifecycle for "Sign in with ChatGPT".
 *
 * Supports Bring-Your-Own-Plan (BYOP) authorization against auth.openai.com,
 * token exchange, automatic token refresh, manual API key fallbacks, and model selection.
 */
object ChatGptAuthManager {

  private const val TAG = "ChatGptAuthManager"

  // OpenAI OIDC & OAuth endpoints
  const val AUTH_ENDPOINT = "https://auth.openai.com/api/accounts/authorize"
  const val TOKEN_ENDPOINT = "https://auth.openai.com/api/accounts/oauth/token"
  const val CHAT_COMPLETIONS_ENDPOINT = "https://api.openai.com/v1/chat/completions"

  // Standard redirect URI for CAN Talk OAuth callbacks
  const val REDIRECT_URI = "can.cantalk://oauth/callback"

  // Dynamic client ID for open source / agent tooling
  const val DEFAULT_CLIENT_ID = "dynamic_agent_client"

  // Scopes requested: identity + direct token usage from personal ChatGPT plan
  const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
  const val RESOURCE = "https://api.openai.com/v1"

  // Preference keys
  const val PREF_AI_PROVIDER = "pref_ai_provider"
  const val PROVIDER_CHATGPT = "chatgpt"
  const val PROVIDER_GEMINI = "gemini"
  const val PROVIDER_DEVICE = "device"

  const val PREF_CHATGPT_ACCESS_TOKEN = "pref_chatgpt_access_token"
  const val PREF_CHATGPT_REFRESH_TOKEN = "pref_chatgpt_refresh_token"
  const val PREF_CHATGPT_TOKEN_EXPIRES_AT = "pref_chatgpt_token_expires_at"
  const val PREF_CHATGPT_USER_EMAIL = "pref_chatgpt_user_email"
  const val PREF_CHATGPT_USER_NAME = "pref_chatgpt_user_name"
  const val PREF_CHATGPT_CLIENT_ID = "pref_chatgpt_client_id"
  const val PREF_CHATGPT_MANUAL_API_KEY = "pref_chatgpt_manual_api_key"
  const val PREF_CHATGPT_MODEL = "pref_chatgpt_model"
  const val PREF_CHATGPT_PKCE_VERIFIER = "pref_chatgpt_pkce_verifier"
  const val PREF_CHATGPT_PKCE_STATE = "pref_chatgpt_pkce_state"
  const val PREF_CHATGPT_HOST_ID = "pref_chatgpt_host_id"

  // Supported models
  const val MODEL_GPT_4O = "gpt-4o"
  const val MODEL_GPT_4O_MINI = "gpt-4o-mini"
  const val DEFAULT_MODEL = MODEL_GPT_4O

  private val secureRandom = SecureRandom()
  private val executor = Executors.newSingleThreadExecutor()

  /** Callback interface for token exchange operations. */
  interface AuthCallback {
    fun onSuccess(email: String)
    fun onError(errorMessage: String)
  }

  /** Returns whether ChatGPT is the currently selected active AI provider. */
  @JvmStatic
  fun isChatGptActive(prefs: SharedPreferences): Boolean {
    val provider = prefs.getString(PREF_AI_PROVIDER, PROVIDER_CHATGPT)
    return provider == PROVIDER_CHATGPT
  }

  /** Sets the active AI provider in preferences. */
  @JvmStatic
  fun setAiProvider(prefs: SharedPreferences, provider: String) {
    prefs.edit().putString(PREF_AI_PROVIDER, provider).apply()
  }

  /**
   * Returns whether ChatGPT has a valid session (OAuth tokens or manual API key).
   */
  @JvmStatic
  fun isReady(context: Context): Boolean {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val manualKey = prefs.getString(PREF_CHATGPT_MANUAL_API_KEY, null)?.trim()
    if (!manualKey.isNullOrEmpty()) {
      return true
    }
    val accessToken = prefs.getString(PREF_CHATGPT_ACCESS_TOKEN, null)
    val refreshToken = prefs.getString(PREF_CHATGPT_REFRESH_TOKEN, null)
    return !accessToken.isNullOrEmpty() || !refreshToken.isNullOrEmpty()
  }

  /** Returns the user-selected model or the default gpt-4o. */
  @JvmStatic
  fun getModel(prefs: SharedPreferences): String {
    return prefs.getString(PREF_CHATGPT_MODEL, DEFAULT_MODEL)?.trim().takeUnless { it.isNullOrEmpty() }
      ?: DEFAULT_MODEL
  }

  /** Saves the selected model in preferences. */
  @JvmStatic
  fun setModel(prefs: SharedPreferences, model: String) {
    prefs.edit().putString(PREF_CHATGPT_MODEL, model.trim()).apply()
  }

  /** Returns the logged-in email or description. */
  @JvmStatic
  fun getUserEmail(prefs: SharedPreferences): String {
    val manualKey = prefs.getString(PREF_CHATGPT_MANUAL_API_KEY, null)?.trim()
    if (!manualKey.isNullOrEmpty()) {
      return "Clave API manual (${manualKey.take(7)}...)"
    }
    return prefs.getString(PREF_CHATGPT_USER_EMAIL, "")?.trim() ?: ""
  }

  /** Sets a manual API key (e.g. sk-...) or clears it if blank. */
  @JvmStatic
  fun setManualApiKey(prefs: SharedPreferences, key: String) {
    val trimmed = key.trim()
    if (trimmed.isEmpty()) {
      prefs.edit().remove(PREF_CHATGPT_MANUAL_API_KEY).apply()
    } else {
      prefs.edit().putString(PREF_CHATGPT_MANUAL_API_KEY, trimmed).apply()
      setAiProvider(prefs, PROVIDER_CHATGPT)
    }
  }

  /** Returns the manual API key if present. */
  @JvmStatic
  fun getManualApiKey(prefs: SharedPreferences): String {
    return prefs.getString(PREF_CHATGPT_MANUAL_API_KEY, null)?.trim() ?: ""
  }

  /**
   * Starts the "Sign in with ChatGPT" OAuth PKCE authorization flow.
   * Generates a code verifier, challenge, state and opens the system browser.
   */
  @JvmStatic
  fun startAuthorization(context: Context) {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)

    // Generate PKCE code verifier (43-128 chars, URL-safe)
    val verifierBytes = ByteArray(32)
    secureRandom.nextBytes(verifierBytes)
    val codeVerifier = Base64.encodeToString(verifierBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).trim()

    // Generate SHA-256 code challenge
    val sha256 = MessageDigest.getInstance("SHA-256")
    val challengeBytes = sha256.digest(codeVerifier.toByteArray(StandardCharsets.US_ASCII))
    val codeChallenge = Base64.encodeToString(challengeBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).trim()

    // Generate random state to mitigate CSRF
    val stateBytes = ByteArray(16)
    secureRandom.nextBytes(stateBytes)
    val state = Base64.encodeToString(stateBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).trim()

    // Ensure persistent host ID for dynamic client registration
    var hostId = prefs.getString(PREF_CHATGPT_HOST_ID, null)
    if (hostId.isNullOrEmpty()) {
      hostId = UUID.randomUUID().toString()
      prefs.edit().putString(PREF_CHATGPT_HOST_ID, hostId).apply()
    }

    val clientId = prefs.getString(PREF_CHATGPT_CLIENT_ID, DEFAULT_CLIENT_ID) ?: DEFAULT_CLIENT_ID

    // Save verifier and state for validation in callback
    prefs.edit()
      .putString(PREF_CHATGPT_PKCE_VERIFIER, codeVerifier)
      .putString(PREF_CHATGPT_PKCE_STATE, state)
      .apply()

    val authUri = Uri.parse(AUTH_ENDPOINT).buildUpon()
      .appendQueryParameter("response_type", "code")
      .appendQueryParameter("client_id", clientId)
      .appendQueryParameter("redirect_uri", REDIRECT_URI)
      .appendQueryParameter("scope", SCOPES)
      .appendQueryParameter("resource", RESOURCE)
      .appendQueryParameter("code_challenge", codeChallenge)
      .appendQueryParameter("code_challenge_method", "S256")
      .appendQueryParameter("state", state)
      .appendQueryParameter("agent_name_hint", "CAN Talk")
      .appendQueryParameter("ext_agent_host_id", hostId)
      .build()

    LogUtils.v(TAG, "Starting OAuth authorization via: %s", authUri)
    val intent = Intent(Intent.ACTION_VIEW, authUri).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
  }

  /**
   * Exchanges authorization code for access and refresh tokens.
   */
  @JvmStatic
  fun exchangeCode(
    context: Context,
    code: String,
    receivedState: String,
    callback: AuthCallback
  ) {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val expectedState = prefs.getString(PREF_CHATGPT_PKCE_STATE, null)
    val codeVerifier = prefs.getString(PREF_CHATGPT_PKCE_VERIFIER, null)

    if (expectedState.isNullOrEmpty() || expectedState != receivedState) {
      LogUtils.e(TAG, "OAuth state mismatch or missing! Expected: %s, Received: %s", expectedState, receivedState)
      callback.onError("Validación de estado fallida (posible ataque CSRF).")
      return
    }

    if (codeVerifier.isNullOrEmpty()) {
      LogUtils.e(TAG, "Missing code verifier in SharedPreferences.")
      callback.onError("No se encontró el verificador PKCE.")
      return
    }

    val clientId = prefs.getString(PREF_CHATGPT_CLIENT_ID, DEFAULT_CLIENT_ID) ?: DEFAULT_CLIENT_ID

    executor.execute {
      try {
        val postParams = mapOf(
          "grant_type" to "authorization_code",
          "client_id" to clientId,
          "code" to code,
          "redirect_uri" to REDIRECT_URI,
          "code_verifier" to codeVerifier
        )

        val jsonResponse = executePostForm(TOKEN_ENDPOINT, postParams)
        if (jsonResponse == null) {
          callback.onError("No se recibió respuesta del servidor de autorización.")
          return@execute
        }

        if (jsonResponse.has("error")) {
          val errorDesc = jsonResponse.optString("error_description", jsonResponse.optString("error"))
          callback.onError("Error de OpenAI: $errorDesc")
          return@execute
        }

        val accessToken = jsonResponse.getString("access_token")
        val refreshToken = jsonResponse.optString("refresh_token", "")
        val expiresIn = jsonResponse.optLong("expires_in", 3600L)
        val idToken = jsonResponse.optString("id_token", "")
        val issuedClientId = jsonResponse.optString("client_id", "")

        val expiresAt = System.currentTimeMillis() + (expiresIn * 1000L)

        // Parse claims from ID token
        var email = ""
        var name = ""
        if (idToken.isNotEmpty()) {
          val claims = decodeJwtPayload(idToken)
          email = claims.optString("email", "")
          name = claims.optString("name", "")
        }
        if (email.isEmpty()) {
          email = "ChatGPT Plus/Pro"
        }

        val editor = prefs.edit()
          .putString(PREF_CHATGPT_ACCESS_TOKEN, accessToken)
          .putLong(PREF_CHATGPT_TOKEN_EXPIRES_AT, expiresAt)
          .putString(PREF_CHATGPT_USER_EMAIL, email)
          .putString(PREF_CHATGPT_USER_NAME, name)
          .putString(PREF_AI_PROVIDER, PROVIDER_CHATGPT)
          .remove(PREF_CHATGPT_PKCE_VERIFIER)
          .remove(PREF_CHATGPT_PKCE_STATE)

        if (refreshToken.isNotEmpty()) {
          editor.putString(PREF_CHATGPT_REFRESH_TOKEN, refreshToken)
        }
        if (issuedClientId.isNotEmpty() && issuedClientId != DEFAULT_CLIENT_ID) {
          editor.putString(PREF_CHATGPT_CLIENT_ID, issuedClientId)
        }
        editor.apply()

        LogUtils.i(TAG, "Successfully authenticated with ChatGPT as %s", email)
        callback.onSuccess(email)
      } catch (e: Exception) {
        LogUtils.e(TAG, "Error exchanging code: %s", e.message)
        callback.onError("Error al conectar: ${e.message}")
      }
    }
  }

  /**
   * Synchronously refreshes the access token using the stored refresh token.
   * Returns true if refresh succeeded.
   */
  @Synchronized
  fun refreshAccessToken(context: Context): Boolean {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val refreshToken = prefs.getString(PREF_CHATGPT_REFRESH_TOKEN, null)
    if (refreshToken.isNullOrEmpty()) {
      LogUtils.w(TAG, "No refresh token available to renew access.")
      return false
    }

    val clientId = prefs.getString(PREF_CHATGPT_CLIENT_ID, DEFAULT_CLIENT_ID) ?: DEFAULT_CLIENT_ID

    return try {
      val postParams = mapOf(
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
        "client_id" to clientId
      )

      val jsonResponse = executePostForm(TOKEN_ENDPOINT, postParams) ?: return false
      if (jsonResponse.has("error")) {
        LogUtils.e(TAG, "Refresh failed: %s", jsonResponse.optString("error_description"))
        return false
      }

      val newAccessToken = jsonResponse.getString("access_token")
      val newRefreshToken = jsonResponse.optString("refresh_token", "")
      val expiresIn = jsonResponse.optLong("expires_in", 3600L)
      val expiresAt = System.currentTimeMillis() + (expiresIn * 1000L)

      val editor = prefs.edit()
        .putString(PREF_CHATGPT_ACCESS_TOKEN, newAccessToken)
        .putLong(PREF_CHATGPT_TOKEN_EXPIRES_AT, expiresAt)

      if (newRefreshToken.isNotEmpty()) {
        editor.putString(PREF_CHATGPT_REFRESH_TOKEN, newRefreshToken)
      }
      editor.apply()

      LogUtils.i(TAG, "Access token refreshed successfully.")
      true
    } catch (e: Exception) {
      LogUtils.e(TAG, "Exception during token refresh: %s", e.message)
      false
    }
  }

  /**
   * Retrieves a valid access token or manual API key.
   * Automatically refreshes access token if expired or about to expire (< 5 minutes).
   */
  @JvmStatic
  fun getValidAccessToken(context: Context): String? {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val manualKey = prefs.getString(PREF_CHATGPT_MANUAL_API_KEY, null)?.trim()
    if (!manualKey.isNullOrEmpty()) {
      return manualKey
    }

    val accessToken = prefs.getString(PREF_CHATGPT_ACCESS_TOKEN, null)
    val expiresAt = prefs.getLong(PREF_CHATGPT_TOKEN_EXPIRES_AT, 0L)
    val now = System.currentTimeMillis()

    // If token is missing, not ready
    if (accessToken.isNullOrEmpty()) {
      return null
    }

    // If token expires in less than 5 minutes (300,000 ms), proactively refresh it
    if (expiresAt > 0 && (expiresAt - now) < 300_000L) {
      LogUtils.d(TAG, "Token close to expiring, attempting refresh...")
      if (refreshAccessToken(context)) {
        return prefs.getString(PREF_CHATGPT_ACCESS_TOKEN, null)
      }
    }

    return accessToken
  }

  /**
   * Disconnects the ChatGPT account and clears all associated tokens and preferences.
   */
  @JvmStatic
  fun disconnect(context: Context) {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs.edit()
      .remove(PREF_CHATGPT_ACCESS_TOKEN)
      .remove(PREF_CHATGPT_REFRESH_TOKEN)
      .remove(PREF_CHATGPT_TOKEN_EXPIRES_AT)
      .remove(PREF_CHATGPT_USER_EMAIL)
      .remove(PREF_CHATGPT_USER_NAME)
      .remove(PREF_CHATGPT_MANUAL_API_KEY)
      .remove(PREF_CHATGPT_PKCE_VERIFIER)
      .remove(PREF_CHATGPT_PKCE_STATE)
      .apply()
    LogUtils.i(TAG, "Disconnected ChatGPT account.")
  }

  /** Executes an HTTP POST with application/x-www-form-urlencoded data. */
  private fun executePostForm(endpointUrl: String, params: Map<String, String>): JSONObject? {
    val url = URL(endpointUrl)
    val connection = url.openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "POST"
      connection.doOutput = true
      connection.connectTimeout = 20_000
      connection.readTimeout = 20_000
      connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
      connection.setRequestProperty("Accept", "application/json")

      val postData = StringBuilder()
      for ((key, value) in params) {
        if (postData.isNotEmpty()) postData.append('&')
        postData.append(URLEncoder.encode(key, "UTF-8"))
        postData.append('=')
        postData.append(URLEncoder.encode(value, "UTF-8"))
      }

      OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
        writer.write(postData.toString())
        writer.flush()
      }

      val responseCode = connection.responseCode
      val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
      if (stream == null) return null

      val responseText = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
      return JSONObject(responseText)
    } finally {
      connection.disconnect()
    }
  }

  /** Decodes the payload portion of a JWT without external libraries. */
  private fun decodeJwtPayload(jwt: String): JSONObject {
    val parts = jwt.split('.')
    if (parts.size < 2) return JSONObject()
    return try {
      val payloadJson = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), StandardCharsets.UTF_8)
      JSONObject(payloadJson)
    } catch (e: Exception) {
      LogUtils.w(TAG, "Failed to decode JWT payload: %s", e.message)
      JSONObject()
    }
  }
}
