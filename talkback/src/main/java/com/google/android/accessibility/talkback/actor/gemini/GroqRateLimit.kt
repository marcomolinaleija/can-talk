/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.actor.gemini

import com.android.volley.VolleyError
import java.util.Locale
import kotlin.math.ceil
import org.json.JSONObject

/** Only derived, non-private metadata; never retains Groq's body, organization ID or API key. */
data class GroqRateLimit(
  val retryAfterMillis: Long?,
  val window: Window,
  val remainingRequests: Long?,
  val remainingTokens: Long?,
  val requestTooLarge: Boolean = false,
  val requestedTokens: Long? = null,
  val tokenLimit: Long? = null,
  val tokenType: TokenType = TokenType.UNKNOWN,
) {
  enum class Window { TEMPORARY, DAILY, ACCOUNT, UNKNOWN }
  enum class TokenType { TOTAL, INPUT, OUTPUT, UNKNOWN }

  fun withWait(millis: Long): GroqRateLimit = copy(retryAfterMillis = millis.coerceAtLeast(1))

  companion object {
    @JvmStatic
    fun from(error: VolleyError): GroqRateLimit? {
      val response = error.networkResponse ?: return null
      if (response.statusCode != 429) return null
      val headers = response.headers.orEmpty().mapKeys { it.key.lowercase(Locale.ROOT) }
      fun count(name: String): Long? = headers[name]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
      // Groq documents this header in seconds. Fractional values must be rounded up.
      val headerWait = secondsMillis(headers["retry-after"]?.trim()?.toDoubleOrNull())
      val body = response.data?.toString(Charsets.UTF_8).orEmpty().take(8192)
      val details = try { JSONObject(body).optJSONObject("error") } catch (_: Exception) { null }
      val code = details?.optString("code").orEmpty().lowercase(Locale.ROOT)
      val type = details?.optString("type").orEmpty().lowercase(Locale.ROOT)
      val message = details?.optString("message").orEmpty().lowercase(Locale.ROOT)
      val remainingRequests = count("x-ratelimit-remaining-requests") // Requests per DAY.
      val tokenType = when {
        message.contains("output tokens per minute") || message.contains("(otpm)") -> TokenType.OUTPUT
        message.contains("input tokens per minute") || message.contains("(itpm)") -> TokenType.INPUT
        message.contains("tokens per minute") || message.contains("(tpm)") -> TokenType.TOTAL
        else -> TokenType.UNKNOWN
      }
      val tokenMinuteLimit = tokenType != TokenType.UNKNOWN
      val window = when {
        code == "insufficient_quota" || type == "insufficient_quota" ||
          code == "billing_hard_limit_reached" -> Window.ACCOUNT
        remainingRequests == 0L || listOf("requests per day", "tokens per day", "(rpd)", "(tpd)")
          .any(message::contains) -> Window.DAILY
        tokenMinuteLimit || listOf("requests per minute", "(rpm)")
          .any(message::contains) -> Window.TEMPORARY
        else -> Window.UNKNOWN
      }
      // Some 429 responses omit Retry-After. Only use the token reset for a TPM error:
      // it says nothing about when an RPM, daily or account limit will expire.
      val messageWait = Regex("try again in ([0-9]+(?:\\.[0-9]+)?(?:ms|s|m|h)(?:[0-9]+(?:\\.[0-9]+)?(?:ms|s|m|h))*)")
        .find(message)?.groupValues?.get(1)?.let(::durationMillis)
      val wait = headerWait ?: messageWait ?: if (window == Window.TEMPORARY && tokenType == TokenType.TOTAL) {
        durationMillis(headers["x-ratelimit-reset-tokens"])
      } else null
      fun numericField(name: String): Long? = Regex("\\b$name[: ]+([0-9]+)\\b")
        .find(message)?.groupValues?.get(1)?.toLongOrNull()
      val requested = if (tokenMinuteLimit) numericField("requested") else null
      val tokenLimit = if (tokenMinuteLimit) numericField("limit") else null
      val tooLarge = message.contains("request too large") ||
        (requested != null && tokenLimit != null && requested > tokenLimit)
      return GroqRateLimit(wait, window, remainingRequests, count("x-ratelimit-remaining-tokens"),
        tooLarge, requested, tokenLimit, tokenType)
    }

    private fun secondsMillis(seconds: Double?): Long? =
      seconds?.takeIf { it.isFinite() && it > 0 && it <= 7 * 24 * 60 * 60 }
        ?.let { ceil(it * 1000).toLong() }

    private fun durationMillis(value: String?): Long? {
      if (value == null || value.length > 80) return null
      val parts = Regex("([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h)").findAll(value).toList()
      if (parts.isEmpty() || parts.joinToString("") { it.value } != value) return null
      return secondsMillis(parts.sumOf {
        it.groupValues[1].toDouble() * when (it.groupValues[2]) {
          "ms" -> 0.001
          "m" -> 60.0
          "h" -> 3600.0
          else -> 1.0
        }
      })
    }
  }
}
