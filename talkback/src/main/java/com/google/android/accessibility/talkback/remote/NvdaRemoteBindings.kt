/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.remote

import android.content.Context
import com.google.android.accessibility.braille.common.BrailleImeAction
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.brailleime.BrailleImeGestureAction
import com.google.android.accessibility.brailleime.input.DotHoldSwipe
import com.google.android.accessibility.brailleime.input.Swipe
import com.google.android.accessibility.talkback.R

enum class NvdaRemoteMode(val label: Int) {
  CONTROL(R.string.nvda_remote_mode_control), KEYBOARD(R.string.nvda_remote_mode_keyboard)
}

/** Physical gestures have stable IDs independent of language, orientation and assigned action. */
data class NvdaRemoteGesture(
  val dots: String? = null,
  val direction: String = "",
  val fingers: Int = 1,
  val swipedDot: Int = 0,
  val held: String = "",
  val holdOnly: Boolean = false,
) {
  val id: String get() = when {
    holdOnly -> "hold_$held"
    dots != null -> "tap_$dots"
    else -> "swipe_${held}_${fingers}_${swipedDot}_$direction"
  }
  fun label(context: Context): String {
    if (holdOnly) return context.getString(R.string.nvda_remote_hold_dots,
      held.toCharArray().joinToString(" "))
    if (dots != null) return context.getString(R.string.nvda_remote_dots, dots.toCharArray().joinToString(" "))
    val name = context.getString(when (direction) {
      "LEFT" -> R.string.nvda_remote_direction_left
      "RIGHT" -> R.string.nvda_remote_direction_right
      "UP" -> R.string.nvda_remote_direction_up
      else -> R.string.nvda_remote_direction_down
    })
    val swipe = if (swipedDot > 0) context.getString(R.string.nvda_remote_dot_swipe, swipedDot, name)
      else context.getString(R.string.nvda_remote_swipe, fingers, name)
    return if (held.isEmpty()) swipe else context.getString(R.string.nvda_remote_hold_swipe,
      held.toCharArray().joinToString(" "), swipe)
  }
  companion object {
    fun parse(id: String): NvdaRemoteGesture? {
      if (id.startsWith("hold_")) {
        val held = id.removePrefix("hold_")
        return if (held.isNotEmpty() && held.all { it in '1'..'8' })
          NvdaRemoteGesture(held = held, holdOnly = true) else null
      }
      if (id.startsWith("tap_")) {
        val dots = id.removePrefix("tap_")
        return if (dots.isNotEmpty() && dots.all { it in '1'..'8' }) NvdaRemoteGesture(dots = dots) else null
      }
      val parts = id.split('_')
      if (parts.size != 5 || parts[0] != "swipe" || parts[1].any { it !in '1'..'8' } ||
          parts[4] !in listOf("LEFT", "RIGHT", "UP", "DOWN")) return null
      val fingers = parts[2].toIntOrNull() ?: return null
      val dot = parts[3].toIntOrNull() ?: return null
      return if (fingers in 1..3 && dot in 0..8) NvdaRemoteGesture(direction = parts[4],
        fingers = fingers, swipedDot = dot, held = parts[1]) else null
    }
  }
}

class NvdaRemoteBindings(private val context: Context) {
  private val prefs = preferences(context)
  private fun preference(mode: NvdaRemoteMode, gesture: NvdaRemoteGesture) = "${mode.name}/${gesture.id}"

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

