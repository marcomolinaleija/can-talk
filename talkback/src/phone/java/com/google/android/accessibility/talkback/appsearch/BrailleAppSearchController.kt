/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.appsearch

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.PixelFormat
import android.graphics.Region
import android.os.Build
import android.util.Size
import android.view.KeyEvent
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.accessibility.brailleime.search.BrailleAppSearchInput
import com.google.android.accessibility.brailleime.search.BrailleAppSearchInput.Action
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.monitor.ScreenMonitor
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions
import com.google.android.libraries.accessibility.utils.log.LogUtils

/** A modal braille launcher independent of the active keyboard and current text editor. */
class BrailleAppSearchController @JvmOverloads constructor(
  private val service: TalkBackService,
  private val pipeline: Pipeline.FeedbackReturner,
  private val createInput: (Size, BrailleAppSearchInput.Listener) -> BrailleAppSearchInput =
    { size, listener -> BrailleAppSearchInput(service, size, listener) },
) : BrailleAppSearchInput.Listener {
  private val windows = service.getSystemService(WindowManager::class.java)
  private var view: BrailleAppSearchInput? = null
  private var results = AppSearchResults(emptyList())
  private var originWindowId: Int? = null
  private var backCaptured = false
  val isShowing: Boolean get() = view != null

  fun show(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || ScreenMonitor.isDeviceLocked(service)) {
      speak(service.getString(R.string.braille_app_search_unavailable))
      return false
    }
    hide(false)
    try {
      results = AppSearchResults(loadApps())
      val bounds = windows.currentWindowMetrics.bounds
      val input = createInput(Size(bounds.width(), bounds.height()), this)
      originWindowId = activeAppWindowId()
      val params = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        width = bounds.width()
        height = bounds.height()
        format = PixelFormat.OPAQUE
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        gravity = Gravity.TOP or Gravity.LEFT
        title = service.getString(R.string.braille_app_search_title)
      }
      // Record before attaching, so every failure can release the view and its sensor listeners.
      view = input
      windows.addView(input, params)
      service.setModalTouchRegion(Region(bounds))
      updateView()
      speak(service.getString(R.string.braille_app_search_opened))
      return true
    } catch (e: RuntimeException) {
      LogUtils.w("BrailleAppSearch", "Cannot open search: %s", e.javaClass.simpleName)
      hide(false)
      speak(service.getString(R.string.braille_app_search_failed))
      return false
    }
  }

  @Suppress("DEPRECATION")
  private fun loadApps(): List<AppSearchResults.App> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val manager = service.packageManager
    return manager.queryIntentActivities(intent, 0)
      .mapNotNull { resolved ->
        val activity = resolved.activityInfo ?: return@mapNotNull null
        if (!activity.enabled || !activity.exported || !activity.applicationInfo.enabled ||
            activity.applicationInfo.flags and ApplicationInfo.FLAG_SUSPENDED != 0) return@mapNotNull null
        AppSearchResults.App(resolved.loadLabel(manager).toString(), activity.packageName, activity.name)
      }
  }

  override fun onQueryChanged(query: String) {
    if (!isShowing) return
    results.search(query)
    announceResult()
  }

  override fun onAction(action: Action) {
    if (!isShowing) return
    when (action) {
      Action.PREVIOUS -> { results.move(-1); announceResult() }
      Action.NEXT -> { results.move(1); announceResult() }
      Action.OPEN -> openSelected()
      Action.CLOSE -> dismiss()
      Action.HELP -> speak(service.getString(R.string.braille_app_search_hint))
    }
  }

  override fun onAnnouncement(text: String) { if (isShowing) speak(text) }

  private fun resultText(): String {
    val app = results.selected
    return when {
      results.query.isBlank() -> service.getString(R.string.braille_app_search_type_name)
      app == null -> service.getString(R.string.braille_app_search_no_results, results.query)
      else -> service.getString(R.string.braille_app_search_result,
        app.label, results.index + 1, results.matches.size)
    }
  }

  private fun updateView() {
    view?.setDescription(service.getString(R.string.braille_app_search_title) + "\n" +
      results.query + "\n" + resultText())
  }

  private fun announceResult() { updateView(); speak(resultText()) }

  private fun openSelected() {
    if (ScreenMonitor.isDeviceLocked(service)) { dismiss(); return }
    val app = results.selected ?: run { announceResult(); return }
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
      .setComponent(ComponentName(app.packageName, app.className))
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    // The selected component is explicit: never open a different app when a result disappears.
    hide(false)
    try {
      service.startActivity(intent)
    } catch (e: RuntimeException) {
      LogUtils.w("BrailleAppSearch", "Cannot launch result: %s", e.javaClass.simpleName)
      speak(service.getString(R.string.braille_app_search_launch_failed, app.label))
    }
  }

  private fun activeAppWindowId(): Int? = service.windows.firstOrNull {
    it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
  }?.id

  fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (!isShowing) return
    if (ScreenMonitor.isDeviceLocked(service)) { dismiss(); return }
    if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
        event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
      val active = activeAppWindowId()
      if (active != null && originWindowId != null && active != originWindowId) dismiss()
    }
  }

  fun onKeyEvent(event: KeyEvent): Boolean {
    if (event.keyCode != KeyEvent.KEYCODE_BACK || (!isShowing && !backCaptured)) return false
    if (event.action == KeyEvent.ACTION_DOWN) backCaptured = true
    if (event.action == KeyEvent.ACTION_UP && backCaptured) {
      backCaptured = false
      dismiss()
    }
    return true
  }

  fun dismiss() = hide(true)
  fun shutdown() = hide(false)

  private fun hide(announce: Boolean) {
    val input = view ?: return
    view = null
    originWindowId = null
    input.close()
    service.setModalTouchRegion(null)
    try { windows.removeViewImmediate(input) } catch (_: IllegalArgumentException) { }
    results = AppSearchResults(emptyList())
    if (announce) speak(service.getString(R.string.braille_app_search_closed))
  }

  private fun speak(text: String) {
    pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.speech(text,
      SpeakOptions.create().setQueueMode(SpeechController.QUEUE_MODE_INTERRUPT)
        .setFlags(FeedbackItem.FLAG_NO_HISTORY or FeedbackItem.FLAG_FORCE_FEEDBACK_ALL)))
  }
}
