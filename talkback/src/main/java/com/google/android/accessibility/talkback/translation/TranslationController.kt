/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.translation

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.pause.PauseController
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity
import com.google.android.accessibility.utils.monitor.ScreenMonitor
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.output.SpeechControllerImpl
import com.google.android.accessibility.utils.output.SpeechTextTransformer
import com.google.android.accessibility.utils.preference.PreferencesActivity
import java.util.Locale

/** Service-scoped policy. The provider never receives nodes, gestures, history or preferences. */
class TranslationController @JvmOverloads constructor(
  private val service: TalkBackService,
  private val speech: SpeechControllerImpl,
  private val engine: TranslationEngine = TranslationEngine(TranslationSettings.createProvider(
    SharedPreferencesUtils.getSharedPreferences(service).getString(TranslationSettings.PROVIDER, "google_web") ?: "google_web")),
) : SpeechTextTransformer, SharedPreferences.OnSharedPreferenceChangeListener {
  private val prefs = SharedPreferencesUtils.getSharedPreferences(service)
  private val handler = Handler(Looper.getMainLooper())
  private var closed = false
  private var lastFailureAt = -60_000L
  var continuous = false
    private set
  var lastExternalPackage = ""
    private set

  init { prefs.registerOnSharedPreferenceChangeListener(this) }

  override fun capture(item: FeedbackItem) {
    val params = item.fragments.firstOrNull()?.speechParams
    val root = service.rootInActiveWindow
    val accessibilityFocus = root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
    val inputFocus = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    val focus = accessibilityFocus ?: inputFocus
    val origin = params?.getString(SpeechTextTransformer.SOURCE_PACKAGE)
      ?: focus?.packageName?.toString() ?: root?.packageName?.toString() ?: ""
    item.translationPackage = origin
    item.isTranslationProtected = item.hasFlag(FeedbackItem.FLAG_NO_TRANSLATION) ||
      listOfNotNull(root, accessibilityFocus, inputFocus).any {
        it.isPassword || (Build.VERSION.SDK_INT >= 34 && it.isAccessibilityDataSensitive)
      }
    if (origin.isNotEmpty() && origin != service.packageName) lastExternalPackage = origin
  }

  private fun allowed(item: FeedbackItem): Boolean = !closed &&
    (!engine.provider.sendsTextOffDevice || prefs.getBoolean(TranslationSettings.CONSENT, false)) &&
    !ScreenMonitor.isDeviceLocked(service) && service.isInstanceActive && !PauseController.isPaused() &&
    !speech.isMute && !speech.shouldSilentSpeech &&
    !item.isTranslationProtected && !item.hasFlag(FeedbackItem.FLAG_NO_TRANSLATION) &&
    !item.hasFlag(FeedbackItem.FLAG_NO_SPEECH) && item.translationPackage.isNotEmpty() &&
    item.translationPackage != service.packageName &&
    !prefs.getStringSet(TranslationSettings.EXCLUDED, emptySet())!!.contains(item.translationPackage)

  override fun shouldTransform(item: FeedbackItem) = allowed(item) && !item.isTranslationApplied &&
    (continuous || item.hasFlag(FeedbackItem.FLAG_TRANSLATE_ONCE)) &&
    !item.translationSource.isNullOrBlank() && item.translationSource.length <= engine.provider.maxInputCharacters

  override fun transform(item: FeedbackItem, callback: SpeechTextTransformer.Callback): Runnable {
    val mode = prefs.getString(TranslationSettings.CACHE, "app")
    val scope = if (mode == "global") "*" else item.translationPackage
    val target = TranslationSettings.target(prefs)
    val source = TranslationSettings.source(prefs)
    if (source == target) { callback.ready(null); return Runnable {} }
    val cancelled = java.util.concurrent.atomic.AtomicBoolean()
    val cancelRequest = engine.request(scope, TranslationRequest(item.translationSource, source, target), mode != "off") { result ->
      handler.post {
        if (cancelled.get() || closed) return@post
        // Recheck consent, lock, exclusions, pause and lifecycle after network completion.
        if (!allowed(item)) {
          // A privacy transition is not a network failure: do not read the old private text either.
          speech.interruptPendingTextTransformation()
          return@post
        }
        callback.ready(result?.let { SpeechTextTransformer.Result(it.text, Locale.forLanguageTag(target)) })
        if (result == null && SystemClock.uptimeMillis() - lastFailureAt >= 60_000) {
          lastFailureAt = SystemClock.uptimeMillis()
          announce(R.string.translation_failed, SpeechController.QUEUE_MODE_QUEUE)
        }
      }
    }
    return Runnable { cancelled.set(true); cancelRequest.run() }
  }

  fun toggle(): Boolean {
    if (continuous) { setContinuous(false); return true }
    if (engine.provider.sendsTextOffDevice && !prefs.getBoolean(TranslationSettings.CONSENT, false)) {
      announce(R.string.translation_consent_required)
      openSettings()
      return false
    }
    if (ScreenMonitor.isDeviceLocked(service) || PauseController.isPaused()) return false
    setContinuous(true)
    return true
  }

  fun setContinuous(enabled: Boolean) {
    continuous = enabled && (!engine.provider.sendsTextOffDevice || prefs.getBoolean(TranslationSettings.CONSENT, false))
    speech.interrupt(false)
    announce(if (continuous) R.string.translation_enabled else R.string.translation_disabled)
  }

  fun translateLast(saved: Boolean = false): Boolean {
    if (engine.provider.sendsTextOffDevice && !prefs.getBoolean(TranslationSettings.CONSENT, false)) {
      announce(R.string.translation_consent_required)
      openSettings()
      return false
    }
    val item = if (saved) speech.savedUtterance else speech.lastUtterance
    if (item == null || item.translationSource.isNullOrBlank()) {
      announce(R.string.translation_no_history)
      return false
    }
    if (!allowed(item)) { announce(R.string.translation_protected); return false }
    if (item.translationSource.length > engine.provider.maxInputCharacters) { announce(R.string.translation_too_long); return false }
    val params = Bundle().apply { putString(SpeechTextTransformer.SOURCE_PACKAGE, item.translationPackage) }
    speech.speak(item.translationSource, null, SpeechController.SpeakOptions.create()
      .setQueueMode(SpeechController.QUEUE_MODE_INTERRUPT)
      .setFlags(FeedbackItem.FLAG_TRANSLATE_ONCE or FeedbackItem.FLAG_NO_HISTORY)
      .setSpeechParams(params))
    return true
  }

  fun clearCache(all: Boolean) {
    val mode = prefs.getString(TranslationSettings.CACHE, "app")
    engine.cache.clear(if (all || mode == "global") null else lastExternalPackage)
    announce(R.string.translation_cache_cleared)
  }
  fun cacheSize() = engine.cache.size()

  fun stop() {
    continuous = false
    speech.interrupt(false)
  }

  override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
    if (key in setOf(TranslationSettings.CONSENT, TranslationSettings.PROVIDER, TranslationSettings.SOURCE,
        TranslationSettings.TARGET, TranslationSettings.CACHE, TranslationSettings.EXCLUDED)) {
      handler.post {
        speech.interrupt(false)
        engine.cache.clear()
        if (engine.provider.sendsTextOffDevice && !prefs.getBoolean(TranslationSettings.CONSENT, false)) continuous = false
      }
    }
  }

  private fun announce(resource: Int, queue: Int = SpeechController.QUEUE_MODE_INTERRUPT) {
    speech.speak(service.getString(resource), null, SpeechController.SpeakOptions.create()
      .setFlags(FeedbackItem.FLAG_NO_TRANSLATION or FeedbackItem.FLAG_NO_HISTORY or FeedbackItem.FLAG_FORCE_FEEDBACK_ALL)
      .setQueueMode(queue))
  }

  private fun openSettings() {
    service.startActivity(Intent(service, TalkBackPreferencesActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      .putExtra(PreferencesActivity.FRAGMENT_NAME, TranslationFragment::class.java.name))
  }

  override fun close() {
    if (closed) return
    closed = true
    continuous = false
    prefs.unregisterOnSharedPreferenceChangeListener(this)
    handler.removeCallbacksAndMessages(null)
    engine.close()
  }
}
