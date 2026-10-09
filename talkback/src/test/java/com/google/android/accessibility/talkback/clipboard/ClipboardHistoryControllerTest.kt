/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.clipboard

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Region
import android.os.UserManager
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.clipboard.ClipboardHistoryView.Action
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor
import com.google.android.accessibility.talkback.focusmanagement.record.AccessibilityFocusActionHistory
import com.google.android.accessibility.utils.FocusFinder
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.output.SpeechController
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
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "es-rMX")
@LooperMode(LooperMode.Mode.PAUSED)
class ClipboardHistoryControllerTest {
  class RecordingService : TalkBackService() {
    var region: Region? = null
    var inputFocus: AccessibilityNodeInfo? = null
    // Attach an Android service context without starting the entire reader pipeline.
    override fun onCreate() { }
    override fun onDestroy() { }
    override fun findFocus(focus: Int): AccessibilityNodeInfo? =
      if (focus == AccessibilityNodeInfo.FOCUS_INPUT) inputFocus else null
    override fun setClipboardHistoryTouchRegion(region: Region?) {
      this.region = region?.let { Region(it) }
    }
  }

  private lateinit var service: RecordingService
  private lateinit var controller: ClipboardHistoryController
  private lateinit var clipboard: ClipboardManager
  private lateinit var store: ClipboardHistoryStore
  private val spoken = mutableListOf<String>()
  private val queueModes = mutableListOf<Int>()

  @Before fun setUp() {
    service = Robolectric.buildService(RecordingService::class.java).create().get()
    FormFactorUtils.initialize(service)
    shadowOf(service.getSystemService(UserManager::class.java)).setUserUnlocked(true)
    clipboard = service.getSystemService(ClipboardManager::class.java)
    clipboard.clearPrimaryClip()
    val prefs = service.applicationContext.getSharedPreferences("clipboard_history", Context.MODE_PRIVATE)
    prefs.edit().clear().commit()
    store = ClipboardHistoryStore(prefs)
    store.record("Primero")
    store.toggleFavorite("Primero")
    store.record("Segundo")
    val focus = AccessibilityFocusMonitor(service, FocusFinder(service), AccessibilityFocusActionHistory(service).reader)
    controller = ClipboardHistoryController(service, Pipeline.FeedbackReturner { feedback ->
      feedback.failovers().forEach { part -> part.speech()?.let { speech ->
        speech.text()?.let { spoken.add(it.toString()) }
        speech.options()?.let { queueModes.add(it.mQueueMode) }
      } }
      true
    }, focus)
  }

  @After fun tearDown() { controller.shutdown() }

  private fun action(action: Action) {
    ReflectionHelpers.callInstanceMethod<Unit>(controller, "onAction", ClassParameter.from(Action::class.java, action))
  }

  @Test fun opening_announcesOnlyOpened_thenNavigationReadsFirstItemAndTotal() {
    assertTrue(controller.show())
    assertEquals(listOf("Historial abierto"), spoken)
    action(Action.NEXT)
    assertEquals("Segundo. 1 de 2", spoken.last())
    action(Action.NEXT)
    assertEquals("Primero. 2 de 2", spoken.last())
    action(Action.PREVIOUS)
    assertEquals("Segundo. 1 de 2", spoken.last())
  }

  @Test fun firstLeftSwipe_alsoReadsFirstItem() {
    assertTrue(controller.show())
    action(Action.PREVIOUS)
    assertEquals("Segundo. 1 de 2", spoken.last())
  }

  @Test fun bothCategoryDirections_wrapRepeatedly() {
    assertTrue(controller.show())
    for (direction in listOf(Action.PREVIOUS_CATEGORY, Action.NEXT_CATEGORY)) {
      action(direction)
      assertEquals("Favoritos", spoken.last())
      action(Action.NEXT)
      assertEquals("Primero. 1 de 1", spoken.last())
      action(direction)
      assertEquals("General", spoken.last())
      action(Action.NEXT)
      assertEquals("Segundo. 1 de 2", spoken.last())
    }
  }

  @Test fun copy_closesAndRestoresTouchRegion_andCopiesWholeText() {
    assertTrue(controller.show())
    action(Action.COPY)
    assertFalse(controller.isShowing)
    assertEquals(null, service.region)
    assertEquals("Segundo", clipboard.primaryClip!!.getItemAt(0).text.toString())
    assertEquals("Historial cerrado. Copiado", spoken.last())
  }

