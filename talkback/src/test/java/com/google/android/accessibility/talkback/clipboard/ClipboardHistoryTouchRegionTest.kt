/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.clipboard

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.graphics.Region
import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.talkback.actor.PassThroughModeActor
import com.google.android.accessibility.utils.FormFactorUtils
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ClipboardHistoryTouchRegionTest {
  class RecordingService : AccessibilityService() {
    var region = Region()
    override fun onAccessibilityEvent(event: AccessibilityEvent) { }
    override fun onInterrupt() { }
    override fun setTouchExplorationPassthroughRegion(displayId: Int, region: Region) {
      this.region = Region(region)
    }
  }
  private lateinit var service: RecordingService
  private lateinit var actor: PassThroughModeActor
  private val screen = Region(0, 0, 1000, 2000)
  private val keyboard = Region(0, 1000, 1000, 2000)
  @Before fun setUp() {
    service = Robolectric.buildService(RecordingService::class.java).create().get()
    FormFactorUtils.initialize(service)
    actor = PassThroughModeActor(service)
    actor.setPipeline { true }
  }

  @Test fun closingHistory_restoresBrailleRegion_evenAfterKeyboardResizes() {
    actor.lockTouchExplorePassThrough(keyboard)
    actor.setOverlayTouchRegion(screen)
    assertEquals(screen, service.region)
    val resized = Region(0, 800, 1000, 2000)
    actor.lockTouchExplorePassThrough(resized)
    assertEquals(screen, service.region)
    actor.setOverlayTouchRegion(null)
    assertEquals(resized, service.region)
  }
  @Test fun directTouchUpdates_cannotOverwriteOpenHistory_andReturnOnClose() {
    actor.setOverlayTouchRegion(screen)
    val direct = Region(50, 50, 500, 900)
    actor.setDirectTouchRegion(direct)
    assertEquals(screen, service.region)
    actor.setOverlayTouchRegion(null)
    assertEquals(direct, service.region)
  }
  @Test fun keyboardClosingUnderHistory_doesNotRemoveHistoryTouchRegion() {
    actor.lockTouchExplorePassThrough(keyboard)
    actor.setOverlayTouchRegion(screen)
    actor.lockTouchExplorePassThrough(Region())
    assertEquals(screen, service.region)
    actor.setOverlayTouchRegion(null)
    assertEquals(Region(), service.region)
  }
}
