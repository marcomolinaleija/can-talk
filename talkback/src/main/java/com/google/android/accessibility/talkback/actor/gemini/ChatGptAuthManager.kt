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
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONException
import org.json.JSONObject

/**
 * Manages OAuth 2.0 PKCE authentication and token lifecycle for "Sign in with ChatGPT".
 *
 * Supports Bring-Your-Own-Plan (BYOP) authorization against auth.openai.com,
 * loopback redirect listener on localhost:1455, manual URL/code entry, automatic token refresh,
 * manual API key fallbacks, and model selection.
 */
object ChatGptAuthManager {

  private const val TAG = "ChatGptAuthManager"

  // OpenAI OIDC & OAuth endpoints
  const val AUTH_ENDPOINT = "https://auth.openai.com/api/accounts/authorize"
  const val TOKEN_ENDPOINT = "https://auth.openai.com/api/accounts/oauth/token"
  const val RESPONSES_ENDPOINT = "https://api.openai.com/v1/responses"
  const val CHAT_COMPLETIONS_ENDPOINT = "https://api.openai.com/v1/chat/completions"

  // OpenAI dynamic_agent_client strictly whitelists http://localhost:1455/auth/callback
  const val REDIRECT_URI = "http://localhost:1455/auth/callback"
  const val LOOPBACK_PORT = 1455

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
  const val MODEL_GPT_6_LUNA = "gpt-6-luna"
  const val MODEL_GPT_6_1_SOL = "gpt-6.1-sol"
  const val MODEL_GPT_6_ASTRA = "gpt-6-astra"
  const val MODEL_GPT_4O = "gpt-4o"
  const val MODEL_GPT_4O_MINI = "gpt-4o-mini"
  const val DEFAULT_MODEL = MODEL_GPT_6_LUNA

  private val secureRandom = SecureRandom()
  private val executor = Executors.newSingleThreadExecutor()
  private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

  @Volatile private var inFlightVerifier: String? = null
  @Volatile private var inFlightState: String? = null
  @Volatile private var serverSocket: ServerSocket? = null
  @Volatile private var loopbackThread: Thread? = null

  /** Callback interface for token exchange operations. */
  interface AuthCallback {
    fun onSuccess(email: String)
    fun onError(errorMessage: String)
  }

  /** Returns whether ChatGPT is the currently selected active AI provider. */
  @JvmStatic
  fun isChatGptActive(prefs: SharedPreferences): Boolean {
    val provider = prefs.getString(
      com.google.android.accessibility.talkback.actor.gemini.local.OnDeviceAiSettings.PREF_PROVIDER,
      prefs.getString(PREF_AI_PROVIDER, PROVIDER_CHATGPT)
    )
    return provider == PROVIDER_CHATGPT
  }

  /** Sets the active AI provider in preferences. */
  @JvmStatic
  fun setAiProvider(prefs: SharedPreferences, provider: String) {
    prefs.edit()
      .putString(com.google.android.accessibility.talkback.actor.gemini.local.OnDeviceAiSettings.PREF_PROVIDER, provider)
      .putString(PREF_AI_PROVIDER, provider)
      .apply()
  }

  /**
   * Returns whether ChatGPT has a valid session (OAuth tokens or manual API key).
   */
  @JvmStatic
  fun isReady(context: Context): Boolean =
    isReady(SharedPreferencesUtils.getSharedPreferences(context))

  @JvmStatic
  fun isReady(prefs: SharedPreferences): Boolean {
    val manualKey = prefs.getString(PREF_CHATGPT_MANUAL_API_KEY, null)?.trim()
    if (!manualKey.isNullOrEmpty()) {
      return true
    }
    val accessToken = prefs.getString(PREF_CHATGPT_ACCESS_TOKEN, null)
    val refreshToken = prefs.getString(PREF_CHATGPT_REFRESH_TOKEN, null)
    return !accessToken.isNullOrEmpty() || !refreshToken.isNullOrEmpty()
  }