  fun resolve(mode: NvdaRemoteMode, gesture: NvdaRemoteGesture): String {
    if (gesture.holdOnly) {
      val key = preference(mode, gesture)
      if (prefs.contains(key)) return prefs.getString(key, "none") ?: "none"
      // Follow the user's dot assignment instead of hardcoding Alt to a physical location.
      return resolve(mode, NvdaRemoteGesture(dots = gesture.held)).takeIf { it in modifierActions } ?: "none"
    }
    val candidates = if (gesture.dots == null && gesture.swipedDot > 0)
      listOf(gesture, gesture.copy(swipedDot = 0)) else listOf(gesture)
    for (candidate in candidates) {
      val key = preference(mode, candidate)
      if (prefs.contains(key)) return prefs.getString(key, "none") ?: "none"
    }

    if (mode == NvdaRemoteMode.KEYBOARD) {
      for (candidate in candidates) {
        val customAction = findCustomBrailleAction(candidate)?.let { mapBrailleImeAction(it) }
        if (customAction != null) return customAction
      }
    }

    for (candidate in candidates) {
      defaults(mode)[candidate.id]?.let { return it }
    }

    if (mode == NvdaRemoteMode.KEYBOARD) {
      for (candidate in candidates) {
        val brailleGesture = toBrailleGesture(candidate)
        if (brailleGesture != null) {
          val defaultAction = runCatching {
            BrailleImeGestureAction.getAction(context, brailleGesture).firstOrNull()?.let { mapBrailleImeAction(it) }
          }.getOrNull()
          if (defaultAction != null) return defaultAction
        }
      }
    }

    if (gesture.held.isNotEmpty()) {
      val navigation = navigationHeldDots(mode, gesture.held)
      if (navigation != gesture.held) return resolve(mode, gesture.copy(held = navigation))
    }
    if (gesture.dots != null) {
      if (mode == NvdaRemoteMode.KEYBOARD) return "braille"
      letterKey(gesture.dots)?.let { return it }
    }
    return "none"
  }

  fun heldModifiers(mode: NvdaRemoteMode, dots: String): List<String> {
    val chord = NvdaRemoteGesture(held = dots, holdOnly = true)
    if (prefs.contains(preference(mode, chord))) return listOf(resolve(mode, chord)).filter { it in modifierActions }
    return dots.map { resolve(mode, NvdaRemoteGesture(held = it.toString(), holdOnly = true)) }
      .filter { it in modifierActions }.distinct()
  }

  fun navigationHeldDots(mode: NvdaRemoteMode, dots: String): String {
    val chord = NvdaRemoteGesture(held = dots, holdOnly = true)
    if (prefs.contains(preference(mode, chord))) return if (resolve(mode, chord) in modifierActions) "" else dots
    return dots.filter { resolve(mode, NvdaRemoteGesture(held = it.toString(), holdOnly = true)) !in modifierActions }
  }

  fun assign(mode: NvdaRemoteMode, gesture: NvdaRemoteGesture, action: String) {
    prefs.edit().putString(preference(mode, gesture), action).apply()
  }
  fun restore(mode: NvdaRemoteMode) {
    val edit = prefs.edit()
    prefs.all.keys.filter { it.startsWith("${mode.name}/") }.forEach { edit.remove(it) }
    edit.apply()
  }
  fun gestures(mode: NvdaRemoteMode): List<NvdaRemoteGesture> {
    val customKeys = if (mode == NvdaRemoteMode.KEYBOARD) {
      runCatching {
        BrailleUserPreferences.readGestureActionMap(context).values.flatten()
      }.getOrDefault(emptyList()).mapNotNull { id ->
        if (id.startsWith("hold_dot")) {
          val parts = id.removePrefix("hold_dot").split('_')
          if (parts.size == 4 && parts[1] == "swipe") {
            val held = parts[0]
            val dir = parts[2].uppercase()
            val fingers = parts[3].toIntOrNull() ?: 1
            NvdaRemoteGesture(direction = dir, fingers = fingers, held = held).id
          } else null
        } else if (id.startsWith("swipe_")) {
          val parts = id.split('_')
          if (parts.size == 3) {
            val dir = parts[1].uppercase()
            val fingers = parts[2].toIntOrNull() ?: 1
            NvdaRemoteGesture(direction = dir, fingers = fingers).id
          } else if (parts.size == 4 && parts[1].startsWith("dot")) {
            val dot = parts[1].removePrefix("dot").toIntOrNull() ?: 0
            val dir = parts[2].uppercase()
            val fingers = parts[3].toIntOrNull() ?: 1
            NvdaRemoteGesture(direction = dir, fingers = fingers, swipedDot = dot).id
          } else null
        } else null
      }
    } else emptyList()
    val ids = defaults(mode).keys + customKeys + prefs.all.keys.filter { it.startsWith("${mode.name}/") }
      .map { it.substringAfter('/') }
    return ids.distinct().mapNotNull { NvdaRemoteGesture.parse(it) }
  }

