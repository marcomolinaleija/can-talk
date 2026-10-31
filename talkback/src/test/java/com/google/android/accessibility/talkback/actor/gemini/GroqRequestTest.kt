/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.actor.gemini

import com.android.volley.ClientError
import com.android.volley.Header
import com.android.volley.ExecutorDelivery
import com.android.volley.Response
import com.android.volley.TimeoutError
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline.FeedbackReturner
import com.google.android.accessibility.talkback.actor.gemini.ui.ImageQnAChatController
import com.google.android.accessibility.talkback.imagecaption.ImageCaptionUtils.CaptionType
import com.google.android.accessibility.talkback.imagecaption.Result as CaptionResult
import com.android.volley.Network
import com.android.volley.NetworkResponse
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.NoCache
import com.google.android.accessibility.talkback.actor.gemini.DataFieldUtils.GeminiResponse
import com.google.android.accessibility.talkback.actor.gemini.GeminiRestRequestPerformer.GeminiRestResponseCallback
import com.google.android.accessibility.talkback.actor.gemini.local.OnDeviceAiSettings
import com.google.android.accessibility.talkback.directtouch.FakeSharedPreferences
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.RuntimeEnvironment
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GroqRequestTest {
  private class Queue : RequestQueue(NoCache(), Network { throw AssertionError("No network in tests") }) {
    val requests = mutableListOf<Request<*>>()
    override fun <T : Any?> add(request: Request<T>): Request<T> {
      requests.add(request)
      return request
    }
  }
  private class Callback : GeminiRestResponseCallback {
    var failure: GeminiFailure? = null
    var cancelled = 0
    var response: GeminiResponse? = null
    override fun onResponse(response: GeminiResponse) { this.response = response }
    override fun onFailure(failure: GeminiFailure) { this.failure = failure }
    override fun onCancelled() { cancelled++ }
  }
  private val prefs = FakeSharedPreferences()
  private val queue = Queue()
  private val performer = GeminiRestRequestPerformer(queue, prefs)
  private val callback = Callback()
  private val source = JSONObject("""{"contents":[{"parts":[{"text":"Describe"},{"inlineData":{"mimeType":"image/jpeg","data":"aW1n"}}]}]}""")
  private val googleUrl = "https://generativelanguage.googleapis.com/v1beta/models/test:generateContent"

  private fun selectGroq() {
    prefs.edit().putString(OnDeviceAiSettings.PREF_PROVIDER, GroqSettings.PROVIDER).apply()
  }

  private fun start() {
    selectGroq()
    GroqSettings.setUserKey(prefs, "key")
    performer.performRequest(googleUrl, "google-key", source, callback)
  }
  private fun tick(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
  private fun rateError(wait: String = "2", daily: Boolean = false) = ClientError(NetworkResponse(
    429, """{"error":{"message":"Rate limit on tokens per ${if (daily) "day (TPD)" else "minute (TPM)"}"}}""".toByteArray(),
    false, 0, listOf(Header("Retry-After", wait))))

  @Test fun onlyGroqKeyIsSentToGroqWithImageBodyAndNoDiskCache() {
    selectGroq()
    GroqSettings.setUserKey(prefs, " groq-key ")
    performer.performRequest(googleUrl, "google-secret", source, callback)
    val request = queue.requests.single()
    assertEquals(GroqSettings.URL, request.url)
    assertEquals(mapOf("Authorization" to "Bearer groq-key"), request.headers)
    val body = String(request.body, Charsets.UTF_8)
    assertTrue(body.contains("image_url"))
    assertFalse(body.contains("google-secret"))
    assertFalse(request.shouldCache())
    assertTrue(performer.hasPendingTransaction())
    assertTrue(performer.isSupported(""))
  }

  @Test fun geminiRemainsDefaultAndNeverReceivesSavedGroqKey() {
    GroqSettings.setUserKey(prefs, "groq-secret")
    performer.performRequest(googleUrl, "google-key", source, callback)
    val request = queue.requests.single()
    assertEquals(googleUrl, request.url)
    assertEquals(mapOf("x-goog-api-key" to "google-key"), request.headers)
    assertEquals(source.toString(), String(request.body, Charsets.UTF_8))
    assertFalse(GroqSettings.isSelected(prefs))
  }

  @Test fun missingGroqKeyNeverFallsBackToGemini() {
    selectGroq()
    performer.performRequest(googleUrl, "google-key", source, callback)
    assertTrue(queue.requests.isEmpty())
    assertEquals(GeminiFailure.Kind.API_KEY, callback.failure?.kind)
    assertTrue(callback.failure!!.logMessage.startsWith("Groq"))
  }

  @Test fun cancellingRequestClearsPendingAndNotifiesOnce() {
    selectGroq()
    GroqSettings.setUserKey(prefs, "key")
    performer.performRequest(googleUrl, "", source, callback)
    performer.cancelExistingRequestIfNeeded()
    performer.cancelExistingRequestIfNeeded()
    assertTrue(queue.requests.single().isCanceled)
    assertFalse(performer.hasPendingTransaction())
    assertEquals(1, callback.cancelled)
  }

  @Test fun lateFailureFromCancelledRequestDoesNotClearNewRequest() {
    selectGroq()
    GroqSettings.setUserKey(prefs, "key")
    performer.performRequest(googleUrl, "", source, callback)
    val nextCallback = Callback()
    performer.performRequest(googleUrl, "", source, nextCallback)
    queue.requests.first().deliverError(ClientError(NetworkResponse(429, ByteArray(0), false, 0, emptyList())))
    assertTrue(performer.hasPendingTransaction())
    assertNull(callback.failure)
    assertNull(nextCallback.failure)
    assertEquals(1, callback.cancelled)
  }

  @Test fun quotaErrorUsesGroqAndDoesNotExposeResponseSecrets() {
    selectGroq()
    GroqSettings.setUserKey(prefs, "key")
    performer.performRequest(googleUrl, "", source, callback)
    val error = """{"error":{"message":"Secret gsk-example","type":"rate_limit_exceeded"}}""".toByteArray()
    repeat(3) { attempt ->
      queue.requests.last().deliverError(ClientError(NetworkResponse(429, error, false, 0, emptyList())))
      if (attempt < 2) tick(2000L shl attempt)
    }
    assertEquals(GeminiFailure.Kind.QUOTA, callback.failure?.kind)
    assertFalse(callback.failure!!.logMessage.contains("gsk-example"))
    assertFalse(performer.hasPendingTransaction())
  }

  @Test fun shortLimitWaitsThenRetriesOnceAndReturnsTheAnswer() {
    start()
    tick(1000)
    queue.requests.single().deliverError(rateError())
    assertTrue(performer.hasPendingTransaction())
    assertNull(callback.failure)
    tick(1999)
    assertEquals(1, queue.requests.size)
    tick(1)
    assertEquals(2, queue.requests.size)
    assertEquals(queue.requests.first().headers, queue.requests.last().headers)
    assertArrayEquals(queue.requests.first().body, queue.requests.last().body)
    assertEquals(27000, queue.requests.last().timeoutMs)
    @Suppress("UNCHECKED_CAST")
    val retry = queue.requests.last() as Request<JSONObject>
    ExecutorDelivery(Handler(Looper.getMainLooper())).postResponse(retry, Response.success(
      JSONObject("""{"choices":[{"finish_reason":"stop","message":{"content":"Done"}}]}"""), null))
    tick(0)
    assertEquals("Done", callback.response!!.text())
    assertFalse(performer.hasPendingTransaction())
  }

  @Test fun aThird429DoesNotStartARetryLoop() {
    start()
    queue.requests.single().deliverError(rateError("1"))
    tick(1000)
    queue.requests.last().deliverError(rateError("1"))
    tick(1000)
    queue.requests.last().deliverError(rateError("1"))
    tick(5000)
    assertEquals(3, queue.requests.size)
    assertNotNull(callback.failure)
    assertFalse(performer.hasPendingTransaction())
  }

  @Test fun cancellationAlsoStopsADelayedRetry() {
    start()
    queue.requests.single().deliverError(rateError())
    performer.cancelExistingRequestIfNeeded()
    tick(5000)
    assertEquals(1, queue.requests.size)
    assertEquals(1, callback.cancelled)
    assertNull(callback.failure)
    assertFalse(performer.hasPendingTransaction())
  }

  @Test fun providerChangeDuringWaitStopsTheRetry() {
    start()
    queue.requests.single().deliverError(rateError())
    prefs.edit().putString(OnDeviceAiSettings.PREF_PROVIDER, OnDeviceAiSettings.PROVIDER_CLOUD).apply()
    tick(2000)
    assertEquals(1, queue.requests.size)
    assertEquals(1, callback.cancelled)
  }

  @Test fun keyChangeDuringWaitStopsTheRetry() {
    start()
    queue.requests.single().deliverError(rateError())
    GroqSettings.setUserKey(prefs, "different-key")
    tick(2000)
    assertEquals(1, queue.requests.size)
    assertEquals(1, callback.cancelled)
  }

  @Test fun longCooldownPreventsManualResendsUntilTheServerTime() {
    start()
    queue.requests.single().deliverError(rateError("25"))
    assertNotNull(callback.failure)
    val next = Callback()
    performer.performRequest(googleUrl, "", source, next)
    assertNull(next.failure) // Deferred so callers can start and then stop their progress correctly.
    tick(0)
    assertNotNull(next.failure)
    assertEquals(1, queue.requests.size)
    tick(25000)
    performer.performRequest(googleUrl, "", source, Callback())
    assertEquals(2, queue.requests.size)
  }

  @Test fun dailyLimitNeverRetriesEvenWithAShortRetryHeader() {
    start()
    queue.requests.single().deliverError(rateError(daily = true))
    tick(5000)
    assertEquals(1, queue.requests.size)
    assertEquals(GroqRateLimit.Window.DAILY, callback.failure!!.groqLimit!!.window)
  }

  @Test fun anExpiredBudgetNeverSchedulesAnotherHttpRequest() {
    start()
    tick(28000)
    queue.requests.single().deliverError(rateError())
    tick(5000)
    assertEquals(1, queue.requests.size)
    assertNotNull(callback.failure)
  }

  @Test fun aTimeoutIsNotRetried() {
    start()
    queue.requests.single().deliverError(TimeoutError())
    tick(10000)
    assertEquals(1, queue.requests.size)
    assertEquals(GeminiFailure.Kind.TIMEOUT, callback.failure!!.kind)
  }

  @Test fun geminiDoesNotUseGroqRetryHandling() {
    performer.performRequest(googleUrl, "google-key", source, callback)
    queue.requests.single().deliverError(rateError())
    tick(10000)
    assertEquals(1, queue.requests.size)
    assertNotNull(callback.failure)
    assertNull(callback.failure!!.groqLimit)
  }

  @Test fun cooldownExpiresWhileThePhoneSleeps() {
    start()
    queue.requests.single().deliverError(rateError("25"))
    ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(26))
    performer.performRequest(googleUrl, "", source, Callback())
    assertEquals(2, queue.requests.size)
  }

  @Test fun aRetryDoesNotSendAnOldScreenshotAfterThePhoneSleptPastTheDeadline() {
    start()
    queue.requests.single().deliverError(rateError())
    ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(31))
    tick(2000)
    assertEquals(1, queue.requests.size)
    assertEquals(GeminiFailure.Kind.TIMEOUT, callback.failure!!.kind)
    assertFalse(performer.hasPendingTransaction())
  }

  @Test fun aDifferentKeyDoesNotInheritThePreviousKeysCooldown() {
    start()
    queue.requests.single().deliverError(rateError("25"))
    GroqSettings.setUserKey(prefs, "new-key")
    performer.performRequest(googleUrl, "", source, Callback())
    assertEquals(2, queue.requests.size)
    assertEquals("Bearer new-key", queue.requests.last().headers["Authorization"])
  }

  @Test fun missingHeaderUsesTwoThenFourSecondsAndStopsAfterTwoRetries() {
    start()
    val error = ClientError(NetworkResponse(429, ByteArray(0), false, 0, emptyList()))
    queue.requests.last().deliverError(error)
    assertTrue(performer.hasPendingTransaction())
    tick(1999)
    assertEquals(1, queue.requests.size)
    tick(1)
    assertEquals(2, queue.requests.size)
    queue.requests.last().deliverError(error)
    tick(3999)
    assertEquals(2, queue.requests.size)
    tick(1)
    assertEquals(3, queue.requests.size)
    assertEquals(24000, queue.requests.last().timeoutMs)
    queue.requests.last().deliverError(error)
    tick(10000)
    assertEquals(3, queue.requests.size)
    assertNotNull(callback.failure)
    assertFalse(performer.hasPendingTransaction())
  }

  @Test fun requestTooLargeIsExplainedWithoutRepeatingTheSamePayload() {
    start()
    val body = """{"error":{"message":"Request too large for model in org-private on tokens per minute (TPM): Limit 8000, Requested 12000. Reduce your message size."}}"""
    queue.requests.single().deliverError(ClientError(NetworkResponse(
      429, body.toByteArray(), false, 0, listOf(Header("Retry-After", "1")))))
    tick(10000)
    assertEquals(1, queue.requests.size)
    assertTrue(callback.failure!!.groqLimit!!.requestTooLarge)
    assertFalse(callback.failure!!.logMessage.contains("org-private"))
  }

  @Test fun messageHintIsRespectedWhenRetryHeaderIsAbsent() {
    start()
    val body = """{"error":{"message":"Rate limit on tokens per minute (TPM). Please try again in 3.5s."}}"""
    queue.requests.single().deliverError(ClientError(NetworkResponse(
      429, body.toByteArray(), false, 0, emptyList())))
    tick(3499)
    assertEquals(1, queue.requests.size)
    tick(1)
    assertEquals(2, queue.requests.size)
  }

  @Test fun dailyAndAccountErrorsWithoutAHeaderNeverUseTheDefaultBackoff() {
    for (message in listOf("tokens per day (TPD)", "insufficient_quota")) {
      start()
      val body = """{"error":{"message":"$message","code":"$message"}}"""
      val count = queue.requests.size
      queue.requests.last().deliverError(ClientError(NetworkResponse(
        429, body.toByteArray(), false, 0, emptyList())))
      tick(10000)
      assertEquals(count, queue.requests.size)
      assertNotNull(callback.failure)
    }
  }

  @Test fun aLongerServerWaitFitsWithinTheOriginalThirtySecondBudget() {
    start()
    queue.requests.single().deliverError(rateError("18.608"))
    tick(18607)
    assertEquals(1, queue.requests.size)
    tick(1)
    assertEquals(2, queue.requests.size)
    assertEquals(11392, queue.requests.last().timeoutMs)
    assertNull(callback.failure)
    assertTrue(performer.hasPendingTransaction())
  }

  @Test fun imageAndScreenFollowupsKeepTheOriginalImageAndConversationOnGroq() {
    selectGroq()
    GroqSettings.setUserKey(prefs, "groq-key")
    val image = byteArrayOf(1, 2, 3)
    for (action in listOf(GeminiActor.Action.IMAGE_QNA, GeminiActor.Action.SCREEN_QNA)) {
      var feedback: Feedback? = null
      val controller = ImageQnAChatController(RuntimeEnvironment.getApplication(),
        FeedbackReturner { feedback = it; true }, action,
        listOf(CaptionResult.create(CaptionType.IMAGE_DESCRIPTION, "A blue bicycle.")))
      controller.onNewCommand("What color is it?", image, false)
      controller.onImageQnaResponse("It is blue.")
      controller.onNewCommand("And how many wheels?", image, true)
      val command = feedback!!.failovers().first().geminiRequest()!!
      assertArrayEquals(image, command.imageBytes())
      assertEquals(if (action == GeminiActor.Action.IMAGE_QNA)
        Feedback.GeminiRequest.Action.REQUEST_IMAGE_QNA else Feedback.GeminiRequest.Action.REQUEST_SCREEN_QNA,
        command.action())
      performer.performRequest(googleUrl, "google-secret", DataFieldUtils.createPostDataJson(
        command.text(), Base64.encodeToString(command.imageBytes(), Base64.NO_WRAP), JSONArray()), callback)
      val request = queue.requests.last()
      assertEquals(GroqSettings.URL, request.url)
      assertEquals("Bearer groq-key", request.headers["Authorization"])
      val parts = JSONObject(String(request.body)).getJSONArray("messages")
        .getJSONObject(1).getJSONArray("content")
      val prompt = parts.getJSONObject(0).getString("text")
      for (text in listOf("A blue bicycle.", "What color is it?", "It is blue.", "And how many wheels?")) {
        assertTrue(prompt.contains(text))
      }
      assertEquals("data:image/jpeg;base64,AQID", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
      assertFalse(String(request.body).contains("google-secret"))
    }
  }
}
