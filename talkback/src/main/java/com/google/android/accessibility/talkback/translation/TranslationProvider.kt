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

import java.util.concurrent.CancellationException

data class TranslationRequest(
  val text: String,
  val source: String,
  val target: String,
  val swapTarget: String? = null,
)
data class TranslationResult(
  val text: String,
  val detectedSource: String? = null,
  val targetLanguage: String = "",
)

/** Implement only this contract to add Cloud, ML Kit, Gemini or a self-hosted service. */
interface TranslationProvider {
  val id: String
  val sendsTextOffDevice: Boolean
  val maxInputCharacters: Int get() = 16_000
  fun translate(request: TranslationRequest, cancellation: TranslationCancellation): TranslationResult
}

/** Cooperative cancellation also closes an in-flight HTTP connection. */
class TranslationCancellation {
  @Volatile var cancelled = false
    private set
  private var action: (() -> Unit)? = null
  @Synchronized fun onCancel(action: (() -> Unit)?) {
    this.action = action
    if (cancelled) action?.invoke()
  }
  @Synchronized fun cancel() {
    cancelled = true
    action?.invoke()
    action = null
  }
  fun check() {
    if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException()
  }
}
