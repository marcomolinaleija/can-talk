/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.directtouch

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.graphics.Rect
import android.graphics.Region
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.accessibility.talkback.Pipeline
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DirectTouchKeyboardRegionTest {
  @Test
  fun keyboardRegionLeavesApplicationExplorableAndPreservesNavigationBar() {
    val keyboard = Rect(0, 1400, 1080, 2300)
    val navigation = Rect(0, 2300, 1080, 2400)
    val visible = DirectTouchRegions.partialRegion(listOf(keyboard, navigation))
    assertTrue(visible.contains(500, 1800))
    assertTrue(visible.contains(500, 2350))
    assertFalse(visible.contains(500, 500))
    val hidden = DirectTouchRegions.partialRegion(listOf(navigation))
    assertFalse(hidden.contains(500, 1800))
    assertTrue(hidden.contains(500, 2350))
    assertTrue(DirectTouchRegions.partialRegion(emptyList()).isEmpty)
  }

  @Test
  fun controllerDetectsOnlySelectedInputMethodWindows() {
    val service = Robolectric.buildService(KeyboardService::class.java).create().get()
    val prefs = FakeSharedPreferences()
    DirectTouchSettings.setAppEnabled(prefs, "abk", true)
    val controller = DirectTouchController(service, prefs, Pipeline.FeedbackReturner { true })
    val root = AccessibilityNodeInfo.obtain().apply { packageName = "abk" }
    val keyboard = AccessibilityWindowInfo.obtain()
    shadowOf(keyboard).setType(AccessibilityWindowInfo.TYPE_INPUT_METHOD)
    shadowOf(keyboard).setRoot(root)
    val detect = DirectTouchController::class.java.getDeclaredMethod(
      "directKeyboards", List::class.java
    ).apply { isAccessible = true }
    try {
      assertEquals(listOf(keyboard), detect.invoke(controller, listOf(keyboard)))
      shadowOf(keyboard).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
      assertEquals(emptyList<AccessibilityWindowInfo>(), detect.invoke(controller, listOf(keyboard)))
      shadowOf(keyboard).setType(AccessibilityWindowInfo.TYPE_INPUT_METHOD)
      DirectTouchSettings.setMasterEnabled(prefs, false)
      assertEquals(emptyList<AccessibilityWindowInfo>(), detect.invoke(controller, listOf(keyboard)))
      DirectTouchSettings.setMasterEnabled(prefs, true)
      DirectTouchSettings.setAppEnabled(prefs, "abk", false)
      assertEquals(emptyList<AccessibilityWindowInfo>(), detect.invoke(controller, listOf(keyboard)))
    } finally {
      controller.shutdown()
      keyboard.recycle()
      root.recycle()
    }
  }

  @Test
  fun controllerClearsPartialRegionWhenKeyboardDisappearsAndOnShutdown() {
    val service = Robolectric.buildService(KeyboardService::class.java).create().get()
    val sent = mutableListOf<Region>()
    val feedback = Pipeline.FeedbackReturner { item ->
      item.failovers().forEach { part ->
        part.passThroughMode()?.let { sent.add(Region(it.region())) }
      }
      true
    }
    val controller = DirectTouchController(service, FakeSharedPreferences(), feedback)
    val keyboard = AccessibilityWindowInfo.obtain()
    shadowOf(keyboard).setBoundsInScreen(Rect(0, 1400, 1080, 2300))
    val update = DirectTouchController::class.java.getDeclaredMethod(
      "updatePartRegion", List::class.java, Boolean::class.javaPrimitiveType, List::class.java
    ).apply { isAccessible = true }
    try {
      update.invoke(controller, emptyList<AccessibilityWindowInfo>(), false, listOf(keyboard))
      assertTrue(sent.last().contains(500, 1800))
      assertFalse(sent.last().contains(500, 500))
      update.invoke(controller, emptyList<AccessibilityWindowInfo>(), false, emptyList<AccessibilityWindowInfo>())
      assertTrue(sent.last().isEmpty)
      update.invoke(controller, emptyList<AccessibilityWindowInfo>(), false, listOf(keyboard))
      controller.shutdown()
      assertTrue(sent.last().isEmpty)
    } finally {
      controller.shutdown()
      keyboard.recycle()
    }
  }

  class KeyboardService : AccessibilityService() {
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}
  }
}