  companion object {
    val modifierActions = setOf("ctrl", "alt", "shift", "nvda", "windows")
    private val LETTERS = listOf("1", "12", "14", "145", "15", "124", "1245", "125", "24", "245",
      "13", "123", "134", "1345", "135", "1234", "12345", "1235", "234", "2345", "136", "1236",
      "2456", "1346", "13456", "1356").mapIndexed { index, dots -> dots to ('a' + index) }.toMap()

    fun letterKey(dots: String): String? = LETTERS[dots]?.let { key(it.code - 'a'.code + 0x41) }

    fun key(code: Int, modifiers: List<String> = emptyList()) = "key:$code:${modifiers.distinct().joinToString(",")}"
    fun keyCode(action: String): Int? = action.split(':').takeIf { it.size == 3 && it[0] == "key" }
      ?.get(1)?.toIntOrNull()?.takeIf { it in 1..255 }
    fun modifiers(action: String): List<String> = action.substringAfterLast(':').split(',')
      .filter { it in listOf("ctrl", "alt", "shift", "nvda", "windows") }.distinct()

    fun defaults(mode: NvdaRemoteMode): Map<String, String> = buildMap {
      fun swipe(fingers: Int, direction: String, action: String, dot: Int = 0, held: String = "") {
        put(NvdaRemoteGesture(direction = direction, fingers = fingers, swipedDot = dot, held = held).id, action)
      }
      swipe(3, "DOWN", "mode")
      swipe(3, "UP", "menu")
      swipe(2, "DOWN", "exit")
      swipe(1, "RIGHT", if (mode == NvdaRemoteMode.KEYBOARD) "braille_space" else key(0x20))
      swipe(1, "LEFT", if (mode == NvdaRemoteMode.KEYBOARD) "braille_erase" else key(0x08))
      swipe(2, "RIGHT", if (mode == NvdaRemoteMode.KEYBOARD) "braille_enter" else key(0x0D))
      swipe(2, "LEFT", if (mode == NvdaRemoteMode.KEYBOARD) key(0x08, listOf("ctrl")) else key(0x1B))
      swipe(2, "UP", if (mode == NvdaRemoteMode.KEYBOARD) "braille_translate" else key(0x11))
      for ((direction, code) in listOf("LEFT" to 0x25, "UP" to 0x26, "RIGHT" to 0x27, "DOWN" to 0x28)) {
        swipe(1, direction, key(code), dot = 4, held = "1")
      }
      if (mode == NvdaRemoteMode.CONTROL) {
        for ((dot, action) in listOf("2" to "nvda", "3" to "shift", "5" to "alt", "6" to "ctrl")) {
          put(NvdaRemoteGesture(dots = dot).id, action)
          put(NvdaRemoteGesture(held = dot, holdOnly = true).id, action)
        }
        swipe(1, "RIGHT", key(0x09), dot = 4)
        swipe(1, "LEFT", key(0x09, listOf("shift")), dot = 4)
        swipe(1, "UP", key(0x21), dot = 4)
        swipe(1, "DOWN", key(0x22), dot = 4)
      } else {
        swipe(1, "UP", key(0x25))
        swipe(1, "DOWN", key(0x27))
        swipe(3, "RIGHT", key(0x09))
        swipe(3, "LEFT", key(0x09, listOf("shift")))
        put("tap_7", "braille_erase")
        put("tap_8", "braille_enter")
        put("tap_78", "braille_translate")
      }
    }

    val localActions = listOf("none", "mode", "menu", "exit", "learn", "clear", "toggle_remote_speech", "ctrl", "alt", "shift",
      "nvda", "windows", "braille", "braille_space", "braille_erase", "braille_enter", "braille_translate", "emoji")
    val keys = listOf(0x09, 0x0D, 0x1B, 0x08, 0x2E, 0x20, 0x25, 0x26, 0x27, 0x28,
      0x24, 0x23, 0x21, 0x22, 0x2D, 0x14, 0x11, 0x5B, 0xBE) + (0x70..0x7B) + (0x41..0x5A) + (0x30..0x39)

    fun actionLabel(context: Context, action: String): String {
      val resource = when (action) {
        "mode" -> R.string.nvda_remote_switch_mode
        "menu" -> R.string.nvda_remote_commands
        "exit" -> R.string.nvda_remote_exit_surface
        "learn" -> R.string.nvda_remote_learn
        "clear" -> R.string.nvda_remote_clear_modifiers
        "toggle_remote_speech" -> R.string.nvda_remote_toggle_speech
        "ctrl" -> R.string.nvda_remote_arm_ctrl
        "alt" -> R.string.nvda_remote_arm_alt
        "shift" -> R.string.nvda_remote_arm_shift
        "nvda" -> R.string.nvda_remote_arm_nvda
        "windows" -> R.string.nvda_remote_arm_windows
        "braille" -> R.string.nvda_remote_braille_input
        "braille_space" -> R.string.nvda_remote_braille_space
        "braille_erase" -> R.string.nvda_remote_braille_erase
        "braille_enter" -> R.string.nvda_remote_braille_enter
        "braille_translate" -> R.string.nvda_remote_braille_translate
        "emoji" -> R.string.nvda_remote_emoji
        else -> R.string.nvda_remote_no_action
      }
      val code = keyCode(action) ?: return context.getString(resource)
      val keyResource = when (code) {
        0x09 -> R.string.nvda_remote_tab; 0x0D -> R.string.nvda_remote_enter
        0x1B -> R.string.nvda_remote_escape; 0x08 -> R.string.nvda_remote_backspace
        0x2E -> R.string.nvda_remote_delete; 0x25 -> R.string.nvda_remote_left
        0x26 -> R.string.nvda_remote_up; 0x27 -> R.string.nvda_remote_right
        0x28 -> R.string.nvda_remote_down; 0x20 -> R.string.nvda_remote_space
        0x24 -> R.string.nvda_remote_home; 0x23 -> R.string.nvda_remote_end
        0x21 -> R.string.nvda_remote_page_up; 0x22 -> R.string.nvda_remote_page_down
        0x2D -> R.string.nvda_remote_insert; 0x14 -> R.string.nvda_remote_caps
        0x11 -> R.string.nvda_remote_stop; 0x5B -> R.string.nvda_remote_windows
        0xBE -> R.string.nvda_remote_period
        else -> 0
      }
      val name = when {
        keyResource != 0 -> context.getString(keyResource)
        code in 0x70..0x7B -> "F${code - 0x70 + 1}"
        code in 0x30..0x39 || code in 0x41..0x5A -> code.toChar().toString()
        else -> code.toString()
      }
      val prefix = modifiers(action).joinToString(" + ") { modifierLabel(it) }
      return if (prefix.isEmpty()) name else "$prefix + $name"
    }
    fun modifierLabel(modifier: String): String = when (modifier) {
      "ctrl" -> "Control"; "alt" -> "Alt"; "shift" -> "Shift"; "nvda" -> "NVDA"; else -> "Windows"
    }
    const val PREFS_NAME = "nvda_remote_gestures_v1"
    fun preferences(context: Context): android.content.SharedPreferences =
      context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  }
}
