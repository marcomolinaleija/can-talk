/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.remote

import android.content.Context
import android.widget.FrameLayout

/** A raw braille surface owned by the remote activity, independent of the local IME. */
abstract class NvdaRemoteInput(context: Context) : FrameLayout(context) {
  interface Listener {
    fun onGesture(gesture: NvdaRemoteGesture)
    fun onAnnouncement(text: String)
    fun onHold(dots: String): Boolean
    fun onHeldDotsChanged(dots: String)
  }
  abstract fun setDescription(text: String)
  abstract fun close()
}
