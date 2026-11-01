/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.web

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.UserManager
import com.google.android.accessibility.talkback.remote.NvdaRemoteGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class BrailleWebBindingsTest {
  private lateinit var userManager: UserManager
  private lateinit var saved: SharedPreferences
  private lateinit var guardedContext: Context
  private var preferenceOpens = 0
  private val swipe = NvdaRemoteGesture(direction = "RIGHT")

  @Before fun setUp() {
    val context = RuntimeEnvironment.getApplication()
    userManager = context.getSystemService(UserManager::class.java)
    shadowOf(userManager).setUserUnlocked(true)
    saved = context.getSharedPreferences("braille_web_gestures_v1", Context.MODE_PRIVATE)
    saved.edit().clear().commit()
    guardedContext = object : ContextWrapper(context) {
      override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        preferenceOpens++
        check(userManager.isUserUnlocked) {
          "SharedPreferences in credential encrypted storage are not available until after user is unlocked"
        }
        return super.getSharedPreferences(name, mode)
      }
    }
  }

  @Test fun constructionBeforeFirstUnlockDoesNotOpenPreferences() {
    shadowOf(userManager).setUserUnlocked(false)
    BrailleWebBindings(guardedContext)
    assertEquals(0, preferenceOpens)
  }

  @Test fun defaultsBeforeUnlockDoNotHideExistingAssignmentsAfterUnlock() {
    saved.edit().putString(swipe.id, BrailleWebAction.TOP.name).commit()
    shadowOf(userManager).setUserUnlocked(false)
    val bindings = BrailleWebBindings(guardedContext)
    assertEquals(BrailleWebAction.NEXT_ITEM, bindings.resolve(swipe))
    assertEquals(0, preferenceOpens)

    shadowOf(userManager).setUserUnlocked(true)
    assertEquals(BrailleWebAction.TOP, bindings.resolve(swipe))
  }

  @Test fun assignmentBeforeUnlockDoesNotOverwriteSavedGesture() {
    saved.edit().putString(swipe.id, BrailleWebAction.TOP.name).commit()
    shadowOf(userManager).setUserUnlocked(false)
    val bindings = BrailleWebBindings(guardedContext)
    bindings.assign(swipe, BrailleWebAction.BOTTOM)
    assertEquals(0, preferenceOpens)

    shadowOf(userManager).setUserUnlocked(true)
    assertEquals(BrailleWebAction.TOP, bindings.resolve(swipe))
  }

  @Test fun resetBeforeUnlockDoesNotDeleteSavedGestures() {
    saved.edit().putString(swipe.id, BrailleWebAction.TOP.name).commit()
    shadowOf(userManager).setUserUnlocked(false)
    val bindings = BrailleWebBindings(guardedContext)
    bindings.reset()
    assertEquals(0, preferenceOpens)

    shadowOf(userManager).setUserUnlocked(true)
    assertEquals(BrailleWebAction.TOP, bindings.resolve(swipe))
  }

  @Test fun assignmentAfterUnlockPersistsInTheExistingPreferenceFile() {
    shadowOf(userManager).setUserUnlocked(false)
    val bindings = BrailleWebBindings(guardedContext)
    shadowOf(userManager).setUserUnlocked(true)
    bindings.assign(swipe, BrailleWebAction.BOTTOM)
    assertEquals(BrailleWebAction.BOTTOM.name, saved.getString(swipe.id, null))
    assertEquals(BrailleWebAction.BOTTOM, BrailleWebBindings(guardedContext).resolve(swipe))
  }

  @Test fun resetAfterUnlockRestoresDefaults() {
    saved.edit().putString(swipe.id, BrailleWebAction.TOP.name).commit()
    val bindings = BrailleWebBindings(guardedContext)
    bindings.reset()
    assertNull(saved.getString(swipe.id, null))
    assertEquals(BrailleWebAction.NEXT_ITEM, bindings.resolve(swipe))
  }

  @Test fun specificDotSwipeStillOverridesGenericSwipeAfterUnlock() {
    val dotSwipe = swipe.copy(swipedDot = 2)
    saved.edit().putString(swipe.id, BrailleWebAction.TOP.name)
      .putString(dotSwipe.id, BrailleWebAction.BOTTOM.name).commit()
    val bindings = BrailleWebBindings(guardedContext)
    assertEquals(BrailleWebAction.BOTTOM, bindings.resolve(dotSwipe))
    assertEquals(BrailleWebAction.TOP, bindings.resolve(swipe.copy(swipedDot = 3)))
  }

  @Test fun reservedGesturesAndDotCommandsRemainAvailableBeforeUnlock() {
    shadowOf(userManager).setUserUnlocked(false)
    val bindings = BrailleWebBindings(guardedContext)
    assertEquals(BrailleWebAction.MENU,
      bindings.resolve(NvdaRemoteGesture(fingers = 3, direction = "UP")))
    assertEquals(BrailleWebAction.EXIT,
      bindings.resolve(NvdaRemoteGesture(fingers = 2, direction = "LEFT")))
    assertEquals(BrailleWebAction.TOP, bindings.resolve(NvdaRemoteGesture(dots = "3")))
    assertEquals(BrailleWebAction.BOTTOM, bindings.resolve(NvdaRemoteGesture(dots = "6")))
    assertEquals(0, preferenceOpens)
  }

  @Test fun heldGestureLoadsItsSavedAssignmentAfterUnlock() {
    val heldSwipe = swipe.copy(held = "12")
    saved.edit().putString(heldSwipe.id, BrailleWebAction.ACTIVATE.name).commit()
    shadowOf(userManager).setUserUnlocked(false)
    val bindings = BrailleWebBindings(guardedContext)
    assertNull(bindings.resolve(heldSwipe))
    shadowOf(userManager).setUserUnlocked(true)
    assertEquals(BrailleWebAction.ACTIVATE, bindings.resolve(heldSwipe))
  }
}
