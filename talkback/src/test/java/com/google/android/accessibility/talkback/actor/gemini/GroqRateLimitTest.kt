/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.actor.gemini

import com.android.volley.ClientError
import com.android.volley.Header
import com.android.volley.NetworkResponse
import com.google.android.accessibility.talkback.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GroqRateLimitTest {
  private val context = RuntimeEnvironment.getApplication()
  private fun error(message: String = "Rate limit on tokens per minute (TPM); gsk-secret org-private",
    retry: String? = "2.125", requests: String = "12", status: Int = 429): ClientError {
    val headers = mutableListOf(Header("X-RateLimit-Remaining-Requests", requests),
      Header("x-ratelimit-remaining-tokens", "0"))
    if (retry != null) headers.add(Header("Retry-After", retry))
    val body = org.json.JSONObject().put("error", org.json.JSONObject()
      .put("type", "rate_limit_exceeded").put("message", message).put("status", "gsk-secret"))
    return ClientError(NetworkResponse(status, body.toString().toByteArray(), false, 0, headers))
  }

  @Test fun minuteLimitUsesServerWaitAndDoesNotExposePrivateErrorText() {
    val failure = GeminiFailure.fromGroqVolleyError(error())
    assertEquals(GroqRateLimit.Window.TEMPORARY, failure.groqLimit!!.window)
    assertEquals(2125L, failure.groqLimit!!.retryAfterMillis)
    assertEquals(12L, failure.groqLimit!!.remainingRequests)
    assertEquals(0L, failure.groqLimit!!.remainingTokens)
    assertEquals(context.getString(R.string.groq_error_rate_limit_wait, 3L), failure.userMessage(context))
    assertFalse(failure.logMessage.contains("gsk-secret"))
    assertFalse(failure.logMessage.contains("org-private"))
  }

  @Test fun remainingRequestHeaderIsDailyEvenIfRetryDelayIsShort() {
    val failure = GeminiFailure.fromGroqVolleyError(error(requests = "0"))
    assertEquals(GroqRateLimit.Window.DAILY, failure.groqLimit!!.window)
    assertEquals(context.getString(R.string.groq_error_daily_limit), failure.userMessage(context))
  }

  @Test fun dailyTokenLimitDoesNotUseMinuteTokenResetAsItsWait() {
    val failure = GeminiFailure.fromGroqVolleyError(error("Rate limit on tokens per day (TPD)", retry = null))
    assertEquals(GroqRateLimit.Window.DAILY, failure.groqLimit!!.window)
    assertNull(failure.groqLimit!!.retryAfterMillis)
  }

  @Test fun absentOrInvalidWaitNeverInventsARetryTime() {
    for (value in listOf(null, "invalid", "NaN", "Infinity", "-2", "0", "9999999999")) {
      val failure = GeminiFailure.fromGroqVolleyError(error(retry = value))
      assertNull(failure.groqLimit!!.retryAfterMillis)
      assertEquals(context.getString(R.string.groq_error_rate_limit), failure.userMessage(context))
    }
  }

  @Test fun unknownRateLimitHasAGenericLimitMessage() {
    val failure = GeminiFailure.fromGroqVolleyError(error("Please slow down", retry = null))
    assertEquals(GroqRateLimit.Window.UNKNOWN, failure.groqLimit!!.window)
    assertEquals(context.getString(R.string.groq_error_rate_limit), failure.userMessage(context))
  }

  @Test fun invalidCredentialsStayKeyErrorsAndDoNotUseRetryAfter() {
    val failure = GeminiFailure.fromGroqVolleyError(error(status = 401))
    assertEquals(GeminiFailure.Kind.API_KEY, failure.kind)
    assertNull(failure.groqLimit)
    assertFalse(failure.logMessage.contains("gsk-secret"))
  }

  @Test fun retryHintInMessageSupportsCombinedDurationsAndKeepsHeaderPriority() {
    val message = "Rate limit on tokens per minute (TPM). Please try again in 1m2.125s."
    assertEquals(62125L, GroqRateLimit.from(error(message, retry = null))!!.retryAfterMillis)
    assertEquals(2125L, GroqRateLimit.from(error(message))!!.retryAfterMillis)
    assertEquals(1L, GroqRateLimit.from(error("Please try again in 0.1ms.", retry = null))!!.retryAfterMillis)
  }

  @Test fun tokenResetIsUsedOnlyForAMinuteTokenLimit() {
    fun response(message: String) = ClientError(NetworkResponse(429,
      org.json.JSONObject().put("error", org.json.JSONObject().put("message", message))
        .toString().toByteArray(), false, 0, listOf(Header("X-RateLimit-Reset-Tokens", "2m1.5s"))))
    assertEquals(121500L, GroqRateLimit.from(response("tokens per minute (TPM)"))!!.retryAfterMillis)
    assertNull(GroqRateLimit.from(response("requests per minute (RPM)"))!!.retryAfterMillis)
    assertNull(GroqRateLimit.from(response("tokens per day (TPD)"))!!.retryAfterMillis)
    assertNull(GroqRateLimit.from(response("output tokens per minute (OTPM)"))!!.retryAfterMillis)
    assertNull(GroqRateLimit.from(response("input tokens per minute (ITPM)"))!!.retryAfterMillis)
  }

  @Test fun oversizedRequestOnlyRetainsTokenCountsAndGetsADistinctMessage() {
    val failure = GeminiFailure.fromGroqVolleyError(error(
      "Request too large in org-private on tokens per minute (TPM): Limit 8000, Requested 12500. gsk-secret",
      retry = null))
    assertTrue(failure.groqLimit!!.requestTooLarge)
    assertEquals(8000L, failure.groqLimit!!.tokenLimit)
    assertEquals(12500L, failure.groqLimit!!.requestedTokens)
    assertEquals(context.getString(R.string.groq_error_request_too_large), failure.userMessage(context))
    assertFalse(failure.logMessage.contains("org-private"))
    assertFalse(failure.logMessage.contains("gsk-secret"))
  }

  @Test fun separateOutputLimitIsNotTheCombinedTokenBalance() {
    val failure = GeminiFailure.fromGroqVolleyError(error(
      "Rate limit reached in org-private on output tokens per minute (OTPM): " +
        "Limit 1000, Used 300, Requested 2048. Please try again in 18.608s.", retry = null))
    assertEquals(GroqRateLimit.TokenType.OUTPUT, failure.groqLimit!!.tokenType)
    assertEquals(1000L, failure.groqLimit!!.tokenLimit)
    assertEquals(2048L, failure.groqLimit!!.requestedTokens)
    assertTrue(failure.groqLimit!!.requestTooLarge)
    assertEquals(18608L, failure.groqLimit!!.retryAfterMillis)
  }

  @Test fun usedAndRequestedMayExceedTheWindowWithoutAnOversizedRequest() {
    val failure = GeminiFailure.fromGroqVolleyError(error(
      "Rate limit on output tokens per minute (OTPM): Limit 1000, Used 600, Requested 768. " +
        "Please try again in 12s.", retry = null))
    assertEquals(GroqRateLimit.TokenType.OUTPUT, failure.groqLimit!!.tokenType)
    assertFalse(failure.groqLimit!!.requestTooLarge)
    assertEquals(12000L, failure.groqLimit!!.retryAfterMillis)
  }
}
