/*
 * Copyright 2026 CAN Talk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.macros

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.gesture.GestureShortcutMapping
import com.google.android.accessibility.talkback.gesture.OpenAppActions
import com.google.android.accessibility.talkback.preference.base.GestureListPreference
import com.google.android.accessibility.utils.SharedPreferencesUtils

/**
 * Adapter bridging SandFly's macro command lookups and gesture assignments with CAN Talk's
 * [GestureListPreference], [GestureShortcutMapping], and gesture preference keys.
 */
object MacroCommands {

  data class CommandOption(val label: String, val value: String)

  data class CommandCategory(val title: String, val commands: List<CommandOption>)

  /** Preference keys and titles of CAN Talk customizable touch/fingerprint gestures. */
  private val GESTURE_PREFS: List<Pair<Int, Int>> =
    listOf(
      R.string.pref_shortcut_up_key to R.string.title_pref_shortcut_up,
      R.string.pref_shortcut_down_key to R.string.title_pref_shortcut_down,
      R.string.pref_shortcut_left_key to R.string.title_pref_shortcut_left,
      R.string.pref_shortcut_right_key to R.string.title_pref_shortcut_right,
      R.string.pref_shortcut_1finger_1tap_hold_key to R.string.title_pref_shortcut_1finger_1tap_hold,
      R.string.pref_shortcut_up_and_hold_key to R.string.title_pref_shortcut_up_and_hold,
      R.string.pref_shortcut_down_and_hold_key to R.string.title_pref_shortcut_down_and_hold,
      R.string.pref_shortcut_left_and_hold_key to R.string.title_pref_shortcut_left_and_hold,
      R.string.pref_shortcut_right_and_hold_key to R.string.title_pref_shortcut_right_and_hold,
      R.string.pref_shortcut_up_and_down_key to R.string.title_pref_shortcut_up_and_down,
      R.string.pref_shortcut_down_and_up_key to R.string.title_pref_shortcut_down_and_up,
      R.string.pref_shortcut_left_and_right_key to R.string.title_pref_shortcut_left_and_right,
      R.string.pref_shortcut_right_and_left_key to R.string.title_pref_shortcut_right_and_left,
      R.string.pref_shortcut_up_and_left_key to R.string.title_pref_shortcut_up_and_left,
      R.string.pref_shortcut_up_and_right_key to R.string.title_pref_shortcut_up_and_right,
      R.string.pref_shortcut_down_and_left_key to R.string.title_pref_shortcut_down_and_left,
      R.string.pref_shortcut_down_and_right_key to R.string.title_pref_shortcut_down_and_right,
      R.string.pref_shortcut_left_and_up_key to R.string.title_pref_shortcut_left_and_up,
      R.string.pref_shortcut_left_and_down_key to R.string.title_pref_shortcut_left_and_down,
      R.string.pref_shortcut_right_and_up_key to R.string.title_pref_shortcut_right_and_up,
      R.string.pref_shortcut_right_and_down_key to R.string.title_pref_shortcut_right_and_down,
      R.string.pref_shortcut_2finger_1tap_key to R.string.title_pref_shortcut_2finger_1tap,
      R.string.pref_shortcut_2finger_1tap_hold_key to R.string.title_pref_shortcut_2finger_1tap_hold,
      R.string.pref_shortcut_2finger_2tap_key to R.string.title_pref_shortcut_2finger_2tap,
      R.string.pref_shortcut_2finger_2tap_hold_key to R.string.title_pref_shortcut_2finger_2tap_hold,
      R.string.pref_shortcut_2finger_3tap_key to R.string.title_pref_shortcut_2finger_3tap,
      R.string.pref_shortcut_2finger_3tap_hold_key to R.string.title_pref_shortcut_2finger_3tap_hold,
      R.string.pref_shortcut_2finger_swipe_up_key to R.string.title_pref_shortcut_2finger_swipe_up,
      R.string.pref_shortcut_2finger_swipe_down_key to R.string.title_pref_shortcut_2finger_swipe_down,
      R.string.pref_shortcut_2finger_swipe_left_key to R.string.title_pref_shortcut_2finger_swipe_left,
      R.string.pref_shortcut_2finger_swipe_right_key to R.string.title_pref_shortcut_2finger_swipe_right,
      R.string.pref_shortcut_2finger_rotate_clockwise_key to
        R.string.title_pref_shortcut_2finger_rotate_clockwise,
      R.string.pref_shortcut_2finger_rotate_counterclockwise_key to
        R.string.title_pref_shortcut_2finger_rotate_counterclockwise,
      R.string.pref_shortcut_3finger_1tap_key to R.string.title_pref_shortcut_3finger_1tap,
      R.string.pref_shortcut_3finger_1tap_hold_key to R.string.title_pref_shortcut_3finger_1tap_hold,
      R.string.pref_shortcut_3finger_2tap_key to R.string.title_pref_shortcut_3finger_2tap,
      R.string.pref_shortcut_3finger_2tap_hold_key to R.string.title_pref_shortcut_3finger_2tap_hold,
      R.string.pref_shortcut_3finger_3tap_key to R.string.title_pref_shortcut_3finger_3tap,
      R.string.pref_shortcut_3finger_3tap_hold_key to R.string.title_pref_shortcut_3finger_3tap_hold,
      R.string.pref_shortcut_3finger_4tap_key to R.string.title_pref_shortcut_3finger_4tap,
      R.string.pref_shortcut_3finger_swipe_up_key to R.string.title_pref_shortcut_3finger_swipe_up,
      R.string.pref_shortcut_3finger_swipe_down_key to R.string.title_pref_shortcut_3finger_swipe_down,
      R.string.pref_shortcut_3finger_swipe_left_key to R.string.title_pref_shortcut_3finger_swipe_left,
      R.string.pref_shortcut_3finger_swipe_right_key to R.string.title_pref_shortcut_3finger_swipe_right,
      R.string.pref_shortcut_4finger_1tap_key to R.string.title_pref_shortcut_4finger_1tap,
      R.string.pref_shortcut_4finger_1tap_hold_key to R.string.title_pref_shortcut_4finger_1tap_hold,
      R.string.pref_shortcut_4finger_2tap_key to R.string.title_pref_shortcut_4finger_2tap,
      R.string.pref_shortcut_4finger_2tap_hold_key to R.string.title_pref_shortcut_4finger_2tap_hold,
      R.string.pref_shortcut_4finger_3tap_key to R.string.title_pref_shortcut_4finger_3tap,
      R.string.pref_shortcut_4finger_swipe_up_key to R.string.title_pref_shortcut_4finger_swipe_up,
      R.string.pref_shortcut_4finger_swipe_down_key to R.string.title_pref_shortcut_4finger_swipe_down,
      R.string.pref_shortcut_4finger_swipe_left_key to R.string.title_pref_shortcut_4finger_swipe_left,
      R.string.pref_shortcut_4finger_swipe_right_key to R.string.title_pref_shortcut_4finger_swipe_right,
      R.string.pref_shortcut_fingerprint_up_key to R.string.title_pref_shortcut_fingerprint_up,
      R.string.pref_shortcut_fingerprint_down_key to R.string.title_pref_shortcut_fingerprint_down,
      R.string.pref_shortcut_fingerprint_left_key to R.string.title_pref_shortcut_fingerprint_left,
      R.string.pref_shortcut_fingerprint_right_key to R.string.title_pref_shortcut_fingerprint_right,
    )

