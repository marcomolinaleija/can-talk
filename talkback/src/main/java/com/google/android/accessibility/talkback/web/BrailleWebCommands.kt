/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.web

import android.content.Context
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.focusmanagement.NavigationTarget
import com.google.android.accessibility.talkback.remote.NvdaRemoteGesture

/** Uncontracted braille commands, independent of an editor or composing word. */
enum class BrailleWebTarget(val dots: String, val target: Int, val label: Int) {
  HEADING("125", NavigationTarget.TARGET_HEADING, R.string.braille_web_headings),
  LINK("13", NavigationTarget.TARGET_LINK, R.string.braille_web_links),
  LIST("123", NavigationTarget.TARGET_HTML_ELEMENT_LIST, R.string.braille_web_lists),
  ITEM("24", NavigationTarget.TARGET_HTML_ELEMENT_LIST_ITEM, R.string.braille_web_items),
  BUTTON("12", NavigationTarget.TARGET_HTML_ELEMENT_BUTTON, R.string.braille_web_buttons),
  EDIT("15", NavigationTarget.TARGET_HTML_ELEMENT_EDIT_FIELD, R.string.braille_web_edits),
  FORM("124", NavigationTarget.TARGET_CONTROL, R.string.braille_web_forms),
  TABLE("2345", NavigationTarget.TARGET_HTML_ELEMENT_TABLE, R.string.braille_web_tables),
  GRAPHIC("1245", NavigationTarget.TARGET_HTML_ELEMENT_GRAPHIC, R.string.braille_web_graphics),
  LANDMARK("145", NavigationTarget.TARGET_HTML_ELEMENT_ARIA_LANDMARK, R.string.braille_web_landmarks),
  CHECKBOX("1346", NavigationTarget.TARGET_HTML_ELEMENT_CHECKBOX, R.string.braille_web_checkboxes),
  COMBOBOX("14", NavigationTarget.TARGET_HTML_ELEMENT_COMBOBOX, R.string.braille_web_comboboxes),
  RADIO("1235", NavigationTarget.TARGET_HTML_ELEMENT_RADIO, R.string.braille_web_radios);

  companion object {
    fun fromDots(dots: String): BrailleWebTarget? = entries.firstOrNull { it.dots == dots }
  }
}

enum class BrailleWebAction(val label: Int) {
  PREVIOUS(R.string.braille_web_previous),
  NEXT(R.string.braille_web_next),
  PREVIOUS_ITEM(R.string.braille_web_previous_item),
  NEXT_ITEM(R.string.braille_web_next_item),
  ACTIVATE(R.string.braille_web_activate),
  MENU(R.string.braille_web_menu),
  EXIT(R.string.braille_web_exit)
}

class BrailleWebBindings(context: Context) {
  private val prefs = context.getSharedPreferences("braille_web_gestures_v1", Context.MODE_PRIVATE)

  fun resolve(gesture: NvdaRemoteGesture): BrailleWebAction? {
    // Keep an accessible menu and an exit even after a mistaken custom assignment.
    if (gesture.held.isEmpty() && gesture.fingers == 3 && gesture.direction == "UP")
      return BrailleWebAction.MENU
    if (gesture.held.isEmpty() && gesture.fingers == 2 && gesture.direction == "LEFT")
      return BrailleWebAction.EXIT
    val ids = listOf(gesture.id, gesture.copy(swipedDot = 0).id).distinct()
    for (id in ids) {
      prefs.getString(id, null)?.let { value ->
        return BrailleWebAction.entries.firstOrNull { it.name == value }
      }
    }
    if (gesture.held.isNotEmpty()) return null
    return when (gesture.fingers to gesture.direction) {
      1 to "UP" -> BrailleWebAction.PREVIOUS
      1 to "DOWN" -> BrailleWebAction.NEXT
      1 to "LEFT" -> BrailleWebAction.PREVIOUS_ITEM
      1 to "RIGHT" -> BrailleWebAction.NEXT_ITEM
      2 to "RIGHT" -> BrailleWebAction.ACTIVATE
      2 to "DOWN" -> BrailleWebAction.EXIT
      else -> null
    }
  }

  fun isReserved(gesture: NvdaRemoteGesture) = gesture.held.isEmpty() &&
    ((gesture.fingers == 3 && gesture.direction == "UP") ||
      (gesture.fingers == 2 && gesture.direction == "LEFT"))

  fun assign(gesture: NvdaRemoteGesture, action: BrailleWebAction) {
    prefs.edit().putString(gesture.id, action.name).apply()
  }

  fun reset() { prefs.edit().clear().apply() }
}
