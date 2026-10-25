/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.remote

import android.content.Context
import android.os.Bundle
import com.google.android.accessibility.talkback.R

/** Local desktop state. There is deliberately no transport, Intent or system clipboard here. */
class NvdaRemoteSimulator(private val context: Context, private val bindings: NvdaRemoteBindings) {
  data class Result(val event: String, val text: String, val spoken: Boolean = true, val changed: Boolean = true)
  private enum class Scene { WINDOW, START, RUN }
  var mode = NvdaRemoteMode.CONTROL
    private set
  var muted = false
    private set
  private val prepared = linkedSetOf<String>()
  private var heldDots = ""
  private var blockedDots = ""
  var held = emptyList<String>()
    private set
  private var window = 0
  private var previewWindow: Int? = null
  private var altMoves = 0
  private var focus = 0
  private var scene = Scene.WINDOW
  private var editor = ""
  private var runText = ""
  private var search = ""
  private var clipboard = ""
  private var savedDocument = ""
  private var cursor = 0
  private var anchor: Int? = null
  private var learned = false

  private fun text(resource: Int, vararg args: Any) = context.getString(resource, *args)
  private fun result(event: String, resource: Int, vararg args: Any, output: Boolean = false,
    changed: Boolean = true) = Result(event, text(resource, *args), !output || !muted, changed)
  private fun key(code: Int, vararg modifiers: String) = NvdaRemoteBindings.key(code, modifiers.sorted())

  fun reset(mode: NvdaRemoteMode, muted: Boolean = false, target: String? = null) {
    this.mode = mode; this.muted = muted
    prepared.clear(); resetContact()
    window = 0; focus = 0; scene = Scene.WINDOW
    editor = text(R.string.nvda_tutorial_sample_text); clipboard = editor; savedDocument = editor
    runText = ""; search = ""; cursor = editor.length; anchor = null; learned = false
    // Each exercise starts in a state where its requested action has a real, audible effect.
    when (target) {
      key(0x27) -> cursor = 0
      key(0x25) -> cursor = editor.length
      key(0x26), key(0x28) -> {
        editor = text(R.string.nvda_tutorial_sample_lines)
        cursor = if (target == key(0x26)) editor.indexOf('\n') + 2 else 1
      }
      key(0x09, "shift") -> focus = 1
      key(0x0D) -> scene = Scene.START
      key(0x1B) -> scene = Scene.RUN
      key(0x43, "ctrl") -> { anchor = 0; cursor = editor.length }
      key(0x56, "ctrl"), "braille:a" -> { editor = ""; cursor = 0 }
      "braille_space", "braille_enter", "braille_translate" -> { editor = "a"; cursor = 1 }
      "braille_erase" -> { editor = "ab"; cursor = 2 }
    }
  }

  private fun windowName(index: Int = window) = text(listOf(R.string.nvda_tutorial_window_editor,
    R.string.nvda_tutorial_window_documents, R.string.nvda_tutorial_window_browser)[index])
  private fun buffer() = when (scene) { Scene.RUN -> runText; Scene.START -> search; else -> editor }
  private fun setBuffer(value: String) {
    val limited = value.take(MAX_TEXT)
    when (scene) { Scene.RUN -> runText = limited; Scene.START -> search = limited; else -> editor = limited }
    cursor = cursor.coerceIn(0, limited.length)
  }
  private fun editable() = scene == Scene.START || (scene == Scene.RUN && focus == 0) ||
    (scene == Scene.WINDOW && window == 0 && focus == 0)
  private fun selection(): IntRange? = anchor?.takeIf { it != cursor }?.let { minOf(it, cursor) until maxOf(it, cursor) }
  private fun selectedText() = selection()?.let { buffer().substring(it.first, it.last + 1) }.orEmpty()

