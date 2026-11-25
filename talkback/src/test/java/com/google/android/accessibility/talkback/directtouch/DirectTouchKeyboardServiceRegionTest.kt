/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.directtouch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.graphics.Region
import android.provider.Settings
import android.util.SparseArray
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.focusmanagement.LiftToActivateMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [34],
  application = Application::class,
  qualifiers = "w360dp-h780dp-xxhdpi",
  shadows = [DirectTouchKeyboardServiceRegionTest.WindowsShadow::class],
)
class DirectTouchKeyboardServiceRegionTest {

  class TestService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
  }

  @Implements(AccessibilityService::class)
  class WindowsShadow : ShadowAccessibilityService() {
    var onScreen: List<AccessibilityWindowInfo> = emptyList()

    @Implementation
    override fun getWindows(): List<AccessibilityWindowInfo> = ArrayList(onScreen)

    @Implementation
    override fun getWindowsOnAllDisplays(): SparseArray<List<AccessibilityWindowInfo>> =
      SparseArray<List<AccessibilityWindowInfo>>().apply {
        put(Display.DEFAULT_DISPLAY, ArrayList(onScreen))
      }
  }

  private lateinit var service: TestService
  private lateinit var serviceWindows: WindowsShadow
  private lateinit var prefs: FakeSharedPreferences
  private lateinit var controller: DirectTouchController
  private val sentRegions = mutableListOf<Region>()

  @Before
  fun setUp() {
    service = Robolectric.buildService(TestService::class.java).create().get()
    serviceWindows = Shadow.extract(service)
    prefs = FakeSharedPreferences()
    val feedback = Pipeline.FeedbackReturner { item ->
      item.failovers().forEach { part ->
        part.passThroughMode()?.let { sentRegions.add(Region(it.region())) }
      }
      true
    }
    controller = DirectTouchController(service, prefs, feedback) { DirectTouchApp.UNKNOWN }
  }

  @After
  fun tearDown() {
    controller.shutdown()
  }

  @Test
  fun keyboardWithItsOwnServiceKeepsRegionWhenNavBarDirectIsOn() {
    enableNavBarDirect()
    useKeyboard(BRAILLE_KEYBOARD_PACKAGE)
    enableServiceOf(BRAILLE_KEYBOARD_PACKAGE)

    evaluateWindows(statusBar(), navigationBar(), game())
    assertEquals(listOf(NAV_BAR_BOUNDS), sentRegions.map { it.bounds })

    sentRegions.clear()
    evaluateWindows(statusBar(), navigationBar(), game(), keyboard(BRAILLE_KEYBOARD_PACKAGE))
    assertTrue(sentRegions.isEmpty())

    evaluateWindows(statusBar(), navigationBar(), game())
    assertEquals(listOf(NAV_BAR_BOUNDS), sentRegions.map { it.bounds })
  }

  @Test
  fun keyboardWithoutOwnServiceKeepsNavBarRegion() {
    enableNavBarDirect()
    useKeyboard(BRAILLE_KEYBOARD_PACKAGE)
    enableServiceOf("com.example.other")

    evaluateWindows(statusBar(), navigationBar(), game(), keyboard(BRAILLE_KEYBOARD_PACKAGE))
    assertEquals(listOf(NAV_BAR_BOUNDS), sentRegions.map { it.bounds })
  }

  @Test
  fun keyboardOverlayWithItsOwnServiceKeepsRegionWhenNavBarDirectIsOn() {
    enableNavBarDirect()
    useKeyboard(BRAILLE_KEYBOARD_PACKAGE)
    enableServiceOf(BRAILLE_KEYBOARD_PACKAGE)

    evaluateWindows(statusBar(), navigationBar(), game())
    assertEquals(listOf(NAV_BAR_BOUNDS), sentRegions.map { it.bounds })

    sentRegions.clear()
    evaluateWindows(statusBar(), navigationBar(), game(), overlay(BRAILLE_KEYBOARD_PACKAGE))
    assertTrue(sentRegions.isEmpty())

    evaluateWindows(statusBar(), navigationBar(), game())
    assertEquals(listOf(NAV_BAR_BOUNDS), sentRegions.map { it.bounds })
  }

  @Test
  fun overlayOfAnotherServiceKeepsNavBarRegion() {
    enableNavBarDirect()
    useKeyboard(BRAILLE_KEYBOARD_PACKAGE)
    enableServiceOf(BRAILLE_KEYBOARD_PACKAGE)

    evaluateWindows(statusBar(), navigationBar(), game(), overlay(MAGNIFIER_PACKAGE))
    assertEquals(listOf(NAV_BAR_BOUNDS), sentRegions.map { it.bounds })
  }

  @Test
  fun keyboardWithItsOwnServiceKeepsRegionOverDirectTouchApp() {
    DirectTouchSettings.setAppEnabled(prefs, GAME_PACKAGE, true)
    ReflectionHelpers.setField(controller, "mainWindowId", GAME_ID)
    useKeyboard(BRAILLE_KEYBOARD_PACKAGE)
    enableServiceOf(BRAILLE_KEYBOARD_PACKAGE)

    evaluateWindows(statusBar(), navigationBar(), game())
    assertEquals(listOf(GAME_BOUNDS), sentRegions.map { it.bounds })

    sentRegions.clear()
    evaluateWindows(statusBar(), navigationBar(), game(), keyboard(BRAILLE_KEYBOARD_PACKAGE))
    evaluateWindows(statusBar(), navigationBar(), game(), overlay(BRAILLE_KEYBOARD_PACKAGE))
    assertTrue(sentRegions.isEmpty())

    evaluateWindows(statusBar(), navigationBar(), game())
    assertEquals(listOf(GAME_BOUNDS), sentRegions.map { it.bounds })
  }

  @Test
  fun keyboardWithoutOwnServiceDoesNotSuppressDirectTouchAppRegion() {
    DirectTouchSettings.setAppEnabled(prefs, GAME_PACKAGE, true)
    ReflectionHelpers.setField(controller, "mainWindowId", GAME_ID)
    useKeyboard(BRAILLE_KEYBOARD_PACKAGE)
    enableServiceOf("com.example.other")

    evaluateWindows(statusBar(), navigationBar(), game())
    sentRegions.clear()
    evaluateWindows(
      statusBar(),
      navigationBar(),
      game(),
      keyboard(BRAILLE_KEYBOARD_PACKAGE, Rect(0, 1500, 1080, 2220)),
    )

    assertEquals(listOf(Rect(0, 80, 1080, 1500)), sentRegions.map { it.bounds })
  }

  @Test
  fun ownPackageKeyboardDoesNotSuppressNavBarRegion() {
    enableNavBarDirect()
    useKeyboard(service.packageName)
    enableServiceOf(service.packageName)

    evaluateWindows(statusBar(), navigationBar(), game(), keyboard(service.packageName))
    assertEquals(listOf(NAV_BAR_BOUNDS), sentRegions.map { it.bounds })
  }

  private fun enableNavBarDirect() {
    prefs
      .edit()
      .putString(
        service.getString(R.string.pref_lift_to_activate_key),
        LiftToActivateMode.NAVIGATION_BAR.prefValue,
      )
      .apply()
  }

  private fun useKeyboard(packageName: String) {
    Settings.Secure.putString(
      service.contentResolver,
      Settings.Secure.DEFAULT_INPUT_METHOD,
      "$packageName/.Keyboard",
    )
  }

  private fun enableServiceOf(packageName: String) {
    val info = AccessibilityServiceInfo()
    ReflectionHelpers.setField(
      info,
      "mResolveInfo",
      ResolveInfo().apply {
        serviceInfo =
          ServiceInfo().apply {
            this.packageName = packageName
            name = "$packageName.Service"
          }
      },
    )
    shadowOf(service.getSystemService(AccessibilityManager::class.java))
      .setEnabledAccessibilityServiceList(listOf(info))
  }

  private fun evaluateWindows(vararg windows: AccessibilityWindowInfo) {
    serviceWindows.onScreen = windows.toList()
    controller.onDisplayStateChanged(true)
  }

  private fun statusBar() =
    window(STATUS_BAR_ID, SYSTEM, "Status bar", Rect(0, 0, 1080, 80), SYSTEM_UI)

  private fun navigationBar() =
    window(NAV_BAR_ID, SYSTEM, "Navigation bar", NAV_BAR_BOUNDS, SYSTEM_UI)

  private fun game() =
    window(GAME_ID, APPLICATION, "Game", SCREEN, GAME_PACKAGE, active = true)

  private fun keyboard(rootPackage: String, bounds: Rect = GAME_BOUNDS) =
    window(KEYBOARD_ID, INPUT_METHOD, "Keyboard", bounds, rootPackage, layer = 3)

  private fun overlay(rootPackage: String) =
    window(OVERLAY_ID, ACCESSIBILITY_OVERLAY, "Overlay", SCREEN, rootPackage, layer = 10)

  private fun window(
    id: Int,
    type: Int,
    title: String,
    bounds: Rect,
    rootPackage: String,
    active: Boolean = false,
    layer: Int = if (type == SYSTEM) 5 else 1,
  ): AccessibilityWindowInfo {
    val window = AccessibilityWindowInfo()
    val shadow = shadowOf(window)
    shadow.setId(id)
    shadow.setType(type)
    shadow.setTitle(title)
    shadow.setBoundsInScreen(bounds)
    shadow.setLayer(layer)
    shadow.setActive(active)
    shadow.setFocused(active)
    shadow.setDisplayId(Display.DEFAULT_DISPLAY)
    shadow.setRoot(AccessibilityNodeInfo().apply { packageName = rootPackage })
    return window
  }

  private companion object {
    const val STATUS_BAR_ID = 1
    const val NAV_BAR_ID = 2
    const val KEYBOARD_ID = 5
    const val OVERLAY_ID = 6
    const val GAME_ID = 10
    const val GAME_PACKAGE = "com.example.game"
    const val SYSTEM_UI = "com.android.systemui"
    const val BRAILLE_KEYBOARD_PACKAGE = "com.example.braillekeyboard"
    const val MAGNIFIER_PACKAGE = "com.example.magnifier"
    const val SYSTEM = AccessibilityWindowInfo.TYPE_SYSTEM
    const val APPLICATION = AccessibilityWindowInfo.TYPE_APPLICATION
    const val INPUT_METHOD = AccessibilityWindowInfo.TYPE_INPUT_METHOD
    const val ACCESSIBILITY_OVERLAY = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
    val SCREEN = Rect(0, 0, 1080, 2340)
    val NAV_BAR_BOUNDS = Rect(0, 2220, 1080, 2340)
    val GAME_BOUNDS = Rect(0, 80, 1080, 2220)
  }
}