  /** Command categories exposed by CAN Talk, grouped the same way as [GestureListPreference]. */
  fun categories(context: Context): List<CommandCategory> {
    val result = ArrayList<CommandCategory>()
    var currentTitle: String? = null
    var currentCommands = ArrayList<CommandOption>()
    for (item in GestureListPreference.collectActionItems(context)) {
      if (item.isTitle) {
        if (currentTitle != null && currentCommands.isNotEmpty()) {
          result += CommandCategory(currentTitle, currentCommands)
        }
        currentTitle = item.text
        currentCommands = ArrayList()
      } else if (currentTitle != null && item.value.isNotEmpty()) {
        currentCommands += CommandOption(item.text, item.value)
      }
    }
    if (currentTitle != null && currentCommands.isNotEmpty()) {
      result += CommandCategory(currentTitle, currentCommands)
    }
    return result
  }

  /** Human-readable label for a command [value], or null when unavailable. */
  fun label(context: Context, value: String?): String? {
    if (value.isNullOrEmpty()) return null
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    Macros.label(context, prefs, value)?.let { return it }
    OpenAppActions.label(context, value)?.let { return it }
    val unassigned = context.getString(R.string.shortcut_unassigned)
    val unassignedValue = context.getString(R.string.shortcut_value_unassigned)
    if (value == unassignedValue) return unassigned
    val resolved = GestureShortcutMapping.getActionString(context, value)
    return resolved.takeIf { it != unassigned }
  }

