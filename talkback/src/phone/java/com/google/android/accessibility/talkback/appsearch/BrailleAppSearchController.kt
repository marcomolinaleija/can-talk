/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.appsearch

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.PixelFormat
import android.graphics.Region
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.monitor.ScreenMonitor
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions
import com.google.android.libraries.accessibility.utils.log.LogUtils

/** Unified braille search independent of the active keyboard and current text editor. */
class BrailleAppSearchController @JvmOverloads constructor(
  private val service: TalkBackService,
  private val pipeline: Pipeline.FeedbackReturner,
  private val screenSearch: ScreenElementSearch = ScreenElementSearch.forService(service, pipeline),
  private val createInput: (Size, BrailleAppSearchInput.Listener) -> BrailleAppSearchInput =
    { size, listener -> BrailleAppSearchInput(service, size, listener) },
) : BrailleAppSearchInput.Listener {
  private val windows = service.getSystemService(WindowManager::class.java)
  private val prefs = SharedPreferencesUtils.getSharedPreferences(service)
  private val handler = Handler(Looper.getMainLooper())
  private enum class Mode { APPS, ELEMENTS }
  private var mode = Mode.APPS
  private var generation = 0
  private var elementsDirty = true
  private var hasAnnouncedResult = false
  private var view: BrailleAppSearchInput? = null
  private var cachedApps: List<AppSearchResults.App> = emptyList()
  private var appsLoaded = false
  private var results = AppSearchResults(emptyList())
  private var originWindowId: Int? = null
  private var backCaptured = false
  val isShowing: Boolean get() = view != null

  fun show(): Boolean {
    cancelPendingActivation()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || ScreenMonitor.isDeviceLocked(service)) {
      speak(service.getString(R.string.braille_app_search_unavailable))
      return false
    }
    hide(false)
    try {
      results = AppSearchResults(cachedApps)
      appsLoaded = false
      mode = if (prefs.getBoolean("pref_braille_search_elements_mode", false)) Mode.ELEMENTS else Mode.APPS
      hasAnnouncedResult = false
      originWindowId = activeAppWindowId()
      elementsDirty = true
      val bounds = windows.currentWindowMetrics.bounds
      val input = createInput(Size(bounds.width(), bounds.height()), this)
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
      speak(service.getString(R.string.braille_app_search_opened, modeName()))
      if (mode == Mode.APPS) {
        handler.post { if (isShowing) ensureAppsLoaded() }
      }
      return true
    } catch (e: RuntimeException) {
      LogUtils.w("BrailleAppSearch", "Cannot open search: %s", e.javaClass.simpleName)
      hide(false)
      speak(service.getString(R.string.braille_app_search_failed))
      return false
    }
  }

  private fun ensureAppsLoaded() {
    if (appsLoaded) return
    cachedApps = loadApps()
    appsLoaded = true
    val currentQuery = results.query
    results = AppSearchResults(cachedApps)
    if (currentQuery.isNotEmpty()) {
      results.search(currentQuery)
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
    if (mode == Mode.APPS) ensureAppsLoaded()
    results.search(query)
    if (mode == Mode.ELEMENTS) {
      if (elementsDirty) refreshElements() else screenSearch.search(query)
    }
    announceResult()
  }

  override fun onAction(action: Action) {
    if (!isShowing) return
    when (action) {
      Action.PREVIOUS -> { move(-1); announceResult() }
      Action.NEXT -> { move(1); announceResult() }
      Action.OPEN -> if (mode == Mode.APPS) openSelected() else activateElement()
      Action.CLOSE -> dismiss()
      Action.HELP -> speak(service.getString(R.string.braille_app_search_hint))
      Action.SWITCH_MODE -> {
        mode = if (mode == Mode.APPS) Mode.ELEMENTS else Mode.APPS
        prefs.edit().putBoolean("pref_braille_search_elements_mode", mode == Mode.ELEMENTS).apply()
        if (mode == Mode.ELEMENTS) {
          if (results.query.isNotBlank()) refreshElements() else elementsDirty = true
        } else {
          ensureAppsLoaded()
          results.search(results.query)
        }
        hasAnnouncedResult = false
        updateView()
        speak(modeName())
      }
    }
  }

  override fun onAnnouncement(text: String) { if (isShowing) speak(text) }

  private fun resultText(): String {
    if (mode == Mode.ELEMENTS) {
      val element = screenSearch.selected
      return when {
        results.query.isBlank() -> service.getString(R.string.braille_search_type_element)
        element == null -> service.getString(R.string.braille_search_no_elements, results.query)
        else -> service.getString(R.string.braille_app_search_result,
          element.label, screenSearch.index + 1, screenSearch.matches.size)
      }
    }
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
      modeName() + "\n" + results.query + "\n" + resultText())
  }

  private fun announceResult() {
    hasAnnouncedResult = true
    updateView(); speak(resultText())
  }

  private fun modeName() = service.getString(if (mode == Mode.APPS)
    R.string.braille_search_apps else R.string.braille_search_elements)

  private fun move(step: Int) {
    // Opening/switching only announces the mode; the first navigation reads the first match.
    if (!hasAnnouncedResult) return
    if (mode == Mode.APPS) results.move(step) else screenSearch.move(step)
  }

  private fun refreshElements() {
    screenSearch.load(originWindowId)
    screenSearch.search(results.query)
    elementsDirty = false
  }

  private fun activateElement() {
    if (ScreenMonitor.isDeviceLocked(service)) { dismiss(); return }
    val entry = screenSearch.selected ?: run { announceResult(); return }
    val originalWindow = originWindowId
    val token = generation
    hide(false)
    // Release the modal touch region before focusing/clicking. Never redirect to another window.
    handler.postDelayed({
      if (token != generation) return@postDelayed
      if (ScreenMonitor.isDeviceLocked(service) || activeAppWindowId() != originalWindow) {
        speak(service.getString(R.string.braille_search_element_unavailable))
        return@postDelayed
      }
      when (screenSearch.activate(entry)) {
        ScreenElementSearch.Activation.UNAVAILABLE -> speak(service.getString(R.string.braille_search_element_unavailable))
        ScreenElementSearch.Activation.FAILED -> speak(service.getString(R.string.braille_search_activation_failed))
        else -> { }
      }
    }, 150)
  }

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
    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && event.windowId == originWindowId)
      elementsDirty = true
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

  private fun cancelPendingActivation() { generation++; handler.removeCallbacksAndMessages(null) }
  fun dismiss() { cancelPendingActivation(); hide(true) }
  fun shutdown() { cancelPendingActivation(); hide(false) }

  private fun hide(announce: Boolean) {
    val input = view ?: return
    view = null
    originWindowId = null
    input.close()
    service.setModalTouchRegion(null)
    try { windows.removeViewImmediate(input) } catch (_: IllegalArgumentException) { }
    appsLoaded = false
    results = AppSearchResults(emptyList())
    screenSearch.clear()
    if (announce) speak(service.getString(R.string.braille_app_search_closed))
  }

  private fun speak(text: String) {
    pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.speech(text,
      SpeakOptions.create().setQueueMode(SpeechController.QUEUE_MODE_INTERRUPT)
        .setFlags(FeedbackItem.FLAG_NO_HISTORY or FeedbackItem.FLAG_FORCE_FEEDBACK_ALL)))
  }
}