  fun perform(action: String, gesture: NvdaRemoteGesture? = null): Result {
    if (action in NvdaRemoteBindings.modifierActions) {
      val removed = !prepared.add(action)
      if (removed) prepared.remove(action)
      return Result(if (removed && prepared.isEmpty()) "modifiers_cleared" else "modifier", modifierDescription())
    }
    val resolved = if (prepared.isNotEmpty() || held.isNotEmpty()) when (action) {
      "braille" -> gesture?.dots?.let { NvdaRemoteBindings.letterKey(it) } ?: "none"
      "braille_space" -> key(0x20)
      "braille_enter" -> key(0x0D)
      "braille_erase" -> key(0x08)
      else -> action
    } else action
    val code = NvdaRemoteBindings.keyCode(resolved)
    if (code != null) {
      val modifiers = (prepared + held + NvdaRemoteBindings.modifiers(resolved)).distinct().sorted()
      prepared.clear()
      val event = NvdaRemoteBindings.key(code, modifiers)
      if (learned) return Result("learn", NvdaRemoteBindings.actionLabel(context, event))
      return press(code, modifiers, event)
    }
    if (learned && action in listOf("braille", "braille_space", "braille_erase", "braille_enter",
        "braille_translate", "toggle_remote_speech")) return Result("learn", NvdaRemoteBindings.actionLabel(context, action))
    return when (action) {
      "mode" -> {
        prepared.clear(); resetContact()
        mode = if (mode == NvdaRemoteMode.CONTROL) NvdaRemoteMode.KEYBOARD else NvdaRemoteMode.CONTROL
        Result("mode:${mode.name}", context.getString(mode.label))
      }
      "clear" -> {
        val changed = prepared.isNotEmpty() || held.isNotEmpty()
        prepared.clear(); resetContact()
        Result("modifiers_cleared", modifierDescription(), changed = changed)
      }
      "learn" -> {
        learned = !learned; prepared.clear(); resetContact()
        result("learn", if (learned) R.string.nvda_remote_learning_on else R.string.nvda_remote_learning_off)
      }
      "toggle_remote_speech" -> {
        muted = !muted
        result("muted:$muted", if (muted) R.string.nvda_remote_speech_muted else R.string.nvda_remote_speech_unmuted)
      }
      "braille" -> {
        val dots = gesture?.dots?.takeIf { it.isNotEmpty() && it.all { dot -> dot in '1'..'8' } }
          ?: return result("none", R.string.nvda_remote_invalid_gesture, changed = false)
        val letter = NvdaRemoteBindings.letterKey(dots)?.let { NvdaRemoteBindings.keyCode(it) }
          ?.toChar()?.lowercaseChar()?.toString()
        val cell = letter ?: (0x2800 + dots.fold(0) { value, dot -> value or (1 shl (dot - '1')) }).toChar().toString()
        insert(cell, "braille:${letter ?: dots}")
      }
      "braille_space" -> insert(" ", action)
      "braille_erase" -> erase(action)
      "braille_enter" -> enter(action)
      "braille_translate" -> result(action, R.string.nvda_tutorial_text_saved, buffer(), output = true)
      else -> result("none", R.string.nvda_remote_no_action, changed = false)
    }
  }

  private fun insert(value: String, event: String): Result {
    if (!editable()) return result(event, R.string.nvda_tutorial_no_text_field, output = true, changed = false)
    val current = buffer()
    val selection = selection()
    val start = selection?.first ?: cursor
    val end = selection?.let { it.last + 1 } ?: cursor
    val available = MAX_TEXT - (current.length - (end - start))
    val addition = value.take(available.coerceAtLeast(0))
    if (addition.isEmpty()) return result(event, R.string.nvda_tutorial_text_full, output = true, changed = false)
    setBuffer(current.take(start) + addition + current.substring(end))
    cursor = start + addition.length; anchor = null
    return result(event, if (value == " ") R.string.nvda_tutorial_space else R.string.nvda_tutorial_typed,
      addition, output = true)
  }

  private fun erase(event: String, forward: Boolean = false, word: Boolean = false): Result {
    if (!editable()) return result(event, R.string.nvda_tutorial_no_text_field, output = true, changed = false)
    val current = buffer()
    val selection = selection()
    val start = selection?.first ?: if (forward) cursor else if (word) wordPosition(-1) else (cursor - 1).coerceAtLeast(0)
    val end = selection?.let { it.last + 1 } ?: if (forward) (cursor + 1).coerceAtMost(current.length) else cursor
    if (start == end) return result(event, R.string.nvda_tutorial_text_edge, output = true, changed = false)
    setBuffer(current.removeRange(start, end)); cursor = start; anchor = null
    return result(event, R.string.nvda_tutorial_erased, buffer(), output = true)
  }

  private fun wordPosition(direction: Int): Int {
    val current = buffer()
    var position = cursor
    if (direction < 0) {
      while (position > 0 && current[position - 1].isWhitespace()) position--
      while (position > 0 && !current[position - 1].isWhitespace()) position--
    } else {
      while (position < current.length && !current[position].isWhitespace()) position++
      while (position < current.length && current[position].isWhitespace()) position++
    }
    return position
  }