  /** Resolves all command [values] to labels in a single pass. */
  fun labels(context: Context, values: List<String>): List<String?> =
    values.map { if (it.isEmpty()) null else label(context, it) }

  /** Clears [commandValue] from all gesture preferences that point to it. */
  @JvmStatic
  fun unassign(context: Context, prefs: SharedPreferences, commandValue: String) {
    val unassigned = context.getString(R.string.shortcut_value_unassigned)
    prefs.edit {
      for ((keyRes, _) in GESTURE_PREFS) {
        val baseKey = context.getString(keyRes)
        if (prefs.getString(baseKey, null) == commandValue) {
          putString(baseKey, unassigned)
        }
        for (setIndex in 1..2) {
          val setKey = "$baseKey-$setIndex"
          if (prefs.getString(setKey, null) == commandValue) {
            putString(setKey, unassigned)
          }
        }
      }
    }
  }

  /** Returns the human-readable names of gestures currently bound to [commandValue]. */
  fun assignedGestureLabels(
    context: Context,
    prefs: SharedPreferences,
    commandValue: String,
  ): List<String> =
    GESTURE_PREFS.mapNotNull { (keyRes, titleRes) ->
      val key = activeGesturePrefKey(context, prefs, keyRes)
      if (prefs.getString(key, null) == commandValue) context.getString(titleRes) else null
    }

  /** Summary line for the Gestures preference row inside [MacroFragment]. */
  fun assignedSummary(
    context: Context,
    prefs: SharedPreferences,
    commandValue: String,
  ): String {
    val assigned = assignedGestureLabels(context, prefs, commandValue)
    return if (assigned.isEmpty()) {
      context.getString(R.string.macro_gestures_none)
    } else {
      assigned.joinToString(", ")
    }
  }

  /** Opens a dialog allowing the user to assign or unassign a gesture for [commandValue]. */
  fun showAssignGestureDialog(
    context: Context,
    prefs: SharedPreferences,
    commandValue: String,
    macroName: CharSequence,
    onChanged: () -> Unit,
  ) {
    val labels =
      GESTURE_PREFS.map { (keyRes, titleRes) ->
        val key = activeGesturePrefKey(context, prefs, keyRes)
        val assigned = prefs.getString(key, null) == commandValue
        val baseTitle = context.getString(titleRes)
        if (assigned) "$baseTitle \u2713" else baseTitle
      }.toTypedArray()

    AlertDialog.Builder(context)
      .setTitle(context.getString(R.string.macro_assign_gesture) + ": $macroName")
      .setItems(labels) { _, index ->
        val (keyRes, _) = GESTURE_PREFS[index]
        val key = activeGesturePrefKey(context, prefs, keyRes)
        val unassigned = context.getString(R.string.shortcut_value_unassigned)
        val wasAssigned = prefs.getString(key, null) == commandValue
        prefs.edit {
          putString(key, if (wasAssigned) unassigned else commandValue)
        }
        onChanged()
      }
      .setNeutralButton(R.string.macro_remove_gesture) { _, _ ->
        unassign(context, prefs, commandValue)
        onChanged()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun activeGesturePrefKey(
    context: Context,
    prefs: SharedPreferences,
    baseKeyRes: Int,
  ): String {
    val baseKey = context.getString(baseKeyRes)
    val gestureSetEnabled =
      GestureShortcutMapping.isGestureSetEnabled(
        context,
        prefs,
        R.string.pref_multiple_gesture_set_key,
        R.bool.pref_multiple_gesture_set_default,
      )
    if (!gestureSetEnabled) {
      return baseKey
    }
    val setIndex =
      SharedPreferencesUtils.getIntFromStringPref(
        prefs,
        context.resources,
        R.string.pref_gesture_set_key,
        R.string.pref_gesture_set_value_default,
      )
    return GestureShortcutMapping.getPrefKeyWithGestureSet(baseKey, setIndex)
  }
}
