/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.remote

import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService

/** All input, including menu commands, ends at the offline simulator. */
class NvdaRemoteTutorialActivity : AppCompatActivity() {
  private enum class Page { HOME, INTRO, PRACTICE, RESULT }
  private lateinit var content: FrameLayout
  private lateinit var bindings: NvdaRemoteBindings
  private lateinit var progress: NvdaRemoteTutorial.Progress
  private lateinit var simulator: NvdaRemoteSimulator
  private var page = Page.HOME
  private var lesson: NvdaRemoteTutorial.Lesson? = null
  private var stepIndex = 0
  private var freePractice = false
  private var resumed = false
  private var ownsTouchRegion = false
  private var surface: NvdaRemoteInput? = null
  private var dialog: AlertDialog? = null
  private var lastOutput = ""
  private var resultText = ""
  private var resultInstruction = ""
  private var panelAnnouncement = ""
  private var panelGeneration = 0
  private var needsAnnouncement = false
  private val step get() = lesson?.steps?.getOrNull(stepIndex)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    title = getString(R.string.nvda_tutorial_title)
    bindings = NvdaRemoteBindings(this)
    progress = NvdaRemoteTutorial.Progress(this)
    simulator = NvdaRemoteSimulator(this, bindings)
    simulator.reset(NvdaRemoteMode.CONTROL)
    content = FrameLayout(this)
    setContentView(content)
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() { confirmExit() }
    })
    if (savedInstanceState != null) {
      lesson = NvdaRemoteTutorial.lessons.firstOrNull { it.id == savedInstanceState.getString("lesson") }
      stepIndex = savedInstanceState.getInt("step").coerceIn(0, (lesson?.steps?.size ?: 1) - 1)
      freePractice = savedInstanceState.getBoolean("free")
      savedInstanceState.getBundle("simulation")?.let { simulator.restore(it) }
      resultText = savedInstanceState.getString("result").orEmpty()
      resultInstruction = savedInstanceState.getString("instruction").orEmpty()
      if (savedInstanceState.getString("page") == Page.RESULT.name && step != null) showResult()
      else if (lesson != null || freePractice) showIntroduction(reset = false) else showHome()
    } else if (intent.getBooleanExtra("resume", false)) {
      progress.resumeLesson()?.let { openLesson(it) } ?: showHome()
    } else showHome()
  }

  private fun action(resource: Int, run: () -> Unit) = getString(resource) to run
  private fun panel(heading: String, body: String, actions: List<Pair<String, () -> Unit>>) {
    removeSurface()
    content.removeAllViews()
    val column = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      val padding = (16 * resources.displayMetrics.density).toInt()
      setPadding(padding, padding, padding, padding)
    }
    column.addView(TextView(this).apply {
      text = heading; textSize = 22f
      ViewCompat.setAccessibilityHeading(this, true)
    })
    column.addView(TextView(this).apply { text = body; textSize = 18f; setPadding(0, 16, 0, 16) })
    for ((label, run) in actions) column.addView(Button(this).apply {
      text = label; minHeight = (48 * resources.displayMetrics.density).toInt()
      setOnClickListener { run() }
    })
    content.addView(ScrollView(this).apply { addView(column) }, FrameLayout.LayoutParams(-1, -1))
    val generation = ++panelGeneration
    panelAnnouncement = "$heading. $body"; needsAnnouncement = true
    content.post { if (generation == panelGeneration) announcePanel() }
  }

  private fun announcePanel() {
    if (resumed && hasWindowFocus() && !isFinishing && needsAnnouncement && dialog == null && page != Page.PRACTICE) {
      needsAnnouncement = false
      announce(panelAnnouncement)
    }
  }

  private fun showHome() {
    page = Page.HOME; lesson = null; freePractice = false
    val complete = NvdaRemoteTutorial.lessons.all { progress.completed(it) }
    val actions = mutableListOf<Pair<String, () -> Unit>>()
    progress.resumeLesson()?.let { item ->
      actions += action(if (progress.hasStarted()) R.string.nvda_tutorial_resume else R.string.nvda_tutorial_start) { openLesson(item) }
    }
    for (item in NvdaRemoteTutorial.lessons) {
      val label = getString(item.title) + if (progress.completed(item)) getString(R.string.nvda_tutorial_done_suffix) else ""
      actions += label to { openLesson(item) }
    }
    actions += action(R.string.nvda_tutorial_free) { freePractice = true; showIntroduction() }
    if (progress.hasStarted()) actions += action(R.string.nvda_tutorial_reset_lessons) {
      showDialog(AlertDialog.Builder(this).setTitle(R.string.nvda_tutorial_reset_question)
        .setMessage(R.string.nvda_tutorial_reset_body)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.nvda_tutorial_reset_lessons) { _, _ -> progress.resetLessons(); showHome() })
    }
    if (progress.invitationHidden()) actions += action(R.string.nvda_tutorial_show_invitation) {
      progress.showInvitation(); showHome()
    }
    actions += action(R.string.nvda_tutorial_return_connection) { confirmExit() }
    panel(getString(if (complete) R.string.nvda_tutorial_finished else R.string.nvda_tutorial_title),
      getString(if (complete) R.string.nvda_tutorial_finished_body else R.string.nvda_tutorial_home_body), actions)
  }

  private fun openLesson(item: NvdaRemoteTutorial.Lesson) {
    lesson = item; freePractice = false
    stepIndex = progress.nextStep(item).takeIf { it < item.steps.size } ?: 0
    progress.remember(item)
    showIntroduction()
  }

  private fun showIntroduction(reset: Boolean = true) {
    page = Page.INTRO
    if (reset) {
      simulator.reset(step?.mode ?: NvdaRemoteMode.CONTROL, step?.muted ?: false, step?.target)
      lastOutput = ""
    }
    val actions = mutableListOf(
      action(R.string.nvda_tutorial_practice) { beginPractice() },
      action(R.string.nvda_tutorial_repeat_instruction) { announce(instruction()) },
    )
    if (step != null) actions += action(R.string.nvda_tutorial_skip) { advance() }
    actions += action(R.string.nvda_tutorial_lessons) { showHome() }
    actions += action(R.string.nvda_tutorial_exit) { confirmExit() }
    val heading = lesson?.let { getString(R.string.nvda_tutorial_step_count,
      getString(it.title), stepIndex + 1, it.steps.size) } ?: getString(R.string.nvda_tutorial_free)
    panel(heading, instruction() + "\n\n" + getString(R.string.nvda_tutorial_back_hint), actions)
  }

  private fun instruction(): String {
    val current = step ?: return getString(R.string.nvda_tutorial_free_body)
    val guide = getString(current.instruction, current.actions.joinToString("\n") { describeCommand(it, current.mode) })
    return if (simulator.mode != current.mode && !current.target.startsWith("mode:"))
      getString(R.string.nvda_tutorial_restore_mode, getString(current.mode.label), describeCommand("mode", simulator.mode)) +
        "\n" + guide else guide
  }

  private fun candidates(mode: NvdaRemoteMode): List<NvdaRemoteGesture> {
    val count = NvdaRemoteInputFactory.dotCount(this).coerceIn(6, 8)
    val taps = (1 until (1 shl count)).map { mask -> NvdaRemoteGesture(dots = (1..count)
      .filter { mask and (1 shl (it - 1)) != 0 }.joinToString("")) }
    val holds = (1..count).map { NvdaRemoteGesture(held = it.toString(), holdOnly = true) }
    val navigation = listOf("LEFT", "RIGHT", "UP", "DOWN").map {
      NvdaRemoteGesture(direction = it, swipedDot = 4, held = "1")
    }
    return (bindings.gestures(mode) + taps + holds + navigation).distinctBy { it.id }
      .filter { it.swipedDot <= count && (it.dots.orEmpty() + it.held).all { dot -> dot - '0' in 1..count } }
  }
  private fun canonical(command: String): String = NvdaRemoteBindings.keyCode(command)?.let {
    NvdaRemoteBindings.key(it, NvdaRemoteBindings.modifiers(command).sorted())
  } ?: command
  private fun cost(gesture: NvdaRemoteGesture) = gesture.held.length * 30 +
    (gesture.dots?.length ?: (gesture.fingers * 10 + gesture.swipedDot))
  private fun gestureFor(command: String, mode: NvdaRemoteMode, hold: Boolean = false) = candidates(mode)
    .filter { it.holdOnly == hold && canonical(bindings.resolve(mode, it)) == canonical(command) }
    .minByOrNull { cost(it) }

  private fun describeCommand(command: String, mode: NvdaRemoteMode): String {
    if (command == "hold_alt") {
      val held = gestureFor("alt", mode, true) ?: return getString(R.string.nvda_tutorial_missing_hold)
      val count = NvdaRemoteInputFactory.dotCount(this).coerceIn(6, 8)
      val swipes = bindings.gestures(mode).filter { it.direction == "RIGHT" }.map { it.copy(held = held.held) } +
        (0..count).map { NvdaRemoteGesture(direction = "RIGHT", swipedDot = it, held = held.held) }
      val swipe = swipes.filter { it.swipedDot in 0..count }.filter {
        val resolved = bindings.resolve(mode, it)
        NvdaRemoteBindings.keyCode(resolved) == 0x09 && NvdaRemoteBindings.modifiers(resolved).all { mod -> mod == "alt" }
      }.minByOrNull { cost(it) } ?: return getString(R.string.nvda_tutorial_missing_hold)
      return getString(R.string.nvda_tutorial_hold_hint, held.label(this), swipe.copy(held = "").label(this))
    }
    if (command == "braille_a") {
      val cell = candidates(mode).firstOrNull { it.dots == "1" && bindings.resolve(mode, it) == "braille" }
      return cell?.let { getString(R.string.nvda_tutorial_do_gesture, it.label(this)) }
        ?: getString(R.string.nvda_tutorial_missing_gesture)
    }
    gestureFor(command, mode)?.let { return getString(R.string.nvda_tutorial_do_gesture, it.label(this)) }
    if (command == "exit" || command == "menu") return getString(R.string.nvda_tutorial_back_hint)
    val code = NvdaRemoteBindings.keyCode(command)
    val modifiers = NvdaRemoteBindings.modifiers(command)
    if (code != null && modifiers.isNotEmpty()) return (modifiers + NvdaRemoteBindings.key(code))
      .joinToString("\n") { describeCommand(it, mode) }
    val label = NvdaRemoteBindings.actionLabel(this, command)
    val menu = gestureFor("menu", mode)?.label(this)
    return if (menu == null) getString(R.string.nvda_tutorial_from_back_menu, label)
      else getString(R.string.nvda_tutorial_from_menu, menu, label)
  }

  private fun beginPractice() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || TalkBackService.getInstance() == null) {
      announce(getString(R.string.nvda_remote_surface_requires_android)); return
    }
    page = Page.PRACTICE; needsAnnouncement = false; panelGeneration++
    showSurface()
    content.post { if (acceptsInput()) announce(instruction()) }
  }

  private fun showSurface() {
    if (page != Page.PRACTICE || !resumed || surface != null || dialog?.isShowing == true) return
    val created = NvdaRemoteInputFactory.create(this, Size(content.width.coerceAtLeast(1),
      content.height.coerceAtLeast(1)), object : NvdaRemoteInput.Listener {
      override fun onGesture(gesture: NvdaRemoteGesture) {
        if (acceptsInput()) dispatch(bindings.resolve(simulator.mode, gesture), gesture)
      }
      override fun onAnnouncement(text: String) { if (acceptsInput()) announce(text) }
      override fun onHold(dots: String): Boolean {
        if (!acceptsInput()) return false
        val result = simulator.hold(dots) ?: return false
        vibrate(30)
        if (simulator.held.isNotEmpty()) announce(result.text)
        updateDescription()
        return simulator.held.isNotEmpty()
      }
      override fun onHeldDotsChanged(dots: String) {
        if (acceptsInput()) simulator.release(dots)?.let { receive(it) }
      }
    })
    if (created == null) { showIntroduction(reset = false); announce(getString(R.string.nvda_remote_surface_requires_android)); return }
    content.removeAllViews(); simulator.resetContact()
    surface = created
    content.addView(created, FrameLayout.LayoutParams(-1, -1))
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    created.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTouchRegion() }
    created.post { updateTouchRegion() }
    updateDescription()
  }

  private fun acceptsInput() = resumed && page == Page.PRACTICE && surface != null && hasWindowFocus() && dialog == null
  private fun dispatch(command: String, gesture: NvdaRemoteGesture? = null) {
    when (command) {
      "exit" -> confirmExit()
      "menu" -> showPracticeMenu()
      else -> {
        val before = instruction()
        val result = simulator.perform(command, gesture)
        receive(if (lesson?.id == "basics" && stepIndex == 0 && gesture != null && result.event != step?.target)
          result.copy(text = gesture.label(this) + ". " + result.text) else result, before)
      }
    }
    updateDescription()
  }

  private fun receive(result: NvdaRemoteSimulator.Result, before: String = instruction()) {
    lastOutput = result.text
    val current = step
    if (!freePractice && current != null && canonical(result.event) == canonical(current.target) && result.changed) {
      progress.passed(lesson!!, stepIndex)
      page = Page.RESULT; resultText = result.text; resultInstruction = before
      vibrate(40)
      content.post { if (resumed && !isFinishing && page == Page.RESULT) showResult() }
    } else if (result.spoken) {
      val intermediate = result.event in listOf("modifier", "hold", "learn", "modifiers_cleared") ||
        (current?.target == "window_chosen" && NvdaRemoteBindings.keyCode(result.event) == 0x09 &&
          "alt" in NvdaRemoteBindings.modifiers(result.event))
      val goal = current?.let { getString(it.instruction, "").substringBefore('\n') }
      announce(result.text + if (goal != null && !intermediate)
        "\n" + getString(R.string.nvda_tutorial_try_again, goal) else "")
    }
    updateDescription()
  }

  private fun showResult() {
    page = Page.RESULT
    panel(getString(R.string.nvda_tutorial_correct), resultText, listOf(
      action(R.string.nvda_tutorial_continue) { advance() },
      action(R.string.nvda_tutorial_repeat_exercise) { showIntroduction() },
      action(R.string.nvda_tutorial_repeat_instruction) { announce(resultInstruction) },
      action(R.string.nvda_tutorial_lessons) { showHome() },
      action(R.string.nvda_tutorial_exit) { confirmExit() },
    ))
  }
  private fun advance() {
    val item = lesson
    if (item != null && stepIndex + 1 < item.steps.size) { stepIndex++; showIntroduction() } else showHome()
  }

  private fun showPracticeMenu() {
    val completeMenu = step?.target == "menu"
    val common = listOf(NvdaRemoteBindings.key(0x5B), NvdaRemoteBindings.key(0x09),
      NvdaRemoteBindings.key(0x09, listOf("shift")))
    val commands = (listOf("hint", "explanation", "restart", "lessons", "exit", "mode", "clear", "toggle_remote_speech") +
      NvdaRemoteBindings.modifierActions + common + NvdaRemoteBindings.keys.map { NvdaRemoteBindings.key(it) }).distinct()
    val labels = commands.map { when (it) {
      "hint" -> getString(R.string.nvda_tutorial_hint)
      "explanation" -> getString(R.string.nvda_tutorial_repeat_instruction)
      "restart" -> getString(R.string.nvda_tutorial_repeat_exercise)
      "lessons" -> getString(R.string.nvda_tutorial_lessons)
      "exit" -> getString(R.string.nvda_tutorial_exit)
      "toggle_remote_speech" -> getString(if (simulator.muted) R.string.nvda_remote_unmute_speech else R.string.nvda_remote_mute_speech)
      else -> NvdaRemoteBindings.actionLabel(this, it)
    } }.toTypedArray()
    showDialog(AlertDialog.Builder(this).setTitle(R.string.nvda_tutorial_practice_menu)
      .setItems(labels) { _, index ->
        when (val selected = commands[index]) {
          "hint", "explanation" -> announce(instruction())
          "restart" -> showIntroduction()
          "lessons" -> showHome()
          "exit" -> content.post { confirmExit() }
          else -> dispatch(selected)
        }
      }.setNegativeButton(R.string.nvda_tutorial_keep_practicing, null)) {
        if (completeMenu && page == Page.PRACTICE) receive(NvdaRemoteSimulator.Result("menu",
          getString(R.string.nvda_tutorial_menu_open)))
      }
  }

  private fun confirmExit() {
    if (dialog?.isShowing == true) return
    showDialog(AlertDialog.Builder(this).setTitle(R.string.nvda_tutorial_exit_question)
      .setMessage(R.string.nvda_tutorial_exit_body)
      .setPositiveButton(R.string.nvda_tutorial_exit) { _, _ -> finish() }
      .setNegativeButton(R.string.nvda_tutorial_keep_practicing) { _, _ ->
        if (page == Page.PRACTICE && step?.target == "exit_cancelled") receive(NvdaRemoteSimulator.Result(
          "exit_cancelled", getString(R.string.nvda_tutorial_exit_cancelled)))
      }.apply {
        if (page == Page.PRACTICE) setNeutralButton(R.string.nvda_tutorial_practice_menu) { _, _ ->
          content.post { showPracticeMenu() }
        }
      })
  }

  private fun showDialog(builder: AlertDialog.Builder, dismissed: () -> Unit = {}) {
    if (dialog?.isShowing == true) return
    removeSurface()
    val created = builder.create()
    dialog = created
    created.setOnDismissListener {
      if (dialog === created) {
        dialog = null; dismissed()
        content.post { if (resumed && !isFinishing) showSurface() }
      }
    }
    created.show()
  }

  private fun updateDescription() {
    surface?.setDescription(getString(R.string.nvda_tutorial_simulation) + "\n" + getString(simulator.mode.label) +
      "\n" + simulator.modifierDescription() + "\n" + simulator.status() + "\n" + lastOutput)
  }
  private fun updateTouchRegion() {
    val current = surface ?: return
    if (!resumed || !hasWindowFocus()) return
    val bounds = Rect()
    if (current.getGlobalVisibleRect(bounds) && !bounds.isEmpty) {
      TalkBackService.getInstance()?.setModalTouchRegion(Region(bounds)); ownsTouchRegion = true
    }
  }
  private fun removeSurface() {
    if (ownsTouchRegion) { ownsTouchRegion = false; TalkBackService.getInstance()?.setModalTouchRegion(null) }
    simulator.clearHeld()
    val previous = surface; surface = null
    previous?.close()
    if (previous != null) content.removeView(previous)
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }
  private fun announce(message: String) {
    if (TalkBackService.getInstance() != null) NvdaRemoteSpeech.speak(message, true) else content.announceForAccessibility(message)
  }
  private fun vibrate(milliseconds: Long) {
    getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
  }
  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) { updateTouchRegion(); content.post { announcePanel() } }
    else if (surface != null && dialog == null && page == Page.PRACTICE) {
      simulator.clearInput(); showIntroduction(reset = false)
    }
    else if (ownsTouchRegion) { ownsTouchRegion = false; TalkBackService.getInstance()?.setModalTouchRegion(null) }
  }
  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putString("lesson", lesson?.id); outState.putInt("step", stepIndex); outState.putBoolean("free", freePractice)
    outState.putString("page", page.name); outState.putString("result", resultText)
    outState.putString("instruction", resultInstruction); outState.putBundle("simulation", simulator.save())
  }
  override fun onResume() {
    super.onResume(); resumed = true; active = this
    if (page == Page.RESULT) showResult()
    content.post { announcePanel() }
  }
  override fun onPause() {
    resumed = false
    if (active === this) active = null
    simulator.clearInput()
    removeSurface()
    dialog?.setOnDismissListener(null); dialog?.dismiss(); dialog = null
    if (page == Page.PRACTICE && !isFinishing) showIntroduction(reset = false)
    super.onPause()
  }
  companion object {
    private var active: NvdaRemoteTutorialActivity? = null
    @JvmStatic fun handleGesture(): Boolean = active?.acceptsInput() == true
  }
}
