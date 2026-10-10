/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Region
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.view.WindowManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.clipboard.ClipboardHistoryView.Action
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.monitor.ScreenMonitor
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions
import com.google.android.libraries.accessibility.utils.log.LogUtils

class ClipboardHistoryController(
  private val service: TalkBackService,
  private val pipeline: Pipeline.FeedbackReturner,
  private val focus: AccessibilityFocusMonitor,
) {
  private val clipboard = service.getSystemService(ClipboardManager::class.java)
  private val windows = service.getSystemService(WindowManager::class.java)
  private val handler = Handler(Looper.getMainLooper())
  private var store: ClipboardHistoryStore? = null
  private var view: ClipboardHistoryView? = null
  private var origin: AccessibilityNodeInfoCompat? = null
  private var favorites = false
  private var index = 0
  private var itemAnnounced = false
  private var items = emptyList<ClipboardHistoryStore.Entry>()
  private var ownCopy: String? = null
  private var originWindowId: Int? = null
  private var backCaptured = false
  private var restoringBackup = false
  private val listener = ClipboardManager.OnPrimaryClipChangedListener { capture() }
  val isShowing: Boolean get() = view != null

  init {
    clipboard.addPrimaryClipChangedListener(listener)
    capture()
  }

  fun beginBackupRestore() {
    restoringBackup = true
    dismiss()
  }

  fun finishBackupRestore() {
    store = null
    items = emptyList()
    restoringBackup = false
  }

  private fun history(): ClipboardHistoryStore? {
    if (!service.getSystemService(UserManager::class.java).isUserUnlocked) return null
    if (store == null) store = ClipboardHistoryStore(
      service.applicationContext
        .getSharedPreferences("clipboard_history", Context.MODE_PRIVATE))
    return store
  }

  private fun capture() {
    if (restoringBackup) return
    if (ScreenMonitor.isDeviceLocked(service)) return
    val history = history() ?: return
    try {
      val clip = clipboard.primaryClip ?: return
      if (clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return
      val text = clip.getItemAt(0).text?.toString() ?: return
      if (ownCopy == text) { ownCopy = null; return }
      history.record(text)
    } catch (_: RuntimeException) {
      // Android may deny reading when another keyboard is the default. Keep existing entries.
    }
  }

  fun show(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || ScreenMonitor.isDeviceLocked(service)) {
      speak(service.getString(R.string.clipboard_history_unavailable))
      return false
    }
    hide(announceClosure = false)
    capture()
    origin = (focus.getInputFocus()?.takeIf { it.isEditable }
      ?: focus.getAccessibilityFocus(true)?.takeIf { it.isEditable })
      ?.let { AccessibilityNodeInfoCompat.obtain(it) }
    originWindowId = service.windows.firstOrNull {
      it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
    }?.id ?: origin?.windowId
    favorites = false
    index = 0
    itemAnnounced = false
    items = history()?.items().orEmpty()
    val browser = ClipboardHistoryView(service, ::onAction)
    val params = WindowManager.LayoutParams().apply {
      type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
      width = WindowManager.LayoutParams.MATCH_PARENT
      height = WindowManager.LayoutParams.MATCH_PARENT
      format = PixelFormat.OPAQUE
      flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
      title = service.getString(R.string.clipboard_history_title)
    }
    try {
      windows.addView(browser, params)
      view = browser
      service.setClipboardHistoryTouchRegion(Region(windows.currentWindowMetrics.bounds))
    } catch (e: RuntimeException) {
      LogUtils.w("ClipboardHistory", "Cannot show history: %s", e.javaClass.simpleName)
      hide(announceClosure = false)
      speak(service.getString(R.string.clipboard_history_unavailable))
      return false
    }
    updateView()
    speak(service.getString(R.string.clipboard_history_opened))
    return true
  }

  private fun onAction(action: Action) {
    if (!isShowing) return
    when (action) {
      Action.PREVIOUS -> {
        if (itemAnnounced) index = (index - 1).coerceAtLeast(0)
        announce()
      }
      Action.NEXT -> {
        if (itemAnnounced) index = (index + 1).coerceAtMost((items.size - 1).coerceAtLeast(0))
        announce()
      }
      Action.PREVIOUS_CATEGORY, Action.NEXT_CATEGORY -> {
        // With two categories, either direction wraps to the other one.
        favorites = !favorites
        index = 0
        itemAnnounced = false
        items = history()?.items(favorites).orEmpty()
        updateView()
        speak(category())
      }
      Action.COPY -> {
        val entry = selected()
        hide(announceClosure = false)
        speakClosed(service.getString(when {
          entry == null -> R.string.clipboard_history_empty
          copy(entry.text) -> R.string.clipboard_history_copied
          else -> R.string.clipboard_history_copy_failed
        }))
      }
      Action.PASTE -> paste()
      Action.FAVORITE -> {
        val entry = selected()
        if (entry == null) { speak(service.getString(R.string.clipboard_history_empty)); return }
        val favorite = history()?.toggleFavorite(entry.text)
        if (favorite == null) { speak(service.getString(R.string.clipboard_history_favorites_full)); return }
        items = history()?.items(favorites).orEmpty()
        index = items.indexOfFirst { it.text == entry.text }.takeIf { i -> i >= 0 }
          ?: index.coerceAtMost((items.size - 1).coerceAtLeast(0))
        announce(service.getString(if (favorite) R.string.clipboard_history_favorite_added
          else R.string.clipboard_history_favorite_removed))
      }
      Action.CLOSE -> dismiss()
    }
  }

  private fun selected(): ClipboardHistoryStore.Entry? = items.getOrNull(index)

  private fun copy(text: String): Boolean = try {
    ownCopy = text
    clipboard.setPrimaryClip(ClipData.newPlainText("can-talk", text))
    true
  } catch (_: RuntimeException) {
    ownCopy = null
    false
  }

  private fun paste() {
    val entry = selected()
    val target = origin
    // Close on every paste attempt, including an empty list or a field that has disappeared.
    hide(announceClosure = false)
    if (entry == null) { speakClosed(service.getString(R.string.clipboard_history_empty)); return }
    if (target == null || !target.refresh() || !target.isEditable) {
      speakClosed(service.getString(R.string.clipboard_history_no_editor))
      return
    }
    if (!copy(entry.text)) { speakClosed(service.getString(R.string.clipboard_history_copy_failed)); return }
    // Wait for the overlay to disappear; never redirect paste to a different field or app.
    handler.postDelayed({
      if (ScreenMonitor.isDeviceLocked(service) || !target.refresh()
          || !target.isEditable || focus.getInputFocus() != target) {
        speakClosed(service.getString(R.string.clipboard_history_no_editor))
      } else {
        if (!copy(entry.text)) {
          speakClosed(service.getString(R.string.clipboard_history_copy_failed))
          return@postDelayed
        }
        val pasted = target.performAction(AccessibilityNodeInfoCompat.ACTION_PASTE)
        speakClosed(service.getString(if (pasted) R.string.clipboard_history_pasted
          else R.string.clipboard_history_paste_failed), protectFromNewSpeech = true)
      }
    }, 300)
  }

  private fun category(): String = service.getString(
    if (favorites) R.string.clipboard_history_favorites else R.string.clipboard_history_general)

  private fun updateView() {
    val entry = items.getOrNull(index)
    val description = if (entry == null) service.getString(R.string.clipboard_history_empty)
      else service.getString(R.string.clipboard_history_item, category(), index + 1, items.size,
        entry.text.take(500), if (entry.favorite) service.getString(R.string.clipboard_history_favorite) else "")
    view?.text = service.getString(R.string.clipboard_history_title) + "\n\n" + description +
      "\n\n" + service.getString(R.string.clipboard_history_hint)
  }

  private fun announce(prefix: String = "") {
    itemAnnounced = true
    updateView()
    val entry = selected()
    val spoken = if (entry == null) service.getString(R.string.clipboard_history_empty)
      else service.getString(R.string.clipboard_history_spoken_item, entry.text.take(500), index + 1, items.size)
    speak(listOf(prefix, spoken).filter { it.isNotBlank() }.joinToString(". "))
  }

  private fun speakClosed(result: String = "", protectFromNewSpeech: Boolean = false) {
    speak(listOf(service.getString(R.string.clipboard_history_closed), result)
      .filter { it.isNotBlank() }.joinToString(". "), protectFromNewSpeech)
  }

  private fun speak(text: String, protectFromNewSpeech: Boolean = false) {
    // Pasting emits text-change speech. Keep its short result audible through that feedback,
    // while still allowing the user's explicit stop-speech command to interrupt it.
    val queueMode = if (protectFromNewSpeech)
      SpeechController.QUEUE_MODE_INTERRUPT_AND_UNINTERRUPTIBLE_BY_NEW_SPEECH
      else SpeechController.QUEUE_MODE_INTERRUPT
    pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.speech(text,
      SpeakOptions.create().setQueueMode(queueMode)
        .setFlags(FeedbackItem.FLAG_NO_HISTORY or FeedbackItem.FLAG_FORCE_FEEDBACK_ALL)))
  }

  fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (ScreenMonitor.isDeviceLocked(service)) { dismiss(); return }
    if (isShowing && (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
      val active = service.windows.firstOrNull {
        it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
      }?.id
      if (active != null && originWindowId != null && active != originWindowId) dismiss()
    }
    if (store == null) capture()
  }

  fun onKeyEvent(event: KeyEvent): Boolean {
    if (event.keyCode != KeyEvent.KEYCODE_BACK || (!isShowing && !backCaptured)) return false
    if (event.action == KeyEvent.ACTION_DOWN) backCaptured = true
    if (event.action == KeyEvent.ACTION_UP && backCaptured) {
      backCaptured = false
      if (isShowing) onAction(Action.CLOSE)
    }
    return true
  }

  fun dismiss() = hide(announceClosure = true)

  private fun hide(announceClosure: Boolean) {
    handler.removeCallbacksAndMessages(null)
    origin = null
    originWindowId = null
    val browser = view ?: return
    view = null
    browser.cancelPendingTouches()
    service.setClipboardHistoryTouchRegion(null)
    try { windows.removeViewImmediate(browser) } catch (_: IllegalArgumentException) { }
    if (announceClosure) speakClosed()
  }

  fun shutdown() {
    hide(announceClosure = false)
    clipboard.removePrimaryClipChangedListener(listener)
  }
}
