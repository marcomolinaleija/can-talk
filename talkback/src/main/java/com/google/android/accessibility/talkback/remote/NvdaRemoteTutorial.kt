/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.remote

import android.content.Context
import com.google.android.accessibility.talkback.R

/** Lesson IDs and completed steps are independent of labels and gesture assignments. */
object NvdaRemoteTutorial {
  data class Step(
    val instruction: Int,
    val target: String,
    val actions: List<String>,
    val mode: NvdaRemoteMode = NvdaRemoteMode.CONTROL,
    val muted: Boolean = false,
  )
  data class Lesson(val id: String, val title: Int, val steps: List<Step>)
  private fun key(code: Int, vararg modifiers: String) = NvdaRemoteBindings.key(code, modifiers.sorted())
  private fun step(instruction: Int, action: String, mode: NvdaRemoteMode = NvdaRemoteMode.CONTROL) =
    Step(instruction, action, listOf(action), mode)

  val lessons = listOf(
    Lesson("basics", R.string.nvda_tutorial_lesson_basics, listOf(
      step(R.string.nvda_tutorial_step_dot, key(0x41)),
      step(R.string.nvda_tutorial_step_menu, "menu"),
      Step(R.string.nvda_tutorial_step_exit, "exit_cancelled", listOf("exit")),
    )),
    Lesson("navigation", R.string.nvda_tutorial_lesson_navigation, listOf(
      step(R.string.nvda_tutorial_step_tab, key(0x09)),
      step(R.string.nvda_tutorial_step_previous, key(0x09, "shift")),
      step(R.string.nvda_tutorial_step_right, key(0x27)),
      step(R.string.nvda_tutorial_step_left, key(0x25)),
      step(R.string.nvda_tutorial_step_up, key(0x26)),
      step(R.string.nvda_tutorial_step_down, key(0x28)),
      step(R.string.nvda_tutorial_step_enter, key(0x0D)),
      step(R.string.nvda_tutorial_step_escape, key(0x1B)),
    )),
    Lesson("shortcuts", R.string.nvda_tutorial_lesson_shortcuts, listOf(
      Step(R.string.nvda_tutorial_step_cancel_modifier, "modifiers_cleared", listOf("ctrl", "ctrl")),
      step(R.string.nvda_tutorial_step_select, key(0x41, "ctrl")),
      step(R.string.nvda_tutorial_step_copy, key(0x43, "ctrl")),
      step(R.string.nvda_tutorial_step_paste, key(0x56, "ctrl")),
    )),
    Lesson("windows", R.string.nvda_tutorial_lesson_windows, listOf(
      step(R.string.nvda_tutorial_step_windows, key(0x5B)),
      step(R.string.nvda_tutorial_step_run, key(0x52, "windows")),
    )),
    Lesson("held", R.string.nvda_tutorial_lesson_held, listOf(
      Step(R.string.nvda_tutorial_step_held, "window_chosen", listOf("hold_alt")),
    )),
    Lesson("typing", R.string.nvda_tutorial_lesson_typing, listOf(
      Step(R.string.nvda_tutorial_step_keyboard, "mode:KEYBOARD", listOf("mode")),
      Step(R.string.nvda_tutorial_step_letter, "braille:a", listOf("braille_a"), NvdaRemoteMode.KEYBOARD),
      step(R.string.nvda_tutorial_step_space, "braille_space", NvdaRemoteMode.KEYBOARD),
      step(R.string.nvda_tutorial_step_erase, "braille_erase", NvdaRemoteMode.KEYBOARD),
      step(R.string.nvda_tutorial_step_commit, "braille_enter", NvdaRemoteMode.KEYBOARD),
      Step(R.string.nvda_tutorial_step_control, "mode:CONTROL", listOf("mode"), NvdaRemoteMode.KEYBOARD),
    )),
    Lesson("speech", R.string.nvda_tutorial_lesson_speech, listOf(
      Step(R.string.nvda_tutorial_step_mute, "muted:true", listOf("toggle_remote_speech")),
      Step(R.string.nvda_tutorial_step_unmute, "muted:false", listOf("toggle_remote_speech"), muted = true),
    )),
  )

  class Progress(context: Context) {
    private val prefs = context.getSharedPreferences("nvda_remote_tutorial_v1", Context.MODE_PRIVATE)
    fun completed(lesson: Lesson) = nextStep(lesson) >= lesson.steps.size
    fun nextStep(lesson: Lesson): Int {
      val done = prefs.getStringSet("done/${lesson.id}", emptySet()).orEmpty()
      return lesson.steps.indexOfFirst { it.target !in done }.takeIf { it >= 0 } ?: lesson.steps.size
    }
    fun passed(lesson: Lesson, index: Int) {
      val done = prefs.getStringSet("done/${lesson.id}", emptySet()).orEmpty() + lesson.steps[index].target
      prefs.edit().putStringSet("done/${lesson.id}", done).apply()
    }
    fun remember(lesson: Lesson) { prefs.edit().putString("last_lesson", lesson.id).apply() }
    fun resumeLesson(): Lesson? = lessons.firstOrNull {
      it.id == prefs.getString("last_lesson", null) && !completed(it)
    } ?: lessons.firstOrNull { !completed(it) }
    fun shouldOffer() = !prefs.getBoolean("hide_invitation", false) && lessons.any { !completed(it) }
    fun invitationHidden() = prefs.getBoolean("hide_invitation", false)
    fun hideInvitation() { prefs.edit().putBoolean("hide_invitation", true).apply() }
    fun showInvitation() { prefs.edit().putBoolean("hide_invitation", false).apply() }
    fun resetLessons() {
      val edit = prefs.edit().remove("last_lesson")
      prefs.all.keys.filter { it.startsWith("done/") }.forEach { edit.remove(it) }
      edit.apply()
    }
    fun hasStarted() = prefs.contains("last_lesson")
  }
}
