/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.web

import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Region
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.focusmanagement.action.NavigationAction
import com.google.android.accessibility.talkback.remote.NvdaRemoteGesture
import com.google.android.accessibility.talkback.remote.NvdaRemoteInput
import com.google.android.accessibility.talkback.remote.NvdaRemoteInputFactory
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.KeyboardUtils
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.WebInterfaceUtils
import com.google.android.accessibility.utils.input.CursorGranularity
import com.google.android.accessibility.utils.monitor.InputModeTracker.INPUT_MODE_BRAILLE_KEYBOARD
import com.google.android.accessibility.utils.monitor.ScreenMonitor
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions
import com.google.android.accessibility.utils.traversal.TraversalStrategy.SEARCH_FOCUS_BACKWARD
import com.google.android.accessibility.utils.traversal.TraversalStrategy.SEARCH_FOCUS_FORWARD
import com.google.android.libraries.accessibility.utils.log.LogUtils

/** Raw braille over the original browser window. Web actions still go through TalkBack's pipeline. */
class BrailleWebNavigationController(
  private val service: TalkBackService,
  private val pipeline: Pipeline.FeedbackReturner,
) : NvdaRemoteInput.Listener {
  private val windows = service.getSystemService(WindowManager::class.java)
  private val handler = Handler(Looper.getMainLooper())
  private val bindings = BrailleWebBindings(service)
  private var view: NvdaRemoteInput? = null
  private var dialog: AlertDialog? = null
  private var originWindowId: Int? = null
  private var pivot: AccessibilityNodeInfoCompat? = null
  private var lastTarget: BrailleWebTarget? = null
  private var capture: BrailleWebAction? = null
  private var previousKeyboardMode: Int? = null
  private var generation = 0
  private var backCaptured = false
  private var busy = false
  val isShowing: Boolean get() = view != null
  private val active: Boolean get() = originWindowId != null

  fun show(): Boolean {
    if (active) { dismiss(); return true }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || ScreenMonitor.isDeviceLocked(service)) {
      speak(R.string.braille_web_unavailable)
      return false
    }
    val window = activeAppWindow() ?: return unavailable()
    val root = window.root?.let(AccessibilityNodeInfoCompat::wrap) ?: return unavailable()
    val web = findWeb(root) ?: return unavailable()
    originWindowId = window.id
    pivot = root.findFocus(AccessibilityNodeInfoCompat.FOCUS_ACCESSIBILITY)
      ?.takeIf { WebInterfaceUtils.supportsWebActions(it) } ?: web
    previousKeyboardMode = service.softKeyboardController.showMode
    service.softKeyboardController.setShowMode(AccessibilityService.SHOW_MODE_HIDDEN)
    if (!attachSurface()) return false
    speak(R.string.braille_web_opened)
    return true
  }

  private fun unavailable(): Boolean { speak(R.string.braille_web_no_page); return false }

  private fun activeAppWindow(): AccessibilityWindowInfo? {
    val apps = service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
    // Touching our overlay can make it active while the browser still owns input focus.
    return apps.firstOrNull { it.isActive } ?: apps.firstOrNull { it.isFocused }
  }

  private fun root(): AccessibilityNodeInfoCompat? =
    service.windows.firstOrNull { it.id == originWindowId }?.root
      ?.let(AccessibilityNodeInfoCompat::wrap)

  private fun findWeb(root: AccessibilityNodeInfoCompat): AccessibilityNodeInfoCompat? {
    val queue = java.util.ArrayDeque<AccessibilityNodeInfoCompat>()
    val visited = HashSet<AccessibilityNodeInfoCompat>()
    queue.add(root)
    while (queue.isNotEmpty() && visited.size < 3000) {
      val node = queue.removeFirst()
      if (!visited.add(node)) continue
      if (WebInterfaceUtils.supportsWebActions(node) && AccessibilityNodeInfoUtils.isVisible(node))
        return node
      for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
    }
    return null
  }

  private fun currentPivot(): AccessibilityNodeInfoCompat? {
    val root = root() ?: return null
    root.findFocus(AccessibilityNodeInfoCompat.FOCUS_ACCESSIBILITY)?.let {
      if (WebInterfaceUtils.supportsWebActions(it)) { pivot = it; return it }
    }
    pivot?.let {
      if (it.refresh() && it.windowId == originWindowId &&
          WebInterfaceUtils.supportsWebActions(it) && AccessibilityNodeInfoUtils.isVisible(it)) return it
    }
    return findWeb(root)?.also { pivot = it }
  }

  private fun attachSurface(): Boolean {
    if (!active || view != null || dialog != null) return false
    if (ScreenMonitor.isDeviceLocked(service) || activeAppWindow()?.id != originWindowId) {
      dismiss(false)
      return false
    }
    return try {
      val bounds = windows.currentWindowMetrics.bounds
      val input = NvdaRemoteInputFactory.create(service, Size(bounds.width(), bounds.height()), this)
        ?: run { dismiss(false); return false }
      view = input
      input.setBackgroundColor(Color.BLACK)
      input.setDescription(capture?.let {
        service.getString(R.string.braille_web_capture, service.getString(it.label))
      } ?: service.getString(R.string.braille_web_surface))
      val params = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        width = bounds.width()
        height = bounds.height()
        format = PixelFormat.OPAQUE
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        gravity = Gravity.TOP or Gravity.LEFT
        title = service.getString(R.string.braille_web_title)
      }
      windows.addView(input, params)
      service.setModalTouchRegion(Region(bounds))
      true
    } catch (error: RuntimeException) {
      LogUtils.w("BrailleWebNavigation", "Cannot attach surface: %s", error.javaClass.simpleName)
      dismiss(false)
      speak(R.string.braille_web_failed)
      false
    }
  }

  private fun removeSurface() {
    val input = view ?: return
    view = null
    input.close()
    service.setModalTouchRegion(null)
    try { windows.removeViewImmediate(input) } catch (_: IllegalArgumentException) { }
  }

  override fun onGesture(gesture: NvdaRemoteGesture) {
    if (!isShowing || busy) return
    if (ScreenMonitor.isDeviceLocked(service) || activeAppWindow()?.id != originWindowId) {
      dismiss(false)
      return
    }
    capture?.let { action ->
      if (gesture.dots != null || gesture.holdOnly || bindings.isReserved(gesture)) {
        speak(R.string.braille_web_capture_swipe)
      } else {
        capture = null
        removeSurface()
        val previous = bindings.resolve(gesture)
        val message = service.getString(R.string.braille_web_confirm_gesture,
          gesture.label(service), service.getString(action.label),
          previous?.let { service.getString(it.label) } ?: service.getString(R.string.braille_web_unassigned))
        showDialog(AlertDialog.Builder(dialogContext()).setTitle(R.string.braille_web_assign)
          .setMessage(message).setPositiveButton(android.R.string.ok) { _, _ ->
            bindings.assign(gesture, action)
          }.setNegativeButton(android.R.string.cancel, null))
      }
      return
    }
    val command = bindings.resolve(gesture)
    gesture.dots?.takeIf { command == null }?.let { dots ->
      val target = BrailleWebTarget.fromDots(dots)
      if (target == null) speak(R.string.braille_web_unknown_letter)
      else { lastTarget = target; navigate(target, true) }
      return
    }
    when (command) {
      BrailleWebAction.PREVIOUS -> repeat(false)
      BrailleWebAction.NEXT -> repeat(true)
      BrailleWebAction.PREVIOUS_ITEM -> linear(false)
      BrailleWebAction.NEXT_ITEM -> linear(true)
      BrailleWebAction.TOP -> jumpToBoundary(true)
      BrailleWebAction.BOTTOM -> jumpToBoundary(false)
      BrailleWebAction.ACTIVATE -> activate()
      BrailleWebAction.MENU -> showMenu()
      BrailleWebAction.EXIT -> dismiss()
      null -> speak(R.string.braille_web_unknown_gesture)
    }
  }

  private fun repeat(forward: Boolean) {
    val target = lastTarget
    if (target == null) speak(R.string.braille_web_choose_letter) else navigate(target, forward)
  }

  private fun navigate(target: BrailleWebTarget, forward: Boolean) {
    val start = currentPivot() ?: run { dismiss(false); speak(R.string.braille_web_no_page); return }
    val navigation = NavigationAction.Builder().setAction(NavigationAction.DIRECTIONAL_NAVIGATION)
      .setDirection(if (forward) SEARCH_FOCUS_FORWARD else SEARCH_FOCUS_BACKWARD)
      .setTarget(target.target).setInputMode(INPUT_MODE_BRAILLE_KEYBOARD)
      .setShouldWrap(false).setShouldScroll(true).build()
    // WebActor announces the start/end for this element type on failure, without wrapping.
    pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.webDirectionHtml(start, navigation))
  }

  private fun linear(forward: Boolean) {
    pipeline.returnFeedback(EVENT_ID_UNTRACKED,
      Feedback.focusDirection(if (forward) SEARCH_FOCUS_FORWARD else SEARCH_FOCUS_BACKWARD)
        .setGranularity(CursorGranularity.DEFAULT)
        .setInputMode(INPUT_MODE_BRAILLE_KEYBOARD).setWrap(false).setScroll(true)
        .setDefaultToInputFocus(false))
  }

  private fun jumpToBoundary(top: Boolean) {
    if (currentPivot() == null) {
      dismiss(false)
      speak(R.string.braille_web_no_page)
      return
    }
    val feedback = if (top) Feedback.focusTop(INPUT_MODE_BRAILLE_KEYBOARD)
      else Feedback.focusBottom(INPUT_MODE_BRAILLE_KEYBOARD)
    if (!pipeline.returnFeedback(EVENT_ID_UNTRACKED, feedback)) {
      speak(R.string.braille_web_jump_failed)
    }
  }

  private fun activate() {
    val node = root()?.findFocus(AccessibilityNodeInfoCompat.FOCUS_ACCESSIBILITY)
      ?: pivot ?: run { speak(R.string.braille_web_activation_failed); return }
    var target: AccessibilityNodeInfoCompat? = node
    val seen = HashSet<AccessibilityNodeInfoCompat>()
    while (target != null && seen.add(target) && seen.size < 50 &&
        target.windowId == originWindowId && !target.isEditable && !target.isClickable &&
        target.actionList.none { it.id == AccessibilityNodeInfoCompat.ACTION_CLICK }) target = target.parent
    val chosen = target?.takeIf { it.windowId == originWindowId && it.isEnabled &&
      AccessibilityNodeInfoUtils.isVisible(it) &&
      (it.isEditable || it.isClickable || it.actionList.any { a -> a.id == AccessibilityNodeInfoCompat.ACTION_CLICK }) }
      ?: run { speak(R.string.braille_web_activation_failed); return }
    val editing = chosen.isEditable
    val ime = if (editing) KeyboardUtils.getEnabledImeId(service, service.packageName) else ""
    if (editing && ime.isNullOrEmpty()) { speak(R.string.braille_web_enable_keyboard); return }
    busy = true
    removeSurface()
    val token = generation
    handler.postDelayed({
      if (token != generation || !active) return@postDelayed
      if (ScreenMonitor.isDeviceLocked(service) || activeAppWindow()?.id != originWindowId ||
          !chosen.refresh() || !chosen.isEnabled || !AccessibilityNodeInfoUtils.isVisible(chosen)) {
        dismiss(false)
        return@postDelayed
      }
      if (editing) {
        // Release ownership before the real IME takes over; no text is committed by navigation.
        dismiss(false)
        service.softKeyboardController.setShowMode(AccessibilityService.SHOW_MODE_AUTO)
        if (!service.softKeyboardController.switchToInputMethod(ime)) {
          speak(R.string.braille_web_enable_keyboard)
          return@postDelayed
        }
        pipeline.returnFeedback(EVENT_ID_UNTRACKED,
          Feedback.nodeAction(chosen, AccessibilityNodeInfoCompat.ACTION_FOCUS))
      }
      val clicked = pipeline.returnFeedback(EVENT_ID_UNTRACKED,
        Feedback.nodeAction(chosen, AccessibilityNodeInfoCompat.ACTION_CLICK))
      if (editing) {
        speak(if (clicked) R.string.braille_web_writing else R.string.braille_web_activation_failed)
      } else {
        busy = false
        if (!clicked) speak(R.string.braille_web_activation_failed)
        handler.postDelayed({ if (token == generation && active) attachSurface() }, 250)
      }
    }, 150)
  }

  private fun dialogContext() = ContextThemeWrapper(service, android.R.style.Theme_Material_Dialog_Alert)

  private fun showDialog(builder: AlertDialog.Builder) {
    removeSurface()
    val token = generation
    val created = builder.create()
    dialog = created
    created.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
    created.setCanceledOnTouchOutside(false)
    created.setOnDismissListener {
      if (dialog === created) dialog = null
      handler.postDelayed({
        if (token == generation && active && dialog == null && view == null) attachSurface()
      }, 150)
    }
    created.show()
  }

  private fun showMenu() {
    val labels = arrayOf(service.getString(R.string.braille_web_help),
      service.getString(R.string.braille_web_assign), service.getString(R.string.braille_web_reset),
      service.getString(R.string.braille_web_exit))
    showDialog(AlertDialog.Builder(dialogContext()).setTitle(R.string.braille_web_title)
      .setItems(labels) { _, which -> when (which) {
        0 -> showDialog(AlertDialog.Builder(dialogContext()).setTitle(R.string.braille_web_help)
          .setMessage(R.string.braille_web_help_body).setPositiveButton(android.R.string.ok, null))
        1 -> showDialog(AlertDialog.Builder(dialogContext()).setTitle(R.string.braille_web_assign)
          .setItems(BrailleWebAction.entries.map { service.getString(it.label) }.toTypedArray()) { _, index ->
            capture = BrailleWebAction.entries[index]
            speak(service.getString(R.string.braille_web_capture, service.getString(capture!!.label)))
          }.setNegativeButton(android.R.string.cancel, null))
        2 -> showDialog(AlertDialog.Builder(dialogContext()).setTitle(R.string.braille_web_reset)
          .setMessage(R.string.braille_web_reset_question)
          .setPositiveButton(android.R.string.ok) { _, _ -> bindings.reset() }
          .setNegativeButton(android.R.string.cancel, null))
        3 -> dismiss()
      } }.setNegativeButton(R.string.braille_web_resume, null))
  }

  override fun onAnnouncement(text: String) { if (active) speak(text) }
  override fun onHold(dots: String) = false
  override fun onHeldDotsChanged(dots: String) {}

  fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (!active) return
    if (ScreenMonitor.isDeviceLocked(service)) { dismiss(false); return }
    if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED &&
        event.windowId == originWindowId) {
      event.source?.let(AccessibilityNodeInfoCompat::wrap)?.let {
        if (WebInterfaceUtils.supportsWebActions(it)) pivot = it
      }
    }
    if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
        event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
      val current = activeAppWindow()
      if (current != null && current.id != originWindowId) dismiss(false)
    }
  }

  fun onKeyEvent(event: KeyEvent): Boolean {
    if (event.keyCode != KeyEvent.KEYCODE_BACK || (!isShowing && !backCaptured)) return false
    if (event.action == KeyEvent.ACTION_DOWN) backCaptured = true
    if (event.action == KeyEvent.ACTION_UP && backCaptured) {
      backCaptured = false
      if (capture != null) { capture = null; removeSurface(); showMenu() } else dismiss()
    }
    return true
  }

  @JvmOverloads fun dismiss(announce: Boolean = true) {
    val wasActive = active
    generation++
    handler.removeCallbacksAndMessages(null)
    originWindowId = null
    capture = null
    pivot = null
    lastTarget = null
    busy = false
    val oldDialog = dialog
    dialog = null
    oldDialog?.dismiss()
    removeSurface()
    previousKeyboardMode?.let { service.softKeyboardController.setShowMode(it) }
    previousKeyboardMode = null
    if (wasActive && announce) speak(R.string.braille_web_closed)
  }

  fun shutdown() = dismiss(false)

  private fun speak(resource: Int) = speak(service.getString(resource))
  private fun speak(text: String) {
    pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.speech(text,
      SpeakOptions.create().setQueueMode(SpeechController.QUEUE_MODE_INTERRUPT)
        .setFlags(FeedbackItem.FLAG_NO_HISTORY or FeedbackItem.FLAG_FORCE_FEEDBACK_ALL)))
  }
}
