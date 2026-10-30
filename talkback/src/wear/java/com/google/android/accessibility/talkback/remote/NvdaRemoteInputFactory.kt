/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.remote

import android.content.Context
import android.util.Size

/** The six-dot remote surface requires a phone or tablet. */
object NvdaRemoteInputFactory {
  fun dotCount(context: Context): Int = 0

  fun create(context: Context, size: Size, listener: NvdaRemoteInput.Listener): NvdaRemoteInput? = null
}
