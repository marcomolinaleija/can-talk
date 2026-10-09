/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.appsearch

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo
import com.google.android.accessibility.talkback.labeling.TalkBackLabelManager
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED

/** Searches visible accessible labels, retaining the original node and its activation target. */
class ScreenElementSearch(
  private val rootForWindow: (Int) -> AccessibilityNodeInfoCompat?,
  private val labelForNode: (AccessibilityNodeInfoCompat) -> String,
  private val focusNode: (AccessibilityNodeInfoCompat) -> Boolean,
  private val actOnNode: (AccessibilityNodeInfoCompat, Int) -> Boolean,
) {
  data class Entry(val label: String, val node: AccessibilityNodeInfoCompat,
    val target: AccessibilityNodeInfoCompat?, val targetLabel: String, val windowId: Int)
  enum class Activation { ACTIVATED, FOCUSED, UNAVAILABLE, FAILED }
  private var entries = emptyList<Entry>()
  var matches = emptyList<Entry>()
    private set
  var index = 0
    private set
  val selected: Entry? get() = matches.getOrNull(index)

  fun load(windowId: Int?) {
    clear()
    if (windowId == null) return
    val root = rootForWindow(windowId) ?: return
    val stack = java.util.ArrayDeque<AccessibilityNodeInfoCompat>()
    val visited = HashSet<AccessibilityNodeInfoCompat>()
    val found = mutableListOf<Entry>()
    stack.add(root)
    while (stack.isNotEmpty() && visited.size < 5000) {
      val node = stack.removeLast()
      if (!visited.add(node) || node.isPassword) continue
      if (node.windowId == windowId && AccessibilityNodeInfoUtils.isVisible(node)) {
        val label = labelForNode(node).trim()
        if (label.isNotEmpty()) {
          val target = actionTarget(node, windowId)
          found.add(Entry(label, node, target, target?.let(labelForNode).orEmpty(), windowId))
        }
      }
      for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::add)
    }
    entries = found
  }

  fun search(query: String) {
    val needle = AppSearchResults.normalize(query)
    matches = if (needle.isEmpty()) emptyList() else entries.filter {
      AppSearchResults.normalize(it.label).contains(needle)
    }
    index = 0
  }

  fun move(step: Int) {
    if (matches.isNotEmpty()) index = Math.floorMod(index + step, matches.size)
  }

  fun clear() { entries = emptyList(); matches = emptyList(); index = 0 }

  fun activate(entry: Entry): Activation {
    val node = entry.node
    if (!node.refresh() || node.windowId != entry.windowId || node.isPassword ||
        !AccessibilityNodeInfoUtils.isVisible(node) || !node.isEnabled ||
        labelForNode(node).trim() != entry.label) return Activation.UNAVAILABLE
    val target = entry.target
    if (target == null) return if (focusNode(node)) Activation.FOCUSED else Activation.FAILED
    if (!target.refresh() || target.windowId != entry.windowId || target.isPassword ||
        !AccessibilityNodeInfoUtils.isVisible(target) || !target.isEnabled ||
        labelForNode(target) != entry.targetLabel || actionTarget(node, entry.windowId) != target)
      return Activation.UNAVAILABLE
    focusNode(target)
    val click = target.isClickable || supports(target, AccessibilityNodeInfoCompat.ACTION_CLICK)
    val action = if (click) AccessibilityNodeInfoCompat.ACTION_CLICK
      else AccessibilityNodeInfoCompat.ACTION_FOCUS
    return if (actOnNode(target, action)) Activation.ACTIVATED else Activation.FAILED
  }

  private fun actionTarget(start: AccessibilityNodeInfoCompat, windowId: Int): AccessibilityNodeInfoCompat? {
    var node: AccessibilityNodeInfoCompat? = start
    val visited = HashSet<AccessibilityNodeInfoCompat>()
    while (node != null && visited.size < 100 && visited.add(node)) {
      if (node.windowId != windowId || node.isPassword) return null
      if (node.isEditable || node.isClickable || supports(node, AccessibilityNodeInfoCompat.ACTION_CLICK))
        return node
      node = node.parent
    }
    return null
  }

  private fun supports(node: AccessibilityNodeInfoCompat, id: Int) = node.actionList.any { it.id == id }

  companion object {
    fun forService(service: TalkBackService, pipeline: Pipeline.FeedbackReturner) = ScreenElementSearch(
      rootForWindow = { id -> service.windows.firstOrNull { it.id == id }?.root
        ?.let(AccessibilityNodeInfoCompat::wrap) },
      labelForNode = { node -> TalkBackLabelManager.getNodeText(node, service.labelManager)
        ?.toString()?.takeIf { it.isNotBlank() } ?: node.hintText?.toString().orEmpty() },
      focusNode = { node -> pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.focus(node,
        FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build())
        .setForceRefocus(true)) },
      actOnNode = { node, action -> pipeline.returnFeedback(EVENT_ID_UNTRACKED,
        Feedback.nodeAction(node, action)) },
    )
  }
}
