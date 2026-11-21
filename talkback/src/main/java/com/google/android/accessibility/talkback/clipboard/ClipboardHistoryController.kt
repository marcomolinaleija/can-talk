/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Region
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.clipboard.ClipboardHistoryView.Action
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.monitor.ScreenMonitor
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions
import com.google.android.accessibility.utils.preference.PreferencesActivity
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
  private var searchView: ClipboardSearchOverlayView? = null
  private var origin: AccessibilityNodeInfoCompat? = null
  private var originHadInputFocus = false
  private var favorites = false
  private var index = 0
  private var itemAnnounced = false
  private var items = emptyList<ClipboardHistoryStore.Entry>()
  private var ownCopy: String? = null
  private var originWindowId: Int? = null
  private var backCaptured = false
  private var restoringBackup = false
  private var pendingDeleteText: String? = null
  private val clearPendingDelete = Runnable { pendingDeleteText = null }
  private val listener = ClipboardManager.OnPrimaryClipChangedListener { capture() }

  /** True when either the gesture browser or the in-overlay search panel is visible. */
  val isShowing: Boolean get() = view != null || searchView != null

  /** True only when the raw-touch gesture surface owns the screen. */
  val isGestureOverlayActive: Boolean get() = view != null

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
    if (store == null) store = ClipboardHistoryStore.open(service)
    return store
  }

  /**
   * Records text copied directly by CAN Talk (e.g. copy last spoken phrase, AI description copy),
   * so history captures it even on Android 10+ when another keyboard is the default IME.
   */
  fun recordExternal(text: CharSequence?) {
    if (restoringBackup) return
    if (ScreenMonitor.isDeviceLocked(service)) return
    if (!ClipboardHistorySettings.isCaptureEnabled(service)) return
    val value = text?.toString() ?: return
    if (ownCopy == value) { ownCopy = null; return }
    history()?.record(value)
  }

  private fun capture() {
    if (restoringBackup) return
    if (ScreenMonitor.isDeviceLocked(service)) return
    if (!ClipboardHistorySettings.isCaptureEnabled(service)) return
    val history = history() ?: return
    try {
      val clip = clipboard.primaryClip ?: return
      if (clip.itemCount <= 0) return
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
    val inputOrigin = focus.getInputFocus()?.takeIf { it.isEditable }
    val a11yOrigin = if (inputOrigin == null) focus.getAccessibilityFocus(true)?.takeIf { it.isEditable } else null
    originHadInputFocus = inputOrigin != null
    origin = (inputOrigin ?: a11yOrigin)?.let { AccessibilityNodeInfoCompat.obtain(it) }
    originWindowId = activeAppWindowId() ?: origin?.windowId
    favorites = false
    index = 0
    itemAnnounced = false
    pendingDeleteText = null
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
    if (!isGestureOverlayActive) return
    if (action != Action.DELETE) cancelPendingDelete()
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
      Action.PASTE -> paste(selected(), fromSearchOverlay = false)
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
      Action.DELETE -> handleDeleteGesture()
      Action.SEARCH -> openSearchOverlay()
      Action.SETTINGS -> openSettings()
      Action.HELP -> speak(service.getString(R.string.clipboard_history_hint))
      Action.CLOSE -> dismiss()
    }
  }

  private fun handleDeleteGesture() {
    val entry = selected()
    if (entry == null) {
      speak(service.getString(R.string.clipboard_history_empty))
      return
    }
    if (pendingDeleteText != entry.text) {
      pendingDeleteText = entry.text
      handler.removeCallbacks(clearPendingDelete)
      handler.postDelayed(clearPendingDelete, DELETE_CONFIRM_TIMEOUT_MS)
      speak(service.getString(R.string.clipboard_history_delete_confirm_gesture))
      return
    }
    cancelPendingDelete()
    if (history()?.delete(entry.text) == true) {
      items = history()?.items(favorites).orEmpty()
      index = index.coerceAtMost((items.size - 1).coerceAtLeast(0))
      announce(service.getString(R.string.clipboard_history_deleted))
    }
  }

  private fun cancelPendingDelete() {
    pendingDeleteText = null
    handler.removeCallbacks(clearPendingDelete)
  }

  private fun openSearchOverlay() {
    val browser = view ?: return
    view = null
    browser.cancelPendingTouches()
    service.setClipboardHistoryTouchRegion(null)
    try { windows.removeViewImmediate(browser) } catch (_: IllegalArgumentException) { }

    val panel = ClipboardSearchOverlayView(
      serviceContext = service,
      canPaste = origin != null,
      initialFavoritesOnly = favorites,
      listener = object : ClipboardSearchOverlayView.Listener {
        override fun onQueryChanged(query: String, favoritesOnly: Boolean) {
          favorites = favoritesOnly
          refreshSearchOverlay(query, favoritesOnly)
        }

        override fun onPrimarySelect(entry: ClipboardHistoryStore.Entry) {
          if (origin != null) {
            paste(entry, fromSearchOverlay = true)
          } else {
            copyAndClose(entry)
          }
        }

        override fun onCopy(entry: ClipboardHistoryStore.Entry) {
          copyAndClose(entry)
        }

        override fun onToggleFavorite(entry: ClipboardHistoryStore.Entry) {
          val favorite = history()?.toggleFavorite(entry.text)
          if (favorite == null) {
            speak(service.getString(R.string.clipboard_history_favorites_full))
            return
          }
          speak(service.getString(if (favorite) R.string.clipboard_history_favorite_added
            else R.string.clipboard_history_favorite_removed))
          searchView?.let { refreshSearchOverlay(it.currentQuery, it.favoritesOnly) }
        }

        override fun onDelete(entry: ClipboardHistoryStore.Entry) {
          if (history()?.delete(entry.text) == true) {
            speak(service.getString(R.string.clipboard_history_deleted))
            searchView?.let { refreshSearchOverlay(it.currentQuery, it.favoritesOnly) }
          }
        }

        override fun onOpenSettings() {
          openSettings()
        }

        override fun onClose() {
          dismiss()
        }
      },
    )

    val params = WindowManager.LayoutParams().apply {
      type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
      width = WindowManager.LayoutParams.MATCH_PARENT
      height = WindowManager.LayoutParams.MATCH_PARENT
      format = PixelFormat.TRANSLUCENT
      flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
      softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
        WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
      title = service.getString(R.string.clipboard_history_search_title)
    }

    try {
      windows.addView(panel, params)
      searchView = panel
      refreshSearchOverlay("", favorites)
      panel.focusSearchField()
      speak(service.getString(R.string.clipboard_history_search_opened))
    } catch (e: RuntimeException) {
      LogUtils.w("ClipboardHistory", "Cannot open clipboard search: %s", e.javaClass.simpleName)
      hide(announceClosure = false)
      speak(service.getString(R.string.clipboard_history_unavailable))
    }
  }

  private fun refreshSearchOverlay(query: String, favoritesOnly: Boolean) {
    val h = history() ?: return
    val matches = h.search(query, favoritesOnly)
    val totalAll = h.items(false).size
    val totalFavorites = h.items(true).size
    searchView?.updateResults(matches, totalAll, totalFavorites)
  }

  private fun copyAndClose(entry: ClipboardHistoryStore.Entry) {
    hide(announceClosure = false)
    speakClosed(service.getString(
      if (copy(entry.text)) R.string.clipboard_history_copied
      else R.string.clipboard_history_copy_failed
    ))
  }

  private fun openSettings() {
    hide(announceClosure = false)
    try {
      val intent = Intent(service, TalkBackPreferencesActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        putExtra(PreferencesActivity.FRAGMENT_NAME, ClipboardHistoryFragment::class.java.name)
      }
      service.startActivity(intent)
    } catch (e: RuntimeException) {
      LogUtils.w("ClipboardHistory", "Cannot open clipboard settings: %s", e.javaClass.simpleName)
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

  private fun paste(entry: ClipboardHistoryStore.Entry?, fromSearchOverlay: Boolean) {
    val target = origin
    val hadInputFocus = originHadInputFocus
    val savedWindowId = originWindowId
    // Close on every paste attempt, including an empty list or a field that has disappeared.
    hide(announceClosure = false)
    if (entry == null) { speakClosed(service.getString(R.string.clipboard_history_empty)); return }
    if (target == null || !target.refresh() || !target.isEditable) {
      speakClosed(service.getString(R.string.clipboard_history_no_editor))
      return
    }
    if (!copy(entry.text)) { speakClosed(service.getString(R.string.clipboard_history_copy_failed)); return }
    // Wait for the overlay (and IME if searching) to disappear; never redirect paste to another field/app.
    val delayMs = if (fromSearchOverlay) 350L else 300L
    handler.postDelayed({
      if (ScreenMonitor.isDeviceLocked(service) || !target.refresh() || !target.isEditable) {
        speakClosed(service.getString(R.string.clipboard_history_no_editor))
        return@postDelayed
      }
      val currentInput = focus.getInputFocus()
      val validTarget = when {
        fromSearchOverlay -> {
          val activeWindow = activeAppWindowId()
          val sameWindow = savedWindowId == null || activeWindow == null || activeWindow == savedWindowId
          val notAnotherEditor = currentInput == null || currentInput == target
          sameWindow && notAnotherEditor
        }
        hadInputFocus -> currentInput == target
        else -> currentInput == target ||
          (currentInput == null && focus.getAccessibilityFocus(false) == target)
      }
      if (!validTarget) {
        speakClosed(service.getString(R.string.clipboard_history_no_editor))
      } else {
        if (currentInput != target) {
          target.performAction(AccessibilityNodeInfoCompat.ACTION_FOCUS)
        }
        if (!copy(entry.text)) {
          speakClosed(service.getString(R.string.clipboard_history_copy_failed))
          return@postDelayed
        }
        val pasted = target.performAction(AccessibilityNodeInfoCompat.ACTION_PASTE)
        speakClosed(service.getString(if (pasted) R.string.clipboard_history_pasted
          else R.string.clipboard_history_paste_failed), protectFromNewSpeech = true)
      }
    }, delayMs)
  }

  private fun category(): String = service.getString(
    if (favorites) R.string.clipboard_history_favorites else R.string.clipboard_history_general)

  private fun updateView() {
    val entry = items.getOrNull(index)
    val description = if (entry == null) service.getString(R.string.clipboard_history_empty)
      else {
        val body = if (entry.label.isNotEmpty()) "${entry.label}\n${entry.text.take(500)}" else entry.text.take(500)
        service.getString(R.string.clipboard_history_item, category(), index + 1, items.size,
          body, if (entry.favorite) service.getString(R.string.clipboard_history_favorite) else "")
      }
    view?.text = service.getString(R.string.clipboard_history_title) + "\n\n" + description +
      "\n\n" + service.getString(R.string.clipboard_history_hint)
  }

  private fun announce(prefix: String = "") {
    itemAnnounced = true
    updateView()
    val entry = selected()
    val spoken = if (entry == null) {
      service.getString(R.string.clipboard_history_empty)
    } else if (entry.label.isNotEmpty()) {
      if (ClipboardHistorySettings.speakContentWithLabel(service)) {
        service.getString(
          R.string.clipboard_history_spoken_labeled_item,
          entry.label,
          entry.text.take(500),
          index + 1,
          items.size,
        )
      } else {
        service.getString(R.string.clipboard_history_spoken_item, entry.label, index + 1, items.size)
      }
    } else {
      service.getString(R.string.clipboard_history_spoken_item, entry.text.take(500), index + 1, items.size)
    }
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

  private fun activeAppWindowId(): Int? = service.windows.firstOrNull {
    it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
  }?.id

  fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (ScreenMonitor.isDeviceLocked(service)) { dismiss(); return }
    if (isShowing && (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
      val active = activeAppWindowId()
      if (active != null && originWindowId != null && active != originWindowId) dismiss()
    }
    if (store == null) capture()
  }

  fun onKeyEvent(event: KeyEvent): Boolean {
    if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
      if (!isShowing && !backCaptured) return false
      if (event.action == KeyEvent.ACTION_DOWN) backCaptured = true
      if (event.action == KeyEvent.ACTION_UP && backCaptured) {
        backCaptured = false
        if (isShowing) dismiss()
      }
      return true
    }
    if (!isGestureOverlayActive) return false
    val mappedAction = when (event.keyCode) {
      KeyEvent.KEYCODE_DPAD_LEFT -> Action.PREVIOUS
      KeyEvent.KEYCODE_DPAD_RIGHT -> Action.NEXT
      KeyEvent.KEYCODE_DPAD_UP -> Action.PREVIOUS_CATEGORY
      KeyEvent.KEYCODE_DPAD_DOWN -> Action.NEXT_CATEGORY
      KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Action.COPY
      KeyEvent.KEYCODE_SEARCH -> Action.SEARCH
      else -> null
    } ?: return false
    if (event.action == KeyEvent.ACTION_UP) onAction(mappedAction)
    return true
  }

  fun dismiss() = hide(announceClosure = true)

  private fun hide(announceClosure: Boolean) {
    cancelPendingDelete()
    handler.removeCallbacksAndMessages(null)
    origin = null
    originHadInputFocus = false
    originWindowId = null
    val browser = view
    val search = searchView
    if (browser == null && search == null) return
    view = null
    searchView = null
    if (browser != null) {
      browser.cancelPendingTouches()
      service.setClipboardHistoryTouchRegion(null)
      try { windows.removeViewImmediate(browser) } catch (_: IllegalArgumentException) { }
    }
    if (search != null) {
      search.hideKeyboard()
      try { windows.removeViewImmediate(search) } catch (_: IllegalArgumentException) { }
    }
    if (announceClosure) speakClosed()
  }

  fun shutdown() {
    hide(announceClosure = false)
    clipboard.removePrimaryClipChangedListener(listener)
  }

  companion object {
    private const val DELETE_CONFIRM_TIMEOUT_MS = 5_000L
  }
}
