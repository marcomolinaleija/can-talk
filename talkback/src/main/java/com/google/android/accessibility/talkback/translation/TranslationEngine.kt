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

import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Future

/** Bounded asynchronous execution, deduplication and caching, independent of Android/TTS. */
class TranslationEngine(
  val provider: TranslationProvider,
  val cache: TranslationCache = TranslationCache(),
  private val workers: ExecutorService = java.util.concurrent.ThreadPoolExecutor(
    4, 4, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue(16)),
  private val timers: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
  private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
  private val timeoutMillis: Long = 10_000L,
) {
  private class Job(val generation: Long) {
    val cancellation = TranslationCancellation()
    val callbacks = LinkedHashSet<(TranslationResult?) -> Unit>()
    var future: Future<*>? = null
    var timeout: Future<*>? = null
  }
  private val jobs = HashMap<TranslationCache.Key, Job>()
  private var closed = false
  private var unavailableUntil = 0L

  @Synchronized fun request(
    scope: String, request: TranslationRequest, useCache: Boolean,
    callback: (TranslationResult?) -> Unit,
  ): Runnable {
    val key = TranslationCache.Key(provider.id, scope, request)
    if (closed) { callback(null); return Runnable {} }
    if (useCache) cache.get(key)?.let { callback(it); return Runnable {} }
    if (clock() < unavailableUntil) { callback(null); return Runnable {} }
    val job = jobs[key]
    if (job != null) {
      job.callbacks.add(callback)
      return cancellation(key, job, callback)
    }
    if (jobs.size >= 16) { callback(null); return Runnable {} }
    val created = Job(cache.generation)
    created.callbacks.add(callback)
    jobs[key] = created
    created.timeout = timers.schedule({ finish(key, created, null, useCache) }, timeoutMillis,
      java.util.concurrent.TimeUnit.MILLISECONDS)
    try {
      created.future = workers.submit {
        val result = try {
          val initialRequest = if (request.source != "auto" && !request.swapTarget.isNullOrBlank()) {
            request.copy(source = "auto")
          } else {
            request
          }
          val initial = provider.translate(initialRequest, created.cancellation)
          val swapTo = request.swapTarget
          if (!swapTo.isNullOrBlank() &&
            !TranslationSettings.sameLanguage(swapTo, request.target) &&
            TranslationSettings.sameLanguage(initial.detectedSource, request.target)
          ) {
            created.cancellation.check()
            val swapped = provider.translate(
              TranslationRequest(request.text, request.target, swapTo),
              created.cancellation,
            )
            swapped.copy(targetLanguage = swapTo)
          } else {
            initial.copy(targetLanguage = request.target)
          }
        } catch (_: Exception) {
          null
        }
        finish(key, created, result, useCache)
      }
    } catch (_: java.util.concurrent.RejectedExecutionException) {
      finish(key, created, null, useCache)
    }
    return cancellation(key, created, callback)
  }

  private fun cancellation(key: TranslationCache.Key, job: Job, callback: (TranslationResult?) -> Unit) = Runnable {
    synchronized(this) {
      job.callbacks.remove(callback)
      if (jobs[key] === job && job.callbacks.isEmpty()) {
        jobs.remove(key)
        job.cancellation.cancel()
        job.future?.cancel(true)
        (workers as? java.util.concurrent.ThreadPoolExecutor)?.purge()
        job.timeout?.cancel(false)
      }
    }
  }

  private fun finish(key: TranslationCache.Key, job: Job, result: TranslationResult?, useCache: Boolean) {
    val callbacks: List<(TranslationResult?) -> Unit>
    synchronized(this) {
      if (jobs[key] !== job) return
      jobs.remove(key)
      job.timeout?.cancel(false)
      if (result == null) {
        unavailableUntil = clock() + 15_000 // Back off on outages/429; no retry storm during swipes.
        job.cancellation.cancel()
        job.future?.cancel(true)
      }
      if (result != null && useCache) cache.put(key, result, job.generation)
      callbacks = job.callbacks.toList()
    }
    callbacks.forEach { it(result) }
  }

  @Synchronized fun close() {
    closed = true
    jobs.values.forEach { it.cancellation.cancel(); it.future?.cancel(true); it.timeout?.cancel(false) }
    jobs.clear()
    cache.clear()
    workers.shutdownNow()
    timers.shutdownNow()
  }
}
