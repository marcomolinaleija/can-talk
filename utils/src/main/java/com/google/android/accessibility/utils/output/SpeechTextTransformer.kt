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

package com.google.android.accessibility.utils.output

import java.util.Locale

/** Optional, provider-neutral preparation at the head of the existing speech queue. */
interface SpeechTextTransformer {
  /** Capture origin/privacy while the source event is current, even when translation is off. */
  fun capture(item: FeedbackItem)
  fun shouldTransform(item: FeedbackItem): Boolean
  /** Callback is delivered once unless cancelled. Null means retain the original fragments. */
  fun transform(item: FeedbackItem, callback: Callback): Runnable
  fun close()

  fun interface Callback {
    fun ready(result: Result?)
  }

  class Result(val text: String, val locale: Locale)

  companion object {
    const val SOURCE_PACKAGE = "cantalk.translation.sourcePackage"
    const val FORCE_LOCALE = "cantalk.translation.forceLocale"
  }
}
