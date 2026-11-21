/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.clipboard

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.google.android.accessibility.talkback.clipboard.ClipboardHistoryView.Action
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ClipboardHistoryViewTest {
  private lateinit var view: ClipboardHistoryView
  private val actions = mutableListOf<Action>()
  private var downTime = 0L
  @Before fun setUp() {
    view = ClipboardHistoryView(RuntimeEnvironment.getApplication()) { actions.add(it) }
  }
  private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
  private fun send(action: Int, x: Float = 200f, y: Float = 200f) {
    if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
    val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
    view.onTouchEvent(e)
    e.recycle()
  }
  private fun tap() { send(MotionEvent.ACTION_DOWN); advance(30); send(MotionEvent.ACTION_UP) }

  @Test fun singleTapWaitsForDoubleTap_thenCopiesOnce() {
    tap()
    assertTrue(actions.isEmpty())
    advance(350)
    assertEquals(listOf(Action.COPY), actions)
  }
  @Test fun doubleTapPastesOnce_withoutCopyingFirst() {
    tap()
    advance(80)
    tap()
    advance(350)
    assertEquals(listOf(Action.PASTE), actions)
  }
  @Test fun longPressTogglesFavorite_withoutCopyingOnRelease() {
    send(MotionEvent.ACTION_DOWN)
    advance(700)
    send(MotionEvent.ACTION_UP)
    advance(350)
    assertEquals(listOf(Action.FAVORITE), actions)
  }
  @Test fun swipesBrowseAndSwitchCategories_withoutTapSideEffects() {
    val ends = listOf(80f to 200f, 320f to 200f, 200f to 80f, 200f to 320f)
    for ((x, y) in ends) {
      send(MotionEvent.ACTION_DOWN)
      advance(80)
      send(MotionEvent.ACTION_MOVE, x, y)
      advance(80)
      send(MotionEvent.ACTION_UP, x, y)
      advance(350)
    }
    assertEquals(listOf(Action.PREVIOUS, Action.NEXT, Action.PREVIOUS_CATEGORY, Action.NEXT_CATEGORY), actions)
  }
  @Test fun canceledSurface_cannotCopyAfterDismissal() {
    tap()
    view.cancelPendingTouches()
    advance(350)
    assertTrue(actions.isEmpty())
  }

  private fun multi(action: Int, x: Float, yOffset: Float = 0f, count: Int = 2) {
    val props = Array(count) { i -> MotionEvent.PointerProperties().apply {
      id = i; toolType = MotionEvent.TOOL_TYPE_FINGER
    } }
    val coords = Array(count) { i -> MotionEvent.PointerCoords().apply {
      this.x = x; y = 200f + yOffset + i * 120; pressure = 1f; size = 1f
    } }
    val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count,
      props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    view.onTouchEvent(e)
    e.recycle()
  }
  @Test fun twoFingerLeftClosesOnlyAfterBothFingersLift_withoutCopyOrBrowse() {
    send(MotionEvent.ACTION_DOWN, 300f)
    multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 300f)
    advance(80)
    multi(MotionEvent.ACTION_MOVE, 150f)
    multi(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 150f)
    assertTrue(actions.isEmpty())
    send(MotionEvent.ACTION_UP, 150f)
    advance(350)
    assertEquals(listOf(Action.CLOSE), actions)
  }

  @Test fun twoFingerRightOpensSearch_upOpensSettings_downDeletes() {
    // 2 fingers right -> SEARCH
    send(MotionEvent.ACTION_DOWN, 150f)
    multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 150f)
    advance(80)
    multi(MotionEvent.ACTION_MOVE, 300f)
    multi(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 300f)
    send(MotionEvent.ACTION_UP, 300f)
    advance(350)

    // 2 fingers up -> SETTINGS
    send(MotionEvent.ACTION_DOWN, 200f, 300f)
    multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200f, 100f)
    advance(80)
    multi(MotionEvent.ACTION_MOVE, 200f, -60f)
    multi(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200f, -60f)
    send(MotionEvent.ACTION_UP, 200f, 140f)
    advance(350)

    // 2 fingers down -> DELETE
    send(MotionEvent.ACTION_DOWN, 200f, 100f)
    multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200f, -60f)
    advance(80)
    multi(MotionEvent.ACTION_MOVE, 200f, 100f)
    multi(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200f, 100f)
    send(MotionEvent.ACTION_UP, 200f, 300f)
    advance(350)

    assertEquals(listOf(Action.SEARCH, Action.SETTINGS, Action.DELETE), actions)
  }

  @Test fun threeFingerUpSpeaksHelp() {
    send(MotionEvent.ACTION_DOWN, 200f, 300f)
    multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200f, 100f, count = 2)
    multi(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200f, 100f, count = 3)
    advance(80)
    multi(MotionEvent.ACTION_MOVE, 200f, -80f, count = 3)
    multi(MotionEvent.ACTION_POINTER_UP or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200f, -80f, count = 3)
    multi(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200f, -80f, count = 2)
    send(MotionEvent.ACTION_UP, 200f, 120f)
    advance(350)
    assertEquals(listOf(Action.HELP), actions)
  }
}