  /** Returns the user-selected model or the default gpt-6.1-sol. */
  @JvmStatic
  fun getModel(prefs: SharedPreferences): String {
    val model =
      prefs.getString(PREF_CHATGPT_MODEL, DEFAULT_MODEL)?.trim().takeUnless { it.isNullOrEmpty() }
        ?: DEFAULT_MODEL
    // If the saved model was the deprecated gpt-4o or gpt-4o-mini on an OAuth session, migrate to default
    val hasManualKey = prefs.getString(PREF_CHATGPT_MANUAL_API_KEY, null)?.trim()?.isNotEmpty() == true
    if (!hasManualKey && (model == MODEL_GPT_4O || model == MODEL_GPT_4O_MINI)) {
      return DEFAULT_MODEL
    }
    return model
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
   * Starts the local loopback server on localhost:1455 and opens the browser.
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

    // Ensure persistent host ID for dynamic client registration (OpenAI requires urn:uuid:<uuid> URI format)
    var hostId = prefs.getString(PREF_CHATGPT_HOST_ID, null)
    if (hostId.isNullOrEmpty()) {
      hostId = "urn:uuid:" + UUID.randomUUID().toString()
      prefs.edit().putString(PREF_CHATGPT_HOST_ID, hostId).apply()
    } else if (!hostId.startsWith("urn:uuid:")) {
      hostId = "urn:uuid:$hostId"
      prefs.edit().putString(PREF_CHATGPT_HOST_ID, hostId).apply()
    }

    val clientId = prefs.getString(PREF_CHATGPT_CLIENT_ID, DEFAULT_CLIENT_ID) ?: DEFAULT_CLIENT_ID

    inFlightVerifier = codeVerifier
    inFlightState = state

    // Save verifier and state for validation
    prefs.edit()
      .putString(PREF_CHATGPT_PKCE_VERIFIER, codeVerifier)
      .putString(PREF_CHATGPT_PKCE_STATE, state)
      .apply()

    // Start local loopback listener on port 1455
    startLoopbackServer(context.applicationContext, state)

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

  private fun buildSuccessHtml(email: String): String {
    return """
      <!DOCTYPE html>
      <html lang="es">
      <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>CAN Talk</title></head>
      <body style="font-family:sans-serif; text-align:center; padding:40px; background:#f7f7f8; color:#202123;">
        <h1 style="color:#10a37f; font-size:28px;">¡Vinculación completada!</h1>
        <p style="font-size:18px;">Tu cuenta de ChatGPT (${email.ifEmpty { "Plus/Pro" }}) se conectó correctamente con CAN Talk.</p>
        <p style="color:#6e6e80; font-size:16px;">Ya podés cerrar esta pestaña del navegador y regresar al lector de pantalla.</p>
      </body>
      </html>
    """.trimIndent()
  }

  private fun buildErrorHtml(title: String, detail: String): String {
    return """
      <!DOCTYPE html>
      <html lang="es">
      <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>CAN Talk</title></head>
      <body style="font-family:sans-serif; text-align:center; padding:40px; background:#f7f7f8; color:#202123;">
        <h1 style="color:#ef4444; font-size:28px;">$title</h1>
        <p style="font-size:18px;">Ocurrió un problema al vincular tu cuenta con OpenAI:</p>
        <div style="color:#b91c1c; font-size:16px; background:#fee2e2; padding:14px; border-radius:8px; display:inline-block; max-width:90%; word-break:break-all; text-align:left;">$detail</div>
        <p style="color:#6e6e80; font-size:16px; margin-top:24px;">Regresa a CAN Talk para intentar nuevamente.</p>
      </body>
      </html>
    """.trimIndent()
  }

  private fun writeHttpResponse(socket: java.net.Socket, statusCode: Int, statusText: String, htmlBody: String) {
    val bodyBytes = htmlBody.toByteArray(StandardCharsets.UTF_8)
    val response = "HTTP/1.1 $statusCode $statusText\r\n" +
        "Content-Type: text/html; charset=UTF-8\r\n" +
        "Content-Length: ${bodyBytes.size}\r\n" +
        "Connection: close\r\n\r\n"

    OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8).use { writer ->
      writer.write(response)
      writer.write(htmlBody)
      writer.flush()
    }
  }

