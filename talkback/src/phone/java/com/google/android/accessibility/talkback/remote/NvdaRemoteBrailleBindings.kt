/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.remote

import android.content.Context
import com.google.android.accessibility.braille.common.BrailleImeAction
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.brailleime.BrailleImeGestureAction
import com.google.android.accessibility.brailleime.input.DotHoldSwipe
import com.google.android.accessibility.brailleime.input.Swipe
import com.google.android.accessibility.talkback.remote.NvdaRemoteBindings.Companion.key

/** Integrates the phone-only braille keyboard with NVDA Remote. */
internal class NvdaRemoteBrailleBindings(private val context: Context) {
  fun customAction(gesture: NvdaRemoteGesture): String? =
    findCustomBrailleAction(gesture)?.let { mapBrailleImeAction(it) }

  fun defaultAction(gesture: NvdaRemoteGesture): String? {
    val brailleGesture = toBrailleGesture(gesture) ?: return null
    return runCatching {
      BrailleImeGestureAction.getAction(context, brailleGesture).firstOrNull()?.let { mapBrailleImeAction(it) }
    }.getOrNull()
  }

  fun gestureIds(): List<String> = runCatching {
    BrailleUserPreferences.readGestureActionMap(context).values.flatten()
  }.getOrDefault(emptyList())

  fun toBrailleGesture(gesture: NvdaRemoteGesture): com.google.android.accessibility.brailleime.input.Gesture? {
    if (gesture.direction.isEmpty()) return null
    val direction = runCatching {
      Swipe.Direction.valueOf(gesture.direction)
    }.getOrNull() ?: return null
    val swipe = if (gesture.swipedDot in 1..8) {
      Swipe(direction, gesture.fingers, gesture.swipedDot)
    } else {
      Swipe(direction, gesture.fingers)
    }
    return if (gesture.held.isNotEmpty() && gesture.held.all { it in '1'..'8' }) {
      DotHoldSwipe(swipe, BrailleCharacter(gesture.held))
    } else swipe
  }

  fun findCustomBrailleAction(gesture: NvdaRemoteGesture): BrailleImeAction? {
    val brailleGesture = toBrailleGesture(gesture) ?: return null
    val customMap = runCatching { BrailleUserPreferences.readGestureActionMap(context) }.getOrNull()
    if (!customMap.isNullOrEmpty()) {
      val targetId = brailleGesture.id
      for ((action, ids) in customMap) {
        if (ids != null && ids.contains(targetId)) {
          return action
        }
      }
      val matchedActions = runCatching {
        BrailleImeGestureAction.getAction(context, brailleGesture)
      }.getOrNull()
      if (!matchedActions.isNullOrEmpty()) {
        for (action in matchedActions) {
          if (customMap.containsKey(action)) {
            return action
          }
        }
      }
    }
    return null
  }

  fun mapBrailleImeAction(action: BrailleImeAction): String? = when (action) {
    BrailleImeAction.TOGGLE_EMOJI_MODE -> "emoji"
    BrailleImeAction.DELETE_WORD, BrailleImeAction.UNDO_SPELLING_SUGGESTION -> key(0x08, listOf("ctrl"))
    BrailleImeAction.DELETE_CHARACTER_OR_PREVIOUS_ITEM -> "braille_erase"
    BrailleImeAction.ADD_SPACE_OR_NEXT_ITEM, BrailleImeAction.HEAR_NEXT_SPELLING_SUGGESTION -> "braille_space"
    BrailleImeAction.ADD_NEWLINE, BrailleImeAction.SUBMIT_TEXT, BrailleImeAction.CONFIRM_SPELLING_SUGGESTION -> "braille_enter"
    BrailleImeAction.MOVE_CURSOR_BACKWARD, BrailleImeAction.PREVIOUS_CHARACTER -> key(0x25)
    BrailleImeAction.MOVE_CURSOR_FORWARD, BrailleImeAction.NEXT_CHARACTER -> key(0x27)
    BrailleImeAction.PREVIOUS_WORD, BrailleImeAction.PREVIOUS_MISSPELLED_WORD -> key(0x25, listOf("ctrl"))
    BrailleImeAction.NEXT_WORD, BrailleImeAction.NEXT_MISSPELLED_WORD -> key(0x27, listOf("ctrl"))
    BrailleImeAction.PREVIOUS_LINE -> key(0x26)
    BrailleImeAction.NEXT_LINE -> key(0x28)
    BrailleImeAction.BEGINNING_OF_PAGE -> key(0x24, listOf("ctrl"))
    BrailleImeAction.END_OF_PAGE -> key(0x23, listOf("ctrl"))
    BrailleImeAction.SELECT_PREVIOUS_CHARACTER -> key(0x25, listOf("shift"))
    BrailleImeAction.SELECT_NEXT_CHARACTER -> key(0x27, listOf("shift"))
    BrailleImeAction.SELECT_PREVIOUS_WORD -> key(0x25, listOf("ctrl", "shift"))
    BrailleImeAction.SELECT_NEXT_WORD -> key(0x27, listOf("ctrl", "shift"))
    BrailleImeAction.SELECT_PREVIOUS_LINE -> key(0x26, listOf("shift"))
    BrailleImeAction.SELECT_NEXT_LINE -> key(0x28, listOf("shift"))
    BrailleImeAction.SELECT_ALL -> key(0x41, listOf("ctrl"))
    BrailleImeAction.SELECT_CURRENT_TO_START -> key(0x24, listOf("shift"))
    BrailleImeAction.SELECT_CURRENT_TO_END -> key(0x23, listOf("shift"))
    BrailleImeAction.COPY -> key(0x43, listOf("ctrl"))
    BrailleImeAction.CUT -> key(0x58, listOf("ctrl"))
    BrailleImeAction.PASTE -> key(0x56, listOf("ctrl"))
    BrailleImeAction.HIDE_KEYBOARD -> "exit"
    BrailleImeAction.HELP_AND_OTHER_ACTIONS -> "menu"
    BrailleImeAction.SWITCH_KEYBOARD, BrailleImeAction.NEXT_KEYBOARD -> "mode"
    BrailleImeAction.PREVIOUS_GRANULARITY, BrailleImeAction.HEAR_PREVIOUS_SPELLING_SUGGESTION -> key(0x09, listOf("shift"))
    BrailleImeAction.NEXT_GRANULARITY -> key(0x09)
    else -> null
  }

}
