/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.gesture

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.content.Context
import android.graphics.Region
import android.view.accessibility.AccessibilityEvent
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.accessibility.talkback.ActorState
import com.google.android.accessibility.talkback.ActorStateWritable
import com.google.android.accessibility.talkback.Interpretation
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.PassThroughModeActor
import com.google.android.accessibility.talkback.interpreters.PassThroughModeInterpreter
import com.google.android.accessibility.talkback.preference.base.GestureListPreference
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.Performance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es-rMX", application = Application::class)
class ExperimentalTouchTest {

  class RecordingService : AccessibilityService() {
    var region = Region()
    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}
    override fun setTouchExplorationPassthroughRegion(displayId: Int, region: Region) {
      this.region = Region(region)
    }
  }

  private lateinit var context: Context
  private lateinit var service: RecordingService
  private lateinit var actor: PassThroughModeActor
  private lateinit var interpreter: PassThroughModeInterpreter

  @Before
  fun setUp() {
    context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), androidx.appcompat.R.style.Theme_AppCompat)
    service = Robolectric.buildService(RecordingService::class.java).create().get()
    FormFactorUtils.initialize(service)
    actor = PassThroughModeActor(service)
    actor.setPipeline { true }

    val writable = ActorStateWritable(
      null, null, null, null, null, null, null, null, null,
      actor.state, null, null, null, null
    )
    val actorState = ActorState(writable)

    interpreter = PassThroughModeInterpreter()
    interpreter.setActorState(actorState)
  }

  @Test
  fun actionAvailableInGestureSettings_andItsSavedValueResolvesInSpanish() {
    val preference = GestureListPreference(context, null)
    preference.key = "experimental_touch_gesture"
    preference.setValue("EXPERIMENTAL_TOUCH")
    assertEquals("Toque experimental", preference.summary)
    assertEquals("Toque experimental", GestureShortcutMapping.getActionString(context, "EXPERIMENTAL_TOUCH"))
    assertEquals("Toque experimental", context.getString(R.string.shortcut_experimental_touch))

    val actionEnum = GestureShortcutMapping.TalkbackAction.valueOf("EXPERIMENTAL_TOUCH")
    assertNotNull(actionEnum)
    assertEquals(R.string.shortcut_value_experimental_touch, actionEnum.actionKeyResId)
    assertEquals(R.string.shortcut_experimental_touch, actionEnum.actionNameResId)
  }

  @Test
  fun passThroughModeActor_activationAndDeactivation() {
    assertFalse(actor.state.isPassThroughModeActive)

    actor.setTouchExplorePassThrough(true)
    assertTrue(actor.state.isPassThroughModeActive)
    assertFalse(service.region.isEmpty)

    actor.setTouchExplorePassThrough(false)
    assertFalse(actor.state.isPassThroughModeActive)
    assertTrue(service.region.isEmpty)
  }

  @Test
  @Suppress("DEPRECATION")
  fun passThroughModeInterpreter_lifecycleSingleTapPassThrough() {
    val dispatchedInterpretations = mutableListOf<Interpretation>()
    interpreter.setPipeline { _, _, interp, _ ->
      if (interp != null) {
        dispatchedInterpretations.add(interp)
      }
      true
    }

    actor.setTouchExplorePassThrough(true)
    assertTrue(actor.state.isPassThroughModeActive)

    val startEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START)
    interpreter.onAccessibilityEvent(startEvent, Performance.EVENT_ID_UNTRACKED)
    assertEquals(1, dispatchedInterpretations.size)
    assertEquals(
      Interpretation.ID(Interpretation.ID.Value.PASS_THROUGH_INTERACTION_START),
      dispatchedInterpretations[0]
    )

    val endEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END)
    interpreter.onAccessibilityEvent(endEvent, Performance.EVENT_ID_UNTRACKED)
    assertEquals(2, dispatchedInterpretations.size)
    assertEquals(
      Interpretation.ID(Interpretation.ID.Value.PASS_THROUGH_INTERACTION_END),
      dispatchedInterpretations[1]
    )

    // Ensure a second end event without a new start does not trigger another end interpretation
    interpreter.onAccessibilityEvent(endEvent, Performance.EVENT_ID_UNTRACKED)
    assertEquals(2, dispatchedInterpretations.size)
  }
}
