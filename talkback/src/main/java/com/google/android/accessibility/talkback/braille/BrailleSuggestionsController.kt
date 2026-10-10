/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.braille

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.pause.PauseController
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.monitor.ScreenMonitor
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions

/** App-owned suggestions; selection never moves input focus or submits the editor. */
class BrailleSuggestionsController(
  private val inputNode: () -> AccessibilityNodeInfoCompat?,
  private val rootForWindow: (Int) -> AccessibilityNodeInfoCompat?,
  private val canInteract: () -> Boolean,
  private val act: (AccessibilityNodeInfoCompat, Int) -> Boolean,
  private val speak: (Int, Array<out Any>) -> Unit,
  private val schedule: (() -> Unit) -> Unit,
) {
  private data class EditorKey(val node: AccessibilityNodeInfoCompat, val window: Int,
    val title: String, val text: String, val start: Int, val end: Int)
  private data class Candidate(val node: AccessibilityNodeInfoCompat, val label: String,
    val description: String)
  private data class Snapshot(val key: EditorKey, val container: AccessibilityNodeInfoCompat,
    val list: AccessibilityNodeInfoCompat, val rows: List<Candidate>)

  var isActive = false
    private set
  private var editorKey: EditorKey? = null
  private var originContainer: AccessibilityNodeInfoCompat? = null
  private var originList: AccessibilityNodeInfoCompat? = null
  private var selected: Candidate? = null
  private var generation = 0
  private var scrolling = false

  fun isAvailable(): Boolean = snapshot() != null

  fun enter(): Boolean {
    val current = snapshot() ?: return false
    clear()
    editorKey = current.key
    originContainer = current.container
    originList = current.list
    isActive = true
    say(R.string.braille_suggestions_control)
    return true
  }

  fun clear() {
    generation++
    scrolling = false
    isActive = false
    editorKey = null
    originContainer = null
    originList = null
    selected = null
  }

  fun move(forward: Boolean): Boolean {
    if (!isActive) return false
    val current = currentSnapshot() ?: return unavailable()
    if (scrolling) return true
    val old = selected
    val index = current.rows.indexOfFirst { sameCandidate(it, old) }
    val next = if (old == null || index < 0) {
      if (forward) 0 else current.rows.lastIndex
    } else index + if (forward) 1 else -1
    if (next in current.rows.indices) {
      announce(current, next)
      return true
    }
    val action = if (forward) AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD
      else AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD
    if (!current.list.isScrollable || !act(current.list, action)) {
      say(R.string.braille_suggestions_edge)
      return true
    }
    scrolling = true
    val token = ++generation
    // RecyclerView scrolls asynchronously. Never click or retain a recycled row by position.
    afterScroll(current, old, forward, token, 0)
    return true
  }

  private fun afterScroll(before: Snapshot, anchor: Candidate?, forward: Boolean,
    token: Int, attempt: Int) {
    schedule {
      if (token != generation || !isActive) return@schedule
      val current = currentSnapshot() ?: run { unavailable(); return@schedule }
      if (current.container != before.container || current.list != before.list) {
        unavailable(); return@schedule
      }
      val index = current.rows.indexOfFirst { sameCandidate(it, anchor) }
      val next = if (index < 0) {
        if (forward) 0 else current.rows.lastIndex
      } else index + if (forward) 1 else -1
      if (next in current.rows.indices) {
        scrolling = false
        announce(current, next)
      } else if (attempt < 2) {
        afterScroll(before, anchor, forward, token, attempt + 1)
      } else {
        scrolling = false
        say(R.string.braille_suggestions_edge)
      }
    }
  }

  fun confirm(): Boolean {
    if (!isActive) return false
    val current = currentSnapshot() ?: return unavailable()
    if (scrolling) return true
    val chosen = selected ?: run { say(R.string.braille_suggestions_choose); return true }
    val row = current.rows.firstOrNull { sameCandidate(it, chosen) }
      ?: return unavailable()
    if (!row.node.refresh() || !usable(row.node) || !row.node.isClickable ||
        row.node.windowId != current.key.window ||
        row.node.contentDescription?.toString().orEmpty() != chosen.description ||
        primaryName(row.node) != chosen.label) return unavailable()
    // ACTION_CLICK invokes WhatsApp's mention insertion; no manual text or IME_ACTION_SEND.
    val accepted = act(row.node, AccessibilityNodeInfoCompat.ACTION_CLICK)
    clear()
    say(if (accepted) R.string.braille_suggestions_inserted else R.string.braille_suggestions_failed)
    return true
  }

  private fun currentSnapshot(): Snapshot? {
    val current = snapshot() ?: return null
    return current.takeIf { it.key == editorKey && it.container == originContainer && it.list == originList }
  }

  private fun unavailable(): Boolean {
    clear()
    say(R.string.braille_suggestions_unavailable)
    return true // Consume the gesture even after disappearance; never insert a newline instead.
  }

  private fun announce(current: Snapshot, index: Int) {
    selected = current.rows[index]
    say(R.string.braille_suggestions_result, current.rows[index].label, index + 1, current.rows.size)
  }

  private fun say(resource: Int, vararg args: Any) = speak(resource, args)
  private fun sameCandidate(a: Candidate, b: Candidate?) = b != null &&
    a.node == b.node && a.label == b.label && a.description == b.description

  private fun usable(node: AccessibilityNodeInfoCompat) =
    node.isVisibleToUser && node.isEnabled && !node.isPassword && node.packageName == PACKAGE

  private fun snapshot(): Snapshot? {
    if (!canInteract()) return null
    val editor = inputNode() ?: return null
    if (!editor.refresh() || !usable(editor) || !editor.isEditable || !editor.isFocused ||
        editor.viewIdResourceName != ENTRY) return null
    val draft = editor.text?.toString().orEmpty()
    val caret = editor.textSelectionEnd
    if (caret !in 0..draft.length || !MENTION_QUERY.containsMatchIn(draft.substring(0, caret))) return null
    val root = rootForWindow(editor.windowId) ?: return null
    if (root.packageName != PACKAGE) return null
    val container = find(root, CONTAINER)
    if (container != null && !usable(container)) return null
    // Android omits WhatsApp's non-important mention_attach FrameLayout from TalkBack's
    // tree. Recognize the remaining list by its rows and the active @ query; never enable
    // unimportant views globally or accept an ambiguous list elsewhere in the window.
    val candidates = findAll(container ?: root, LIST).filter {
      usable(it) && it.windowId == editor.windowId && suggestionRows(it, editor.windowId).isNotEmpty()
    }
    val list = candidates.singleOrNull() ?: return null
    val rows = suggestionRows(list, editor.windowId)
    val title = find(root, TITLE)?.text?.toString().orEmpty()
    return Snapshot(EditorKey(editor, editor.windowId, title, draft,
      editor.textSelectionStart, editor.textSelectionEnd), container ?: list, list, rows)
  }

  private fun suggestionRows(list: AccessibilityNodeInfoCompat, window: Int): List<Candidate> =
    (0 until list.childCount).mapNotNull { i ->
      val row = list.getChild(i) ?: return@mapNotNull null
      if (!usable(row) || !row.isClickable || row.windowId != window) return@mapNotNull null
      val label = primaryName(row)
      if (label.isBlank()) null else Candidate(row, label, row.contentDescription?.toString().orEmpty())
    }

  private fun primaryName(row: AccessibilityNodeInfoCompat) =
    find(row, NAME)?.takeIf(::usable)?.text?.toString()?.trim().orEmpty()

  private fun find(root: AccessibilityNodeInfoCompat, id: String): AccessibilityNodeInfoCompat? =
    findAll(root, id).firstOrNull()

  private fun findAll(root: AccessibilityNodeInfoCompat, id: String): List<AccessibilityNodeInfoCompat> {
    val matches = ArrayList<AccessibilityNodeInfoCompat>()
    val stack = java.util.ArrayDeque<AccessibilityNodeInfoCompat>()
    val visited = HashSet<AccessibilityNodeInfoCompat>()
    stack.add(root)
    while (stack.isNotEmpty() && visited.size < 5000) {
      val node = stack.removeLast()
      if (!visited.add(node) || node.isPassword) continue
      if (node.viewIdResourceName == id && node.isVisibleToUser) matches.add(node)
      for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::add)
    }
    return matches
  }

  companion object {
    private const val PACKAGE = "com.whatsapp"
    private const val ENTRY = "$PACKAGE:id/entry"
    private const val CONTAINER = "$PACKAGE:id/mention_attach"
    private const val LIST = "$PACKAGE:id/list"
    private const val NAME = "$PACKAGE:id/primary_name"
    private const val TITLE = "$PACKAGE:id/conversation_contact_name"
    private val MENTION_QUERY = Regex("(?:^|\\s)@[^@\\r\\n]*\\z")

    @JvmStatic fun forService(service: TalkBackService, pipeline: Pipeline.FeedbackReturner): BrailleSuggestionsController {
      val handler = Handler(Looper.getMainLooper())
      return BrailleSuggestionsController(
        inputNode = { service.findFocus(FOCUS_INPUT)?.let(AccessibilityNodeInfoCompat::wrap) },
        rootForWindow = { id -> service.windows.firstOrNull { it.id == id }?.root
          ?.let(AccessibilityNodeInfoCompat::wrap) },
        canInteract = { TalkBackService.isServiceActive() && !PauseController.isPaused() &&
          !ScreenMonitor.isDeviceLocked(service) },
        act = { node, action -> pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.nodeAction(node, action)) },
        speak = { resource, args -> pipeline.returnFeedback(EVENT_ID_UNTRACKED,
          Feedback.speech(service.getString(resource, *args), SpeakOptions.create()
            .setQueueMode(SpeechController.QUEUE_MODE_INTERRUPT)
            .setFlags(FeedbackItem.FLAG_NO_HISTORY or FeedbackItem.FLAG_FORCE_FEEDBACK_ALL))) },
        schedule = { task -> handler.postDelayed({ task() }, 180) },
      )
    }
  }
}