  private fun move(code: Int, modifiers: List<String>, event: String): Result {
    if (scene == Scene.START) return moveFocus(if (code in listOf(0x25, 0x26, 0x24, 0x21)) -1 else 1, event)
    if (!editable()) return moveFocus(if (code in listOf(0x25, 0x26, 0x24, 0x21)) -1 else 1, event)
    val current = buffer()
    val old = cursor
    val lineStart = current.lastIndexOf('\n', (cursor - 1).coerceAtLeast(-1)) + 1
    val lineEnd = current.indexOf('\n', cursor).takeIf { it >= 0 } ?: current.length
    val column = cursor - lineStart
    val next = when (code) {
      0x25 -> if ("ctrl" in modifiers) wordPosition(-1) else (cursor - 1).coerceAtLeast(0)
      0x27 -> if ("ctrl" in modifiers) wordPosition(1) else (cursor + 1).coerceAtMost(current.length)
      0x26 -> if (lineStart == 0) 0 else {
        val previousEnd = lineStart - 1
        val previousStart = current.lastIndexOf('\n', previousEnd - 1) + 1
        minOf(previousStart + column, previousEnd)
      }
      0x28 -> if (lineEnd == current.length) current.length else {
        val nextStart = lineEnd + 1
        val nextEnd = current.indexOf('\n', nextStart).takeIf { it >= 0 } ?: current.length
        minOf(nextStart + column, nextEnd)
      }
      0x24 -> if ("ctrl" in modifiers) 0 else lineStart
      0x23 -> if ("ctrl" in modifiers) current.length else lineEnd
      0x21 -> 0
      else -> current.length
    }
    if ("shift" in modifiers) { if (anchor == null) anchor = old } else anchor = null
    cursor = next
    val atCursor = when {
      cursor == current.length -> text(R.string.nvda_tutorial_end_text)
      current[cursor] == '\n' -> text(R.string.nvda_tutorial_new_line)
      current[cursor] == ' ' -> text(R.string.nvda_tutorial_space)
      else -> current[cursor].toString()
    }
    val message = text(R.string.nvda_tutorial_cursor_position, cursor + 1, current.length + 1, atCursor) +
      selectedText().takeIf { it.isNotEmpty() }?.let { " " + text(R.string.nvda_tutorial_selected, it) }.orEmpty()
    return Result(event, message, !muted, old != next)
  }

  private fun focusCount() = when (scene) { Scene.START -> 4; Scene.RUN -> 2; else -> 3 }
  private fun focusText(): String = when (scene) {
    Scene.START -> text(listOf(R.string.nvda_tutorial_window_editor, R.string.nvda_tutorial_window_documents,
      R.string.nvda_tutorial_window_browser, R.string.nvda_tutorial_run_item)[focus])
    Scene.RUN -> text(if (focus == 0) R.string.nvda_tutorial_focus_text else R.string.nvda_tutorial_open_button)
    Scene.WINDOW -> when (window) {
      0 -> text(listOf(R.string.nvda_tutorial_focus_text, R.string.nvda_tutorial_focus_save,
        R.string.nvda_tutorial_focus_close)[focus])
      1 -> text(listOf(R.string.nvda_tutorial_document_notes, R.string.nvda_tutorial_document_list,
        R.string.nvda_tutorial_open_button)[focus])
      else -> text(listOf(R.string.nvda_tutorial_browser_address, R.string.nvda_tutorial_browser_help,
        R.string.nvda_tutorial_browser_back)[focus])
    }
  }
  private fun moveFocus(direction: Int, event: String): Result {
    focus = (focus + direction + focusCount()) % focusCount()
    anchor = null
    return Result(event, focusText(), !muted)
  }

