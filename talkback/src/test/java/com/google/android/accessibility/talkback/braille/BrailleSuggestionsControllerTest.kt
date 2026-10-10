/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.braille

import android.app.Application
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrailleSuggestionsControllerTest {
  private lateinit var window: AccessibilityWindowInfo
  private lateinit var root: AccessibilityNodeInfo
  private lateinit var editor: AccessibilityNodeInfo
  private lateinit var title: AccessibilityNodeInfo
  private lateinit var container: AccessibilityNodeInfo
  private lateinit var list: AccessibilityNodeInfo
  private lateinit var controller: BrailleSuggestionsController
  private val announcements = mutableListOf<Pair<Int, List<Any>>>()
  private val actions = mutableListOf<Pair<AccessibilityNodeInfoCompat, Int>>()
  private val scheduled = mutableListOf<() -> Unit>()
  private var allowed = true
  private var acceptAction = true

  @Before fun setup() {
    window = AccessibilityWindowInfo.obtain()
    shadowOf(window).setId(12)
    root = node("")
    title = node("conversation_contact_name", root).apply { text = "Test group" }
    editor = node("entry", root).apply {
      text = "@"; isEditable = true; isFocused = true; setTextSelection(1, 1)
    }
    container = node("mention_attach", root)
    list = node("list", container).apply { isScrollable = true }
    controller = BrailleSuggestionsController(
      { AccessibilityNodeInfoCompat.wrap(editor) },
      { if (it == 12) AccessibilityNodeInfoCompat.wrap(root) else null },
      { allowed }, { node, action -> actions.add(node to action); acceptAction },
      { resource, args -> announcements.add(resource to args.toList()) },
      { task -> scheduled.add(task) },
    )
  }

  private fun node(id: String, parent: AccessibilityNodeInfo? = null): AccessibilityNodeInfo {
    val result = AccessibilityNodeInfo.obtain().apply {
      packageName = "com.whatsapp"
      if (id.isNotEmpty()) viewIdResourceName = "com.whatsapp:id/$id"
      isVisibleToUser = true; isEnabled = true
      setBoundsInScreen(Rect(0, 0, 500, 100))
    }
    shadowOf(result).setAccessibilityWindowInfo(window)
    shadowOf(result).setRefreshReturnValue(true)
    parent?.let { shadowOf(it).addChild(result) }
    return result
  }

  private fun row(label: String, parent: AccessibilityNodeInfo = list): AccessibilityNodeInfo {
    val row = node("", parent).apply { isClickable = true; contentDescription = label }
    node("primary_name", row).text = label
    return row
  }

  @Test fun entersWithoutSelectingAndNavigationNeverClicks() {
    row("Ana"); row("Luis")
    assertTrue(controller.enter())
    assertEquals(R.string.braille_suggestions_control, announcements.last().first)
    assertTrue(controller.confirm())
    assertEquals(R.string.braille_suggestions_choose, announcements.last().first)
    controller.move(true)
    assertEquals(listOf("Ana", 1, 2), announcements.last().second)
    controller.move(true)
    assertEquals(listOf("Luis", 2, 2), announcements.last().second)
    controller.move(false)
    assertEquals(listOf("Ana", 1, 2), announcements.last().second)
    assertTrue(actions.isEmpty())
  }

  @Test fun confirmsOnlyTheChosenRowAndLeavesSuggestionMode() {
    row("Ana"); val chosen = row("Luis")
    controller.enter(); controller.move(true); controller.move(true)
    assertTrue(controller.confirm())
    assertEquals(listOf(AccessibilityNodeInfoCompat.wrap(chosen) to AccessibilityNodeInfoCompat.ACTION_CLICK), actions)
    assertFalse(controller.isActive)
    assertEquals(R.string.braille_suggestions_inserted, announcements.last().first)
    assertTrue(editor.isFocused)
  }

  @Test fun identicalNamesRemainDistinctRows() {
    row("Ana").contentDescription = "Ana, first account"
    val second = row("Ana").apply { contentDescription = "Ana, second account" }
    controller.enter(); controller.move(true); controller.move(true); controller.confirm()
    assertEquals(AccessibilityNodeInfoCompat.wrap(second), actions.single().first)
  }

  @Test fun doesNotRecognizeOrdinaryListsOutsideTheMentionContainer() {
    editor.text = "Hello"; editor.setTextSelection(5, 5)
    container.isVisibleToUser = false
    row("Message sender", node("list", root))
    assertFalse(controller.isAvailable())
    assertFalse(controller.enter())
  }

  private fun omitStructuralContainer() {
    // The compressed accessibility tree reparents important nodes to the window.
    root = node("")
    shadowOf(root).addChild(editor)
    shadowOf(root).addChild(title)
    shadowOf(root).addChild(list)
  }

  @Test fun compressedTreeWithoutMentionContainerSupportsNavigationAndConfirmation() {
    val chosen = row("Ana"); row("Luis")
    omitStructuralContainer()
    assertTrue(controller.isAvailable())
    assertTrue(controller.enter())
    assertTrue(controller.move(true))
    assertEquals(listOf("Ana", 1, 2), announcements.last().second)
    assertTrue(controller.confirm())
    assertEquals(AccessibilityNodeInfoCompat.wrap(chosen), actions.single().first)
  }

  @Test fun compressedTreeRejectsAmbiguousListsWithMentionRows() {
    row("Ana")
    omitStructuralContainer()
    row("Luis", node("list", root))
    assertFalse(controller.enter())
    assertTrue(actions.isEmpty())
  }

  @Test fun compressedTreeRejectsListsWithoutWhatsAppNameRows() {
    val otherRow = node("", list).apply { isClickable = true }
    node("message_text", otherRow).text = "Message"
    omitStructuralContainer()
    assertFalse(controller.enter())
  }

  @Test fun compressedTreeRequiresMentionQueryAtCursorAndRejectsEmailAddresses() {
    row("Ana")
    omitStructuralContainer()
    for (draft in listOf("Hello", "test@example.org", "@Ana\n")) {
      editor.text = draft; editor.setTextSelection(draft.length, draft.length)
      assertFalse(controller.isAvailable())
    }
    editor.text = "Hello @An"; editor.setTextSelection(9, 9)
    assertTrue(controller.isAvailable())
    editor.setTextSelection(3, 3)
    assertFalse(controller.isAvailable())
  }

  @Test fun excludesHiddenDisabledAndPasswordRows() {
    row("Hidden").isVisibleToUser = false
    row("Disabled").isEnabled = false
    row("Secret").isPassword = true
    row("Visible")
    controller.enter(); controller.move(true)
    assertEquals(listOf("Visible", 1, 1), announcements.last().second)
  }

  @Test fun otherPackagesOrNonfocusedEditorsCannotSupplySuggestions() {
    row("Ana")
    editor.packageName = "other.app"
    assertFalse(controller.enter())
    editor.packageName = "com.whatsapp"; editor.isFocused = false
    assertFalse(controller.enter())
  }

  @Test fun changedDraftOrChatCancelsInsteadOfClicking() {
    row("Ana")
    controller.enter(); controller.move(true)
    editor.text = "@l"
    assertTrue(controller.confirm()); assertFalse(controller.isActive)
    assertTrue(actions.isEmpty())
    controller.enter(); controller.move(true)
    title.text = "Another group"
    controller.confirm()
    assertTrue(actions.isEmpty())
    assertEquals(R.string.braille_suggestions_unavailable, announcements.last().first)
  }

  @Test fun recycledRenamedRowCannotActivateADifferentPerson() {
    val row = row("Ana")
    controller.enter(); controller.move(true)
    row.contentDescription = "Luis"
    row.getChild(0).text = "Luis"
    controller.confirm()
    assertTrue(actions.isEmpty())
    assertFalse(controller.isActive)
  }

  @Test fun vanishedOrDisabledSelectionCannotActivate() {
    val row = row("Ana")
    controller.enter(); controller.move(true)
    row.isEnabled = false
    controller.confirm()
    assertTrue(actions.isEmpty())
    row.isEnabled = true
    controller.enter(); controller.move(true)
    shadowOf(row).setRefreshReturnValue(false)
    controller.confirm()
    assertTrue(actions.isEmpty())
  }

  @Test fun failedClickNeverFallsBackToAnotherNode() {
    row("Ana"); row("Luis")
    controller.enter(); controller.move(true)
    acceptAction = false
    controller.confirm()
    assertEquals(1, actions.size)
    assertEquals(R.string.braille_suggestions_failed, announcements.last().first)
  }

  @Test fun scrollWaitsForNewRowsAndAnnouncesTheNextPerson() {
    row("Ana"); row("Luis")
    controller.enter(); controller.move(true); controller.move(true); controller.move(true)
    assertEquals(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD, actions.single().second)
    row("Marta")
    scheduled.removeAt(0).invoke()
    assertEquals(listOf("Marta", 3, 3), announcements.last().second)
    assertEquals(1, actions.size)
  }

  @Test fun cancelInvalidatesDelayedScrollAndConfirmationCannotClickDuringScroll() {
    row("Ana")
    controller.enter(); controller.move(true); controller.move(true)
    controller.confirm()
    assertEquals(1, actions.size)
    controller.clear()
    val announcementsBefore = announcements.size
    row("Luis")
    scheduled.removeAt(0).invoke()
    assertEquals(announcementsBefore, announcements.size)
    assertFalse(controller.isActive)
  }

  @Test fun lockOrPausePreventsPendingScrollAndActivation() {
    row("Ana")
    controller.enter(); controller.move(true); controller.move(true)
    allowed = false
    scheduled.removeAt(0).invoke()
    assertFalse(controller.isActive)
    assertEquals(1, actions.size)
    assertFalse(controller.enter())
  }

  @Test fun scrollAtBoundaryStopsAfterBoundedRetries() {
    row("Ana")
    controller.enter(); controller.move(true); controller.move(true)
    repeat(3) { scheduled.removeAt(0).invoke() }
    assertTrue(scheduled.isEmpty())
    assertEquals(R.string.braille_suggestions_edge, announcements.last().first)
    controller.confirm()
    assertEquals(AccessibilityNodeInfoCompat.ACTION_CLICK, actions.last().second)
  }
}
