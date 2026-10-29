/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.remote

import android.content.Context

/** Wear OS has no phone braille keyboard; NVDA Remote keeps its own bindings and defaults. */
internal class NvdaRemoteBrailleBindings(@Suppress("UNUSED_PARAMETER") context: Context) {
  fun customAction(@Suppress("UNUSED_PARAMETER") gesture: NvdaRemoteGesture): String? = null
  fun defaultAction(@Suppress("UNUSED_PARAMETER") gesture: NvdaRemoteGesture): String? = null
  fun gestureIds(): List<String> = emptyList()
}