  private fun openWindow(index: Int) {
    window = index; scene = Scene.WINDOW; focus = 0; cursor = editor.length; anchor = null
  }
  private fun enter(event: String): Result {
    if (scene == Scene.START) {
      val destination = if (search.isBlank()) focus else when (search.trim().lowercase()) {
        "editor", "notepad", "bloc", "bloc de notas" -> 0
        "documents", "documentos" -> 1
        "browser", "navegador" -> 2
        else -> return result(event, R.string.nvda_tutorial_search_hint, output = true, changed = false)
      }
      if (destination == 3) { scene = Scene.RUN; runText = ""; cursor = 0; focus = 0 }
      else openWindow(destination)
      return Result(event, status(), !muted)
    }
    if (scene == Scene.RUN) {
      if (runText.trim().lowercase() !in listOf("editor", "notepad", "bloc", "bloc de notas"))
        return result(event, R.string.nvda_tutorial_run_hint, output = true, changed = false)
      openWindow(0)
      return result(event, R.string.nvda_tutorial_editor_open, output = true)
    }
    if (window == 1) {
      editor = if (focus == 1) text(R.string.nvda_tutorial_sample_list) else savedDocument
      openWindow(0)
      return result(event, R.string.nvda_tutorial_document_opened, editor, output = true)
    }
    if (window == 2) return result(event, R.string.nvda_tutorial_browser_page, output = true)
    if (focus == 1) {
      savedDocument = editor
      return result(event, R.string.nvda_tutorial_saved, output = true)
    }
    if (focus == 2) { openWindow(1); return Result(event, status(), !muted) }
    val inserted = insert("\n", event)
    return if (inserted.changed) result(event, R.string.nvda_tutorial_text_saved, editor, output = true) else inserted
  }

  private fun press(code: Int, modifiers: List<String>, event: String): Result {
    if (code == 0x09 && "alt" in modifiers) {
      val next = ((previewWindow ?: window) + if ("shift" in modifiers) 2 else 1) % 3
      if ("alt" in held) { previewWindow = next; altMoves++ } else openWindow(next)
      return Result(event, windowName(next), !muted)
    }
    if (code == 0x1B && previewWindow != null) {
      blockedDots += heldDots
      heldDots = ""; held = emptyList(); previewWindow = null; altMoves = 0
      return result(event, R.string.nvda_tutorial_switch_cancelled, windowName(), output = true)
    }
    if (code == 0x5B && modifiers.isEmpty()) {
      scene = if (scene == Scene.START) Scene.WINDOW else Scene.START
      focus = 0; search = ""; cursor = if (scene == Scene.START) 0 else editor.length; anchor = null
      return Result(event, status(), !muted)
    }
    if (modifiers == listOf("windows") && code == 0x52) {
      scene = Scene.RUN; focus = 0; runText = ""; cursor = 0; anchor = null
      return result(event, R.string.nvda_tutorial_run_open, output = true)
    }
    if (modifiers == listOf("ctrl")) when (code) {
      0x41 -> {
        if (!editable()) return result(event, R.string.nvda_tutorial_no_text_field, output = true, changed = false)
        anchor = 0; cursor = buffer().length
        return result(event, R.string.nvda_tutorial_selected, selectedText(), output = true, changed = buffer().isNotEmpty())
      }
      0x43 -> {
        val value = selectedText()
        if (value.isEmpty()) return result(event, R.string.nvda_tutorial_no_selection, output = true, changed = false)
        clipboard = value
        return result(event, R.string.nvda_tutorial_copied, clipboard, output = true)
      }
      0x56 -> {
        if (clipboard.isEmpty()) return result(event, R.string.nvda_tutorial_empty_clipboard, output = true, changed = false)
        val inserted = insert(clipboard, event)
        return if (inserted.changed) result(event, R.string.nvda_tutorial_pasted, buffer(), output = true) else inserted
      }
      0x08 -> return erase(event, word = true)
    }
    if ("nvda" in modifiers && code == 0x54) return Result(event, windowName(), !muted)
    if ("nvda" in modifiers && code == 0x09) return Result(event, focusText(), !muted)
    if ("nvda" in modifiers && code == 0x28) return Result(event, status(), !muted)
    if (modifiers.all { it in listOf("shift", "ctrl") } && code in listOf(0x25, 0x26, 0x27, 0x28, 0x24, 0x23, 0x21, 0x22))
      return move(code, modifiers, event)
    if (modifiers.isEmpty() || modifiers == listOf("shift")) when (code) {
      0x09 -> return moveFocus(if ("shift" in modifiers) -1 else 1, event)
      0x0D -> return enter(event)
      0x1B -> {
        val changed = scene != Scene.WINDOW
        scene = Scene.WINDOW; focus = 0; cursor = editor.length; anchor = null
        return result(event, if (changed) R.string.nvda_tutorial_closed else R.string.nvda_tutorial_no_dialog,
          output = true, changed = changed)
      }
      0x08 -> return erase(event)
      0x2E -> return erase(event, forward = true)
      0x20 -> return insert(" ", event)
      0x11 -> return result(event, R.string.nvda_remote_stop)
      in 0x30..0x39, in 0x41..0x5A -> return insert(code.toChar().let {
        if ("shift" in modifiers) it.toString() else it.lowercaseChar().toString() }, event)
    }
    return result(event, R.string.nvda_tutorial_key_only,
      NvdaRemoteBindings.actionLabel(context, event), output = true, changed = false)
  }