  /** Starts the loopback HTTP server to catch http://localhost:1455/auth/callback */
  private fun startLoopbackServer(appContext: Context, expectedState: String) {
    stopLoopbackServer()
    loopbackThread = Thread({
      try {
        val server = ServerSocket(LOOPBACK_PORT, 5, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        server.soTimeout = 180_000 // 3 minutes timeout

        LogUtils.i(TAG, "Loopback server listening on port %d...", LOOPBACK_PORT)
        val socket = server.accept()
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val firstLine = reader.readLine()
        LogUtils.v(TAG, "Loopback request: %s", firstLine)

        if (firstLine != null && firstLine.startsWith("GET /auth/callback")) {
          val parts = firstLine.split(" ")
          val requestPath = if (parts.size > 1) parts[1] else ""
          val uri = Uri.parse("http://localhost:$LOOPBACK_PORT$requestPath")
          val code = uri.getQueryParameter("code")?.trim()
          val state = uri.getQueryParameter("state")?.trim()
          val callbackClientId = uri.getQueryParameter("client_id")?.trim()
          val error = uri.getQueryParameter("error")?.trim()
          val errorDesc = uri.getQueryParameter("error_description")?.trim()

          LogUtils.e(TAG, "Callback received: code=%s, state=%s, callbackClientId=%s, error=%s",
            code?.take(8), state, callbackClientId, error)

          if (!error.isNullOrEmpty()) {
            val detail = errorDesc ?: error
            val html = buildErrorHtml("Error de autorización", detail)
            writeHttpResponse(socket, 400, "Bad Request", html)
            socket.close()
            mainHandler.post {
              Toast.makeText(appContext, "OpenAI: $detail", Toast.LENGTH_LONG).show()
            }
            return@Thread
          }

          if (code.isNullOrEmpty()) {
            val html = buildErrorHtml("Error de autorización", "No se recibió código de autorización.")
            writeHttpResponse(socket, 400, "Bad Request", html)
            socket.close()
            mainHandler.post {
              Toast.makeText(appContext, "Falta código de autorización.", Toast.LENGTH_LONG).show()
            }
            return@Thread
          }

          // Exchange tokens synchronously on background loopback thread before replying to browser
          val exchangeResult = exchangeCodeSync(appContext, code, state ?: "", callbackClientId)
          if (exchangeResult.isSuccess) {
            val email = exchangeResult.getOrThrow()
            val html = buildSuccessHtml(email)
            writeHttpResponse(socket, 200, "OK", html)
            socket.close()
            mainHandler.post {
              val msg = appContext.getString(R.string.chatgpt_auth_success, email)
              Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
            }
          } else {
            val errorMsg = exchangeResult.exceptionOrNull()?.message ?: "Error al intercambiar tokens con OpenAI."
            val html = buildErrorHtml("Error al intercambiar tokens", errorMsg)
            writeHttpResponse(socket, 400, "Bad Request", html)
            socket.close()
            mainHandler.post {
              Toast.makeText(appContext, errorMsg, Toast.LENGTH_LONG).show()
            }
          }
        }
      } catch (e: Exception) {
        LogUtils.d(TAG, "Loopback server finished or timeout: %s", e.message)
      } finally {
        stopLoopbackServer()
      }
    }, "chatgpt-loopback").apply {
      isDaemon = true
      start()
    }
  }

  fun stopLoopbackServer() {
    try {
      serverSocket?.close()
    } catch (_: Exception) {}
    serverSocket = null
    loopbackThread = null
  }

  /**
   * Parses and exchanges either a raw code, or a full redirect URL pasted by the user.
   */
  @JvmStatic
  fun exchangeCodeOrUrl(context: Context, input: String, callback: AuthCallback) {
    val trimmed = input.trim()
    var code = trimmed
    var state = ""
    var callbackClientId: String? = null

    if (trimmed.contains("code=") || trimmed.contains("client_id=")) {
      try {
        val uri = if (trimmed.startsWith("http")) Uri.parse(trimmed) else Uri.parse("http://localhost?$trimmed")
        code = uri.getQueryParameter("code") ?: code
        state = uri.getQueryParameter("state") ?: ""
        callbackClientId = uri.getQueryParameter("client_id")
      } catch (_: Exception) {}
    }

    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    if (state.isEmpty()) {
      state = prefs.getString(PREF_CHATGPT_PKCE_STATE, "") ?: inFlightState ?: ""
    }

    exchangeCode(context, code, state, callback, callbackClientId)
  }

  /**
   * Synchronously exchanges authorization code for access and refresh tokens.
   */
  @JvmStatic
  @Synchronized
  fun exchangeCodeSync(
    context: Context,
    code: String,
    receivedState: String,
    callbackClientId: String? = null
  ): Result<String> {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val expectedState = prefs.getString(PREF_CHATGPT_PKCE_STATE, null) ?: inFlightState
    val codeVerifier = prefs.getString(PREF_CHATGPT_PKCE_VERIFIER, null) ?: inFlightVerifier

    LogUtils.e(TAG, "exchangeCodeSync: code=%s, receivedState=%s, expectedState=%s, callbackClientId=%s",
      code.take(8) + "...", receivedState, expectedState, callbackClientId)

    if (expectedState != null && expectedState != receivedState && receivedState.isNotEmpty()) {
      val msg = "Validación de estado fallida (posible CSRF)."
      LogUtils.e(TAG, "OAuth state mismatch! Expected: %s, Received: %s", expectedState, receivedState)
      return Result.failure(IllegalStateException(msg))
    }

    if (codeVerifier.isNullOrEmpty()) {
      val msg = "No se encontró el verificador PKCE."
      LogUtils.e(TAG, "Missing code verifier in SharedPreferences and memory.")
      return Result.failure(IllegalStateException(msg))
    }

    // Dynamic registration provides client_id via the callback query parameter
    val clientId = callbackClientId?.takeUnless { it.isEmpty() }
      ?: prefs.getString(PREF_CHATGPT_CLIENT_ID, null)?.takeUnless { it.isEmpty() || it == DEFAULT_CLIENT_ID }
      ?: DEFAULT_CLIENT_ID

    LogUtils.e(TAG, "Exchanging code with client_id: %s", clientId)

    return try {
      val postParams = mapOf(
        "grant_type" to "authorization_code",
        "client_id" to clientId,
        "code" to code.trim(),
        "redirect_uri" to REDIRECT_URI,
        "code_verifier" to codeVerifier.trim(),
        "resource" to RESOURCE
      )

      val jsonResponse = executePostForm(TOKEN_ENDPOINT, postParams)
      if (jsonResponse == null) {
        val msg = "No se recibió respuesta del servidor de autorización."
        LogUtils.e(TAG, msg)
        return Result.failure(IllegalStateException(msg))
      }

      if (jsonResponse.has("error")) {
        val errorDesc = jsonResponse.optString("error_description", jsonResponse.optString("error"))
        val msg = "Error de OpenAI: $errorDesc"
        LogUtils.e(TAG, "Token endpoint error: %s (payload: %s)", msg, jsonResponse)
        return Result.failure(IllegalStateException(msg))
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

      val finalClientId = when {
        issuedClientId.isNotEmpty() && issuedClientId != DEFAULT_CLIENT_ID -> issuedClientId
        !callbackClientId.isNullOrEmpty() && callbackClientId != DEFAULT_CLIENT_ID -> callbackClientId
        else -> null
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
      if (finalClientId != null) {
        editor.putString(PREF_CHATGPT_CLIENT_ID, finalClientId)
      }
      editor.apply()

      inFlightVerifier = null
      inFlightState = null

      LogUtils.e(TAG, "Successfully authenticated with ChatGPT as %s, clientId=%s", email, finalClientId ?: clientId)
      Result.success(email)
    } catch (e: Exception) {
      LogUtils.e(TAG, "Exception exchanging code: %s", e.message)
      Result.failure(e)
    }
  }

  /**
   * Exchanges authorization code for access and refresh tokens.
   */
  @JvmStatic
  @JvmOverloads
  fun exchangeCode(
    context: Context,
    code: String,
    receivedState: String,
    callback: AuthCallback,
    callbackClientId: String? = null
  ) {
    executor.execute {
      val result = exchangeCodeSync(context, code, receivedState, callbackClientId)
      if (result.isSuccess) {
        callback.onSuccess(result.getOrThrow())
      } else {
        callback.onError(result.exceptionOrNull()?.message ?: "Error desconocido")
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
        "refresh_token" to refreshToken.trim(),
        "client_id" to clientId.trim(),
        "resource" to RESOURCE
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
   * Retrieves a valid access token or manual API key without checking network refresh.
   */
  @JvmStatic
  fun getValidAccessToken(prefs: SharedPreferences): String? {
    val manualKey = prefs.getString(PREF_CHATGPT_MANUAL_API_KEY, null)?.trim()
    if (!manualKey.isNullOrEmpty()) {
      return manualKey
    }
    return prefs.getString(PREF_CHATGPT_ACCESS_TOKEN, null)
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
    disconnect(SharedPreferencesUtils.getSharedPreferences(context))
  }

  @JvmStatic
  fun disconnect(prefs: SharedPreferences) {
    stopLoopbackServer()
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
      if (stream == null) {
        LogUtils.e(TAG, "POST %s returned HTTP %d with null stream", endpointUrl, responseCode)
        return null
      }

      val responseText = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
      LogUtils.e(TAG, "POST %s returned HTTP %d: %s", endpointUrl, responseCode, responseText)

      return try {
        JSONObject(responseText)
      } catch (e: JSONException) {
        LogUtils.e(TAG, "Non-JSON response (HTTP %d): %s", responseCode, responseText)
        JSONObject().apply {
          put("error", "HTTP $responseCode")
          put("error_description", responseText)
        }
      }
    } catch (e: Exception) {
      LogUtils.e(TAG, "executePostForm failed for %s: %s", endpointUrl, e.message)
      throw e
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
