/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.appsearch

import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.TalkBackService

/** On-screen braille search is offered only on phones and tablets. */
class BrailleAppSearchController(service: TalkBackService, pipeline: Pipeline.FeedbackReturner) {
  val isShowing: Boolean get() = false
  fun show(): Boolean = false
  fun dismiss() { }
  fun shutdown() { }
  fun onAccessibilityEvent(event: AccessibilityEvent) { }
  fun onKeyEvent(event: KeyEvent): Boolean = false
}
