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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class TranslationEngineTest {
  @Test fun workerRejectionFallsBackWithoutThrowingOnCaller() {
    val workers = java.util.concurrent.Executors.newSingleThreadExecutor()
    workers.shutdownNow()
    val engine = TranslationEngine(Provider { TranslationResult("unused") }, workers = workers)
    try {
      var callbacks = 0
      engine.request("a", request, true) { assertNull(it); callbacks++ }
      assertEquals(1, callbacks)
      assertEquals(0, engine.cache.size())
    } finally { engine.close() }
  }

  @Test fun watchdogCancelsSlowProviderAndReturnsOriginalFallback() {
    val started = CountDownLatch(1)
    val cancelled = CountDownLatch(1)
    val done = CountDownLatch(1)
    val engine = TranslationEngine(Provider { token ->
      token.onCancel { cancelled.countDown() }
      started.countDown()
      cancelled.await(4, TimeUnit.SECONDS)
      token.check()
      TranslationResult("late")
    }, timeoutMillis = 1_000L)
    try {
      engine.request("a", request, true) { assertNull(it); done.countDown() }
      await(started)
      assertTrue(done.await(4, TimeUnit.SECONDS))
      assertEquals(0L, cancelled.count)
      assertEquals(0, engine.cache.size())
    } finally { engine.close() }
  }
  private val request = TranslationRequest("hello", "auto", "es")
  private class Provider(val translateAction: (TranslationCancellation) -> TranslationResult) : TranslationProvider {
    override val id = "fake"
    override val sendsTextOffDevice = false
    val calls = AtomicInteger()
    override fun translate(request: TranslationRequest, cancellation: TranslationCancellation): TranslationResult {
      calls.incrementAndGet()
      return translateAction(cancellation)
    }
  }
  private fun await(latch: CountDownLatch) = assertTrue(latch.await(3, TimeUnit.SECONDS))

  @Test fun coalescesRequestsAndHitsCache() {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val provider = Provider { started.countDown(); release.await(); TranslationResult("hola") }
    val engine = TranslationEngine(provider)
    try {
      val done = CountDownLatch(2)
      engine.request("app", request, true) { assertEquals("hola", it!!.text); done.countDown() }
      await(started)
      engine.request("app", request, true) { done.countDown() }
      release.countDown(); await(done)
      var cached: TranslationResult? = null
      engine.request("app", request, true) { cached=it }
      assertEquals("hola", cached!!.text)
      assertEquals(1, provider.calls.get())
    } finally { release.countDown(); engine.close() }
  }
  @Test fun cancellingOneSubscriberKeepsOtherRequestAlive() {
    val started = CountDownLatch(1); val release = CountDownLatch(1)
    val provider = Provider { started.countDown(); release.await(); TranslationResult("hola") }
    val engine = TranslationEngine(provider)
    try {
      var called = false
      val cancel = engine.request("a", request, true) { called=true }
      await(started)
      val done = CountDownLatch(1)
      engine.request("a", request, true) { done.countDown() }
      cancel.run(); release.countDown(); await(done)
      assertFalse(called)
      assertEquals(1, provider.calls.get())
    } finally { release.countDown(); engine.close() }
  }
  @Test fun cancelledLateResultDoesNotCallBackOrPopulateCache() {
    val started=CountDownLatch(1); val released=CountDownLatch(1); val cancelObserved=CountDownLatch(1)
    val provider = Provider { token ->
      token.onCancel { cancelObserved.countDown() }
      started.countDown()
      try { released.await() } catch (_: InterruptedException) {}
      TranslationResult("late")
    }
    val engine=TranslationEngine(provider)
    try {
      var called=false
      val cancel=engine.request("a", request, true) { called=true }
      await(started); cancel.run(); released.countDown(); await(cancelObserved)
      assertFalse(called); assertEquals(0, engine.cache.size())
    } finally { released.countDown(); engine.close() }
  }
  @Test fun clearDuringFlightPreventsRefillingButCompletesSpeech() {
    val started=CountDownLatch(1); val release=CountDownLatch(1); val done=CountDownLatch(1)
    val engine=TranslationEngine(Provider { started.countDown(); release.await(); TranslationResult("hola") })
    try {
      engine.request("a", request, true) { assertNotNull(it); done.countDown() }
      await(started); engine.cache.clear(); release.countDown(); await(done)
      assertEquals(0, engine.cache.size())
    } finally { release.countDown(); engine.close() }
  }
  @Test fun disabledCacheDoesNotStoreResult() {
    val provider=Provider { TranslationResult("hola") }; val engine=TranslationEngine(provider)
    try {
      repeat(2) { val done=CountDownLatch(1); engine.request("a",request,false) { done.countDown() }; await(done) }
      assertEquals(2,provider.calls.get()); assertEquals(0,engine.cache.size())
    } finally { engine.close() }
  }
  @Test fun failureFallsBackAndBacksOffWithoutCaching() {
    var clock=0L
    val provider=Provider { throw java.io.IOException() }; val engine=TranslationEngine(provider, clock={clock})
    try {
      val done=CountDownLatch(1); engine.request("a",request,true) { assertNull(it); done.countDown() }; await(done)
      engine.request("a",request,true) { assertNull(it) }
      assertEquals(1,provider.calls.get()); assertEquals(0,engine.cache.size())
      clock=15_000
      val again=CountDownLatch(1); engine.request("a",request,true) { again.countDown() }; await(again)
      assertEquals(2,provider.calls.get())
    } finally { engine.close() }
  }
  @Test fun closeCancelsAndRejectsNewWork() {
    val started=CountDownLatch(1); val observed=CountDownLatch(1)
    val provider=Provider { token -> token.onCancel { observed.countDown() }; started.countDown(); Thread.sleep(20_000); TranslationResult("late") }
    val engine=TranslationEngine(provider)
    engine.request("a",request,true) { fail("Cancelled work must not speak") }
    await(started); engine.close(); await(observed)
    var answered=false
    engine.request("a",request,true) { assertNull(it); answered=true }
    assertTrue(answered)
  }
}
