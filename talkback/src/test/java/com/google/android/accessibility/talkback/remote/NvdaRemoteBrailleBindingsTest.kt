/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.remote

import android.app.Application
import android.util.Pair
import com.google.android.accessibility.braille.common.BrailleImeAction
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.utils.FormFactorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NvdaRemoteBrailleBindingsTest {
  private val context = RuntimeEnvironment.getApplication()

  @Before
  fun setUp() {
    FormFactorUtils.initialize(context)
    BrailleUserPreferences.resetAllGestureActionMap(context)
    NvdaRemoteBindings.preferences(context).edit().clear().commit()
  }

  @Test
  fun phoneKeyboardAssignmentsStillOverrideDefaultsAndRemoteAssignmentsTakePriority() {
    val gesture = NvdaRemoteGesture(direction = "RIGHT")
    val brailleId = NvdaRemoteBrailleBindings(context).toBrailleGesture(gesture)!!.id
    BrailleUserPreferences.writeGestureAction(context, Pair.create(BrailleImeAction.COPY, listOf(brailleId)))
    val bindings = NvdaRemoteBindings(context)
    assertEquals("key:67:ctrl", bindings.resolve(NvdaRemoteMode.KEYBOARD, gesture))
    bindings.assign(NvdaRemoteMode.KEYBOARD, gesture, "key:86:ctrl")
    assertEquals("key:86:ctrl", bindings.resolve(NvdaRemoteMode.KEYBOARD, gesture))
  }

  @Test
  fun customHoldSwipeIsStillListedAndResolved() {
    val gesture = NvdaRemoteGesture(direction = "UP", fingers = 3, held = "12")
    val brailleId = NvdaRemoteBrailleBindings(context).toBrailleGesture(gesture)!!.id
    BrailleUserPreferences.writeGestureAction(context, Pair.create(BrailleImeAction.PASTE, listOf(brailleId)))
    val bindings = NvdaRemoteBindings(context)
    assertTrue(bindings.gestures(NvdaRemoteMode.KEYBOARD).any { it.id == gesture.id })
    assertEquals("key:86:ctrl", bindings.resolve(NvdaRemoteMode.KEYBOARD, gesture))
  }

  @Test
  fun builtInRemoteBindingsAreStillAvailableWithoutCustomKeyboardAssignments() {
    val bindings = NvdaRemoteBindings(context)
    assertEquals("braille_space", bindings.resolve(NvdaRemoteMode.KEYBOARD, NvdaRemoteGesture(direction = "RIGHT")))
    assertEquals("nvda", bindings.resolve(NvdaRemoteMode.CONTROL, NvdaRemoteGesture(dots = "2")))
  }
}
