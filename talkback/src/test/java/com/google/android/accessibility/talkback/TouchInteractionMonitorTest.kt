/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback

import android.accessibilityservice.AccessibilityGestureEvent
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GESTURE_DOUBLE_TAP
import android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT
import android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP
import android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN
import android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP_AND_RIGHT
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.TouchInteractionController
import android.accessibilityservice.TouchInteractionController.STATE_CLEAR
import android.accessibilityservice.TouchInteractionController.STATE_DELEGATING
import android.accessibilityservice.TouchInteractionController.STATE_TOUCH_EXPLORING
import android.accessibilityservice.TouchInteractionController.STATE_TOUCH_INTERACTING
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Display
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.actor.DirectClick
import com.google.android.accessibility.talkback.analytics.TalkBackAnalytics
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.gestures.GestureConfiguration
import com.google.android.accessibility.utils.gestures.GestureManifold.GESTURE_1_FINGER_SINGLE_TAP_AND_HOLD
import com.google.android.accessibility.utils.gestures.GestureManifold.GESTURE_SWIPE_UP_AND_HOLD
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock

/**
 * Runs the monitor with Android's own TouchInteractionController passing it touch state changes and
 * motion events on the main thread, as Android does. The main looper is paused, so each test
 * decides when it runs, and can keep it busy while the user's finger moves on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TouchInteractionMonitorTest {
  /** Records the gestures and other results that reach the service, and their threads. */
  class RecordingService : TalkBackService() {
    val gestures = mutableListOf<Int>()
    val threads = mutableSetOf<Thread>()
    val enabledSwipeAndHoldGestures = mutableSetOf<Int>()
    var brailleRawTouchActive = false
    var passthroughAtTouch = false
    var oneFingerTapAndHoldEnabled = false

    override fun onGesture(accessibilityGestureEvent: AccessibilityGestureEvent): Boolean {
      threads.add(Thread.currentThread())
      gestures.add(accessibilityGestureEvent.gestureId)
      return true
    }

    override fun onGestureDetectionStarted() {
      threads.add(Thread.currentThread())
    }

    override fun isBrailleRawTouchActive(): Boolean = brailleRawTouchActive

    override fun isTouchInPassthroughRegion(x: Float, y: Float): Boolean = passthroughAtTouch

    override fun shouldDetectOneFingerTapAndHold(): Boolean =
      !brailleRawTouchActive && oneFingerTapAndHoldEnabled

    override fun shouldDetectSwipeAndHold(gestureId: Int): Boolean =
      !brailleRawTouchActive && gestureId in enabledSwipeAndHoldGestures
  }

  /** Records the threads that ask the controller for touch exploration. */
  private class RecordingReporter :
    TouchExplorationModeFailureReporter(object : TalkBackAnalytics {}) {
    val requests = mutableListOf<Thread>()

    override fun onRequestTouchExploration() {
      requests.add(Thread.currentThread())
      super.onRequestTouchExploration()
    }
  }

  private val mainThread: Thread = Looper.getMainLooper().thread
  private val main = Handler(Looper.getMainLooper())
  private val mainLooper: ShadowLooper = shadowOf(Looper.getMainLooper())
  private val reporter = RecordingReporter()
  private val provisioned = mutableListOf<Boolean>()
  private val provisionThreads = mutableListOf<Thread>()
  private lateinit var service: RecordingService
  private lateinit var controller: TouchInteractionController
  private lateinit var monitor: TouchInteractionMonitor
  private var start = 0L
  private var downTime = 0L

  @Before
  fun setUp() {
    // Whether a finger is down is kept for every monitor, so a test can leave one down.
    TouchInteractionMonitor::class.java.getDeclaredField("fingerDown").apply {
      isAccessible = true
      setBoolean(null, false)
    }
    service = Robolectric.buildService(RecordingService::class.java).get()
    controller =
      TouchInteractionController::class
        .java
        .getDeclaredConstructor(
          AccessibilityService::class.java,
          Any::class.java,
          Int::class.javaPrimitiveType,
        )
        .apply { isAccessible = true }
        .newInstance(service, Any(), Display.DEFAULT_DISPLAY)
    val display =
      service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    // As TalkBackService.registerGestureDetection() sets the monitor up and registers it: with no
    // executor, the controller calls the monitor on the main thread as each event comes in.
    monitor =
      TouchInteractionMonitor(
        display,
        SharedPreferencesUtils.getSharedPreferences(service),
        controller,
        service,
        PrimesController(),
        reporter,
        { enabled ->
          provisionThreads.add(Thread.currentThread())
          provisioned.add(enabled)
        },
        {},
      )
    monitor.setMultiFingerGesturesEnabled(true)
    monitor.setTwoFingerPassthroughEnabled(true)
    monitor.setServiceHandlesDoubleTap(true)
    controller.registerCallback(/* executor= */ null, monitor)
    // Without a connection to Android, the controller would refuse every state change.
    TouchInteractionController::class.java.getDeclaredField("mServiceDetectsGestures").apply {
      isAccessible = true
      setBoolean(controller, true)
    }
    start = SystemClock.uptimeMillis()
  }

  @After
  fun tearDown() {
    monitor.stop()
    controller.unregisterCallback(monitor)
  }

  private fun callController(name: String, type: Class<*>, argument: Any) {
    TouchInteractionController::class
      .java
      .getDeclaredMethod(name, type)
      .apply { isAccessible = true }
      .invoke(controller, argument)
  }

  /** Android changes the touch state [timeMs] after the start, through the main thread. */
  private fun stateChangeAt(state: Int, timeMs: Long) {
    main.postAtTime(
      { callController("onStateChanged", Int::class.javaPrimitiveType!!, state) },
      start + timeMs,
    )
  }

  /** The finger touches at ([x], [y]) [timeMs] after the start; Android passes it on through the main thread. */
  private fun touchAt(action: Int, x: Float, y: Float, timeMs: Long) {
    val eventTime = start + timeMs
    if (action == ACTION_DOWN) {
      downTime = eventTime
    }
    val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
    main.postAtTime({ callController("onMotionEvent", MotionEvent::class.java, event) }, eventTime)
  }

  /**
   * A gesture Backtalk dispatched touches at ([x], [y]) [timeMs] after the start. Android injects
   * it from no device, and passes it on through the main thread.
   */
  private fun injectedAt(action: Int, x: Float, y: Float, timeMs: Long) {
    val eventTime = start + timeMs
    if (action == ACTION_DOWN) {
      downTime = eventTime
    }
    val properties =
      MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_UNKNOWN
      }
    val coords =
      MotionEvent.PointerCoords().apply {
        this.x = x
        this.y = y
        pressure = 1f
        size = 1f
      }
    val event =
      MotionEvent.obtain(
        downTime,
        eventTime,
        action,
        1,
        arrayOf(properties),
        arrayOf(coords),
        0,
        0,
        1f,
        1f,
        KeyCharacterMap.VIRTUAL_KEYBOARD,
        0,
        InputDevice.SOURCE_TOUCHSCREEN,
        0,
      )
    main.postAtTime({ callController("onMotionEvent", MotionEvent::class.java, event) }, eventTime)
  }

  private val node = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo())
  // The gestures a direct click dispatched, and the nodes it clicked the usual way instead.
  private val pressGestures = mutableListOf<GestureDescription>()
  private val pressCallbacks = mutableListOf<GestureResultCallback>()
  private val usualClicks = mutableListOf<AccessibilityNodeInfoCompat>()

  /**
   * Gives the monitor a direct click that touches (300, 500), as TalkBackService does for the main
   * display.
   */
  private fun useDirectClick(): DirectClick {
    val directClick =
      DirectClick(
        locate = { Point(300, 500) },
        dispatch = { gesture, callback ->
          pressGestures.add(gesture)
          pressCallbacks.add(callback)
          true
        },
        readyToPress = { monitor.isReadyForDirectClick() },
        passThrough = { _, _ -> false },
        clickTheUsualWay = { node, _ ->
          usualClicks.add(node)
          true
        },
        handler = main,
      )
    monitor.setDirectClick(directClick)
    return directClick
  }

  /** Runs the main thread until [timeMs] after the start. */
  private fun runUntil(timeMs: Long) {
    mainLooper.idleFor(Duration.ofMillis(start + timeMs - SystemClock.uptimeMillis()))
  }

  /** Lets time pass until [timeMs] after the start while the main thread is busy with other work. */
  private fun mainThreadBusyUntil(timeMs: Long) {
    ShadowSystemClock.advanceBy(Duration.ofMillis(start + timeMs - SystemClock.uptimeMillis()))
  }

  @Test
  fun fingerHeldStillAsksForTouchExplorationOnTheMainThreadAfterTheDelay() {
    stateChangeAt(STATE_TOUCH_INTERACTING, 0)
    touchAt(ACTION_DOWN, 160f, 200f, 0)

    runUntil(140)
    assertEquals(listOf<Thread>(), reporter.requests)
    runUntil(200)
    assertEquals(listOf(mainThread), reporter.requests)
  }

  @Test
  fun swipeThatWaitedOnTheBusyMainThreadIsAGestureRatherThanTouchExploration() {
    stateChangeAt(STATE_TOUCH_INTERACTING, 0)
    touchAt(ACTION_DOWN, 40f, 200f, 0)
    runUntil(10)
    // The finger swipes right while the main thread is busy for longer than the touch exploration
    // delay. The delay ends after the moves came in, so the moves come first.
    for (step in 1..6) {
      touchAt(ACTION_MOVE, 40f + 40f * step, 200f, 10L + 15 * step)
    }
    touchAt(ACTION_UP, 280f, 200f, 110)
    mainThreadBusyUntil(400)
    runUntil(450)

    assertEquals(listOf<Thread>(), reporter.requests)
    assertEquals(listOf(GESTURE_SWIPE_RIGHT), service.gestures)
    assertEquals(setOf(mainThread), service.threads)
  }

  @Test
  fun doubleTapThatWaitedOnTheBusyMainThreadIsNotTakenForAHold() {
    stateChangeAt(STATE_TOUCH_INTERACTING, 0)
    touchAt(ACTION_DOWN, 160f, 200f, 0)
    touchAt(ACTION_UP, 160f, 200f, 50)
    touchAt(ACTION_DOWN, 160f, 200f, 150)
    runUntil(160)
    // The finger lifts 50 ms after it went down again, while the main thread is busy until after
    // the hold would have completed. A hold goes to the controller, not to the service.
    touchAt(ACTION_UP, 160f, 200f, 200)
    val holdEnds = 150 + ViewConfiguration.getLongPressTimeout().toLong()
    mainThreadBusyUntil(holdEnds + 100)
    runUntil(holdEnds + 110)

    assertEquals(listOf(GESTURE_DOUBLE_TAP), service.gestures)
    assertEquals(setOf(mainThread), service.threads)
  }

  @Test
  fun touchExplorationTurnedOffDuringATouchChangesWhenTheTouchEnds() {
    stateChangeAt(STATE_TOUCH_INTERACTING, 0)
    touchAt(ACTION_DOWN, 160f, 200f, 0)
    runUntil(10)

    monitor.requestA11yTouchExploreState(false)
    runUntil(20)
    assertEquals(listOf<Boolean>(), provisioned)

    touchAt(ACTION_UP, 160f, 200f, 50)
    stateChangeAt(STATE_CLEAR, 50)
    runUntil(60)
    assertEquals(listOf(false), provisioned)
    assertEquals(listOf(mainThread), provisionThreads)
  }

  @Test
  fun aTouchHandedStraightToTheAppStopsCountingAsAFingerDownWhenItEnds() {
    // Android hands a touch in a pass-through region, such as direct touch's, straight to the app.
    // The service gets its down, but not its lift.
    stateChangeAt(STATE_DELEGATING, 0)
    touchAt(ACTION_DOWN, 160f, 200f, 0)
    runUntil(10)
    assertTrue(TouchInteractionMonitor.isFingerDown())

    stateChangeAt(STATE_CLEAR, 100)
    runUntil(110)
    assertFalse(TouchInteractionMonitor.isFingerDown())
  }

  @Test
  fun aDirectClickRightAfterADoubleTapIsHandedToTheAppAndIsNeitherAFingerNorAGesture() {
    val directClick = useDirectClick()
    stateChangeAt(STATE_TOUCH_INTERACTING, 0)
    touchAt(ACTION_DOWN, 160f, 200f, 0)
    touchAt(ACTION_UP, 160f, 200f, 50)
    touchAt(ACTION_DOWN, 160f, 200f, 150)
    touchAt(ACTION_UP, 160f, 200f, 200)
    runUntil(210)
    assertEquals(listOf(GESTURE_DOUBLE_TAP), service.gestures)

    // The double tap clicks while Android still counts the touch as going on.
    assertTrue(directClick.click(node, null))
    injectedAt(ACTION_DOWN, 300f, 500f, 220)
    runUntil(230)
    // Handed to the app, so the finger goes up at once.
    assertEquals(2, pressGestures.size)
    assertFalse(TouchInteractionMonitor.isFingerDown())
    assertFalse(monitor.isReadyForDirectClick())

    stateChangeAt(STATE_DELEGATING, 235)
    runUntil(240)
    pressCallbacks[1].onCompleted(pressGestures[1])
    stateChangeAt(STATE_CLEAR, 340)
    runUntil(1000)
    assertEquals(listOf<AccessibilityNodeInfoCompat>(), usualClicks)
    assertEquals(listOf(GESTURE_DOUBLE_TAP), service.gestures)
    assertEquals(listOf<Thread>(), reporter.requests)
    assertTrue(monitor.isReadyForDirectClick())
  }

  @Test
  fun aDirectClickPressThatCanNotBeHandedOverIsLiftedAndClickedTheUsualWay() {
    val directClick = useDirectClick()
    stateChangeAt(STATE_TOUCH_INTERACTING, 0)
    runUntil(10)
    assertTrue(directClick.click(node, null))
    // Android went on with touch exploration before the press came.
    stateChangeAt(STATE_TOUCH_EXPLORING, 15)
    injectedAt(ACTION_DOWN, 300f, 500f, 20)
    runUntil(30)
    assertEquals(2, pressGestures.size)
    assertFalse(TouchInteractionMonitor.isFingerDown())

    // The lift reaches gesture detection too, as the app didn't get the press.
    injectedAt(ACTION_UP, 300f, 500f, 130)
    runUntil(140)
    pressCallbacks[1].onCompleted(pressGestures[1])
    assertEquals(listOf(node), usualClicks)
    assertFalse(TouchInteractionMonitor.isFingerDown())
    assertEquals(listOf<Int>(), service.gestures)
  }

  @Test
  fun aDirectClickPressAndroidHandedToTheAppItselfIsNotHandedOverAgain() {
    val directClick = useDirectClick()
    runUntil(10)
    assertTrue(directClick.click(node, null))
    // The press landed in a pass-through region, so Android handed it to the app at once.
    stateChangeAt(STATE_TOUCH_INTERACTING, 15)
    stateChangeAt(STATE_DELEGATING, 15)
    injectedAt(ACTION_DOWN, 300f, 500f, 15)
    runUntil(20)
    assertEquals(2, pressGestures.size)

    pressCallbacks[1].onCompleted(pressGestures[1])
    assertEquals(listOf<AccessibilityNodeInfoCompat>(), usualClicks)
    assertFalse(TouchInteractionMonitor.isFingerDown())
  }

  @Test
  fun directClickPressesOnlyWhenNoFingerIsDownAndNoTouchIsExplored() {
    useDirectClick()
    runUntil(5)
    assertTrue(monitor.isReadyForDirectClick())

    stateChangeAt(STATE_TOUCH_INTERACTING, 10)
    touchAt(ACTION_DOWN, 160f, 200f, 10)
    runUntil(20)
    assertFalse(monitor.isReadyForDirectClick())

    stateChangeAt(STATE_CLEAR, 30)
    runUntil(40)
    assertTrue(monitor.isReadyForDirectClick())

    // Android goes on with touch exploration for a while after the finger lifts.
    stateChangeAt(STATE_TOUCH_EXPLORING, 50)
    runUntil(60)
    assertFalse(monitor.isReadyForDirectClick())
  }

  @Test
  fun aSecondTapHeldDownIsNoDoubleTapAndKeepsDirectClickFromPressing() {
    useDirectClick()
    stateChangeAt(STATE_TOUCH_INTERACTING, 0)
    touchAt(ACTION_DOWN, 160f, 200f, 0)
    touchAt(ACTION_UP, 160f, 200f, 50)
    touchAt(ACTION_DOWN, 160f, 200f, 150)
    // The finger stays down past the hold, which goes to Android as a long press, and not to the
    // service as a click.
    runUntil(150 + ViewConfiguration.getLongPressTimeout() + 100L)

    assertEquals(listOf<Int>(), service.gestures)
    assertTrue(TouchInteractionMonitor.isFingerDown())
    assertFalse(monitor.isReadyForDirectClick())
    assertEquals(listOf<GestureDescription>(), pressGestures)
  }

  private fun oneCmPx(): Float =
    TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_MM,
      10f,
      service.resources.displayMetrics,
    )

  private fun swipeConfirmPx(): Float {
    val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
    return maxOf(touchSlop * 2.5f, oneCmPx() * 0.45f)
  }

  @Test
  fun compactSwipeUpAndHoldFromNearTopConfirmsAtShortHoldTimeout() {
    service.enabledSwipeAndHoldGestures.add(GESTURE_SWIPE_UP_AND_HOLD)
    val confirmPx = swipeConfirmPx() + 6f
    val startY = 80f

    stateChangeAt(STATE_TOUCH_INTERACTING, 0)
    touchAt(ACTION_DOWN, 200f, startY, 0)
    touchAt(ACTION_MOVE, 200f, startY - confirmPx * 0.45f, 40)
    touchAt(ACTION_MOVE, 200f, startY - confirmPx, 100)

    runUntil(100 + GestureConfiguration.SWIPE_AND_HOLD_TIMEOUT_MS - 30L)
    assertEquals(listOf<Int>(), service.gestures)
    assertEquals(listOf<Thread>(), reporter.requests)

    runUntil(100 + GestureConfiguration.SWIPE_AND_HOLD_TIMEOUT_MS + 20L)
    assertEquals(listOf(GESTURE_SWIPE_UP_AND_HOLD), service.gestures)
    assertEquals(listOf<Thread>(), reporter.requests)
  }

  @Test
  fun swipeUpAndHoldWithInitialDiagonalRollAndCurvedHoldArcStillMatches() {
    service.enabledSwipeAndHoldGestures.add(GESTURE_SWIPE_UP_AND_HOLD)
    val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
    val confirmPx = swipeConfirmPx() + 8f
    val cmPx = oneCmPx()
    val startX = 200f
    val startY = 400f

    stateChangeAt(STATE_TOUCH_INTERACTING, 0)
    touchAt(ACTION_DOWN, startX, startY, 0)
    // Initial thumb roll slightly steeper horizontally than 45 degrees before straightening up.
    touchAt(ACTION_MOVE, startX + touchSlop * 1.4f, startY - touchSlop * 1.1f, 40)
    touchAt(ACTION_MOVE, startX + touchSlop * 1.6f, startY - confirmPx, 110)
    // Natural curved thumb deceleration arc while holding: continues up and slightly right.
    touchAt(ACTION_MOVE, startX + cmPx * 0.55f, startY - confirmPx - cmPx * 0.65f, 180)

    runUntil(180 + GestureConfiguration.SWIPE_AND_HOLD_TIMEOUT_MS + 30L)
    assertEquals(listOf(GESTURE_SWIPE_UP_AND_HOLD), service.gestures)
    assertEquals(listOf<Thread>(), reporter.requests)
  }

  @Test
  fun quickSwipeUpAndTwoDirectionSwipesDoNotTriggerSwipeAndHold() {
    service.enabledSwipeAndHoldGestures.add(GESTURE_SWIPE_UP_AND_HOLD)
    val cmPx = oneCmPx()

    // 1. Quick swipe up
    stateChangeAt(STATE_TOUCH_INTERACTING, 0)
    touchAt(ACTION_DOWN, 200f, 500f, 0)
    for (step in 1..4) {
      touchAt(ACTION_MOVE, 200f, 500f - cmPx * 0.4f * step, 15L * step)
    }
    touchAt(ACTION_UP, 200f, 500f - cmPx * 1.6f, 80)
    stateChangeAt(STATE_CLEAR, 85)
    runUntil(200)

    // 2. L-swipe (up and right)
    stateChangeAt(STATE_TOUCH_INTERACTING, 300)
    touchAt(ACTION_DOWN, 200f, 500f, 300)
    for (step in 1..3) {
      touchAt(ACTION_MOVE, 200f, 500f - cmPx * 0.5f * step, 300L + 15 * step)
    }
    for (step in 1..3) {
      touchAt(ACTION_MOVE, 200f + cmPx * 0.5f * step, 500f - cmPx * 1.5f, 345L + 15 * step)
    }
    touchAt(ACTION_UP, 200f + cmPx * 1.5f, 500f - cmPx * 1.5f, 410)
    stateChangeAt(STATE_CLEAR, 415)
    runUntil(500)

    // 3. Back-and-forth (up and down)
    stateChangeAt(STATE_TOUCH_INTERACTING, 600)
    touchAt(ACTION_DOWN, 200f, 500f, 600)
    for (step in 1..3) {
      touchAt(ACTION_MOVE, 200f, 500f - cmPx * 0.5f * step, 600L + 15 * step)
    }
    for (step in 1..3) {
      touchAt(ACTION_MOVE, 200f, 500f - cmPx * 1.5f + cmPx * 0.5f * step, 645L + 15 * step)
    }
    touchAt(ACTION_UP, 200f, 500f, 710)
    stateChangeAt(STATE_CLEAR, 715)
    runUntil(1400)

    assertEquals(
      listOf(GESTURE_SWIPE_UP, GESTURE_SWIPE_UP_AND_RIGHT, GESTURE_SWIPE_UP_AND_DOWN),
      service.gestures,
    )
  }

  @Test
  fun deliberateSwipeUpAndHoldStillSucceedsWithShortTouchFocusDelay() {
    service.enabledSwipeAndHoldGestures.add(GESTURE_SWIPE_UP_AND_HOLD)
    val prefs = SharedPreferencesUtils.getSharedPreferences(service)
    prefs.edit().putString(service.getString(R.string.pref_touch_focus_time_out_key), "100").commit()
    runUntil(5)

    val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
    val confirmPx = swipeConfirmPx() + 6f
    val startY = 400f

    stateChangeAt(STATE_TOUCH_INTERACTING, 10)
    touchAt(ACTION_DOWN, 200f, startY, 10)
    // Early upward movement before the 50ms touch focus timeout expires extends the window.
    touchAt(ACTION_MOVE, 200f, startY - touchSlop * 2f, 35)
    // Finish reaching confirmation distance slowly (~220ms after down).
    touchAt(ACTION_MOVE, 200f, startY - confirmPx, 230)

    runUntil(230 + GestureConfiguration.SWIPE_AND_HOLD_TIMEOUT_MS + 30L)
    assertEquals(listOf(GESTURE_SWIPE_UP_AND_HOLD), service.gestures)
    assertEquals(listOf<Thread>(), reporter.requests)
  }

  @Test
  fun unassignedSwipeUpAndHoldDoesNotFireOrBlockTouchExploration() {
    // enabledSwipeAndHoldGestures is empty -> all SwipeAndHold gestures are unassigned.
    val confirmPx = swipeConfirmPx() + 6f
    val startY = 400f

    stateChangeAt(STATE_TOUCH_INTERACTING, 0)
    touchAt(ACTION_DOWN, 200f, startY, 0)
    touchAt(ACTION_MOVE, 200f, startY - confirmPx, 100)
    runUntil(100 + GestureConfiguration.SWIPE_AND_HOLD_TIMEOUT_MS + 200L)

    assertEquals(listOf<Int>(), service.gestures)
    assertEquals(listOf(mainThread), reporter.requests)
  }

  @Test
  fun swipeAndHoldDoesNotFireWhileBrailleRawTouchIsActive() {
    service.enabledSwipeAndHoldGestures.add(GESTURE_SWIPE_UP_AND_HOLD)
    service.brailleRawTouchActive = true
    val confirmPx = swipeConfirmPx() + 6f
    val startY = 400f

    stateChangeAt(STATE_TOUCH_INTERACTING, 0)
    touchAt(ACTION_DOWN, 200f, startY, 0)
    touchAt(ACTION_MOVE, 200f, startY - confirmPx * 0.5f, 40)
    touchAt(ACTION_MOVE, 200f, startY - confirmPx, 100)
    runUntil(100 + GestureConfiguration.SWIPE_AND_HOLD_TIMEOUT_MS + 200L)

    assertEquals(listOf<Int>(), service.gestures)
    assertEquals(listOf<Thread>(), reporter.requests)
  }

  @Test
  fun turningOffTouchExplorationCancelsPendingSwipeAndHoldTimer() {
    service.enabledSwipeAndHoldGestures.add(GESTURE_SWIPE_UP_AND_HOLD)
    val confirmPx = swipeConfirmPx() + 6f
    val startY = 400f

    stateChangeAt(STATE_TOUCH_INTERACTING, 0)
    touchAt(ACTION_DOWN, 200f, startY, 0)
    touchAt(ACTION_MOVE, 200f, startY - confirmPx * 0.5f, 40)
    touchAt(ACTION_MOVE, 200f, startY - confirmPx, 100)
    runUntil(150)

    monitor.requestA11yTouchExploreState(false)
    stateChangeAt(STATE_CLEAR, 200)
    runUntil(100 + GestureConfiguration.SWIPE_AND_HOLD_TIMEOUT_MS + 200L)

    assertEquals(listOf<Int>(), service.gestures)
    assertEquals(listOf(false), provisioned)
  }

  @Test
  fun swipeAndHoldDoesNotFireInsidePassthroughRegion() {
    service.enabledSwipeAndHoldGestures.add(GESTURE_SWIPE_UP_AND_HOLD)
    service.passthroughAtTouch = true
    val confirmPx = swipeConfirmPx() + 6f
    val startY = 400f

    stateChangeAt(STATE_TOUCH_INTERACTING, 0)
    touchAt(ACTION_DOWN, 200f, startY, 0)
    touchAt(ACTION_MOVE, 200f, startY - confirmPx * 0.5f, 40)
    touchAt(ACTION_MOVE, 200f, startY - confirmPx, 100)
    runUntil(100 + GestureConfiguration.SWIPE_AND_HOLD_TIMEOUT_MS + 200L)

    assertEquals(listOf<Int>(), service.gestures)
  }

  @Test
  fun oneFingerTapAndHoldDoesNotFireWhileBrailleRawTouchIsActive() {
    service.oneFingerTapAndHoldEnabled = true
    service.brailleRawTouchActive = true

    stateChangeAt(STATE_TOUCH_INTERACTING, 0)
    touchAt(ACTION_DOWN, 200f, 400f, 0)
    runUntil(GestureConfiguration.PROLONGED_HOLD_TIMEOUT_MS + 200L)

    assertEquals(listOf<Int>(), service.gestures)
    assertEquals(listOf<Thread>(), reporter.requests)
  }
}