  fun hold(dots: String): Result? {
    if (dots.isEmpty() || dots.any { it in blockedDots }) return null
    val names = bindings.heldModifiers(mode, dots)
    val navigation = bindings.navigationHeldDots(mode, dots)
    if (navigation.any { it != '1' } || (names.isEmpty() && navigation != "1")) return null
    heldDots = dots; held = names; prepared.removeAll(names.toSet())
    return Result("hold", modifierDescription())
  }

  fun release(dots: String): Result? {
    blockedDots = blockedDots.filter { it in dots }
    val remaining = heldDots.filter { it in dots }
    if (remaining == heldDots) return null
    val names = bindings.heldModifiers(mode, remaining).filter { it in held }
    val chosen = "alt" in held && "alt" !in names && previewWindow != null
    val repeated = altMoves >= 2
    heldDots = remaining; held = names
    if (!chosen) return null
    val selectedWindow = previewWindow!!
    previewWindow = null; altMoves = 0; openWindow(selectedWindow)
    return result(if (repeated) "window_chosen" else "window_selected",
      R.string.nvda_tutorial_window_chosen, windowName(), output = true)
  }

  fun resetContact() { heldDots = ""; held = emptyList(); blockedDots = ""; previewWindow = null; altMoves = 0 }
  fun clearHeld() = resetContact()
  fun clearInput() { prepared.clear(); resetContact() }
  fun modifierDescription(): String {
    val parts = mutableListOf<String>()
    if (prepared.isNotEmpty()) parts += text(R.string.nvda_remote_modifiers_ready,
      prepared.joinToString(" + ") { NvdaRemoteBindings.modifierLabel(it) })
    if (held.isNotEmpty()) parts += text(R.string.nvda_remote_modifiers_held,
      held.joinToString(" + ") { NvdaRemoteBindings.modifierLabel(it) })
    return parts.joinToString(". ").ifEmpty { text(R.string.nvda_remote_modifiers_empty) }
  }
  fun status(): String = when (scene) {
    Scene.START -> text(R.string.nvda_tutorial_start_open) + " " + focusText() + " " + search
    Scene.RUN -> text(R.string.nvda_tutorial_run_open) + " " + runText
    Scene.WINDOW -> windowName() + ". " + focusText() + if (window == 0) "\n$editor" else ""
  }

  fun save(): Bundle = Bundle().apply {
    putString("mode", mode.name); putBoolean("muted", muted)
    putInt("window", window); putInt("focus", focus); putString("scene", scene.name)
    putString("editor", editor); putString("run", runText); putString("search", search)
    putString("clipboard", clipboard); putString("saved", savedDocument); putInt("cursor", cursor)
    putInt("anchor", anchor ?: -1)
  }
  fun restore(state: Bundle) {
    reset(runCatching { NvdaRemoteMode.valueOf(state.getString("mode").orEmpty()) }.getOrDefault(NvdaRemoteMode.CONTROL),
      state.getBoolean("muted"))
    window = state.getInt("window").coerceIn(0, 2)
    scene = runCatching { Scene.valueOf(state.getString("scene").orEmpty()) }.getOrDefault(Scene.WINDOW)
    focus = state.getInt("focus").coerceIn(0, focusCount() - 1)
    editor = state.getString("editor").orEmpty().take(MAX_TEXT)
    runText = state.getString("run").orEmpty().take(MAX_TEXT); search = state.getString("search").orEmpty().take(MAX_TEXT)
    clipboard = state.getString("clipboard").orEmpty().take(MAX_TEXT)
    savedDocument = state.getString("saved").orEmpty().take(MAX_TEXT)
    cursor = state.getInt("cursor").coerceIn(0, buffer().length)
    anchor = state.getInt("anchor", -1).takeIf { it in 0..buffer().length }
  }
  companion object { private const val MAX_TEXT = 2048 }
}