  @Test fun pasteWithoutOriginalEditor_closesWithoutReplacingClipboard() {
    assertTrue(controller.show())
    action(Action.PASTE)
    assertFalse(controller.isShowing)
    assertEquals(null, service.region)
    assertFalse(clipboard.hasPrimaryClip())
    assertTrue(spoken.last().startsWith("Historial cerrado. El campo de texto original no está disponible"))
  }

  @Test fun emptyFavorites_copyAndPasteBothClose() {
    assertTrue(controller.show())
    action(Action.NEXT)
    action(Action.NEXT)
    action(Action.FAVORITE)
    controller.dismiss()
    for (operation in listOf(Action.COPY, Action.PASTE)) {
      assertTrue(controller.show())
      action(Action.NEXT_CATEGORY)
      assertEquals("Favoritos", spoken.last())
      action(Action.NEXT)
      assertEquals("Sin elementos", spoken.last())
      action(operation)
      assertFalse(controller.isShowing)
      assertFalse(clipboard.hasPrimaryClip())
      assertEquals("Historial cerrado. Sin elementos", spoken.last())
    }
  }

  @Test fun explicitClose_announcesOnce_andReopeningDoesNotReadText() {
    assertTrue(controller.show())
    action(Action.CLOSE)
    assertEquals("Historial cerrado", spoken.last())
    assertFalse(controller.isShowing)
    val count = spoken.size
    controller.dismiss()
    assertEquals(count, spoken.size)
    assertTrue(controller.show())
    assertEquals("Historial abierto", spoken.last())
  }

  @Test fun backKey_announcesClosedOnlyOnRelease() {
    assertTrue(controller.show())
    assertTrue(controller.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
    assertEquals("Historial abierto", spoken.last())
    assertTrue(controller.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
    assertEquals("Historial cerrado", spoken.last())
    assertFalse(controller.isShowing)
  }

  private fun editor(acceptsPaste: Boolean): MutableList<Int> {
    val node = AccessibilityNodeInfo.obtain(View(service)).apply { isEditable = true }
    val actions = mutableListOf<Int>()
    shadowOf(node).setRefreshReturnValue(true)
    shadowOf(node).setOnPerformActionListener { action, _ ->
      actions.add(action)
      acceptsPaste
    }
    service.inputFocus = node
    return actions
  }

  @Test fun acceptedPaste_announcesElementPasted_andProtectsConfirmationFromTextSpeech() {
    val performed = editor(acceptsPaste = true)
    assertTrue(controller.show())
    action(Action.PASTE)
    assertFalse(controller.isShowing)
    assertTrue(performed.isEmpty())
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
    assertEquals(listOf(AccessibilityNodeInfo.ACTION_PASTE), performed)
    assertEquals("Segundo", clipboard.primaryClip!!.getItemAt(0).text.toString())
    assertEquals("Historial cerrado. Elemento pegado", spoken.last())
    assertEquals(SpeechController.QUEUE_MODE_INTERRUPT_AND_UNINTERRUPTIBLE_BY_NEW_SPEECH, queueModes.last())
  }

  @Test fun rejectedPaste_announcesFailure_insteadOfSuccess() {
    val performed = editor(acceptsPaste = false)
    assertTrue(controller.show())
    action(Action.PASTE)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
    assertEquals(listOf(AccessibilityNodeInfo.ACTION_PASTE), performed)
    assertEquals("Historial cerrado. Este campo no pudo pegar el texto", spoken.last())
    assertFalse(spoken.any { it.contains("Elemento pegado") })
  }

  @Test fun changedInputFocus_neverPastesIntoAnotherEditor_orConfirmsSuccess() {
    val originalActions = editor(acceptsPaste = true)
    assertTrue(controller.show())
    action(Action.PASTE)
    val otherActions = editor(acceptsPaste = true)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
    assertTrue(originalActions.isEmpty())
    assertTrue(otherActions.isEmpty())
    assertTrue(spoken.last().startsWith("Historial cerrado. El campo de texto original no está disponible"))
    assertFalse(spoken.any { it.contains("Elemento pegado") })
  }
}
