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

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FeedbackFragment
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.FailoverTextToSpeech
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.output.SpeechControllerImpl
import com.google.android.accessibility.utils.output.SpeechTextTransformer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationPolicyTest {
  class Service : TalkBackService() {
    var active = true
    override fun isInstanceActive() = active
  }
  private lateinit var service: Service
  private lateinit var speech: SpeechControllerImpl
  private lateinit var controller: TranslationController
  private val workers = java.util.concurrent.Executors.newSingleThreadExecutor()
  private var remote = true
  private val provider = object : TranslationProvider {
    override val id = "policy-test"
    override val sendsTextOffDevice get() = remote
    override fun translate(request: TranslationRequest, cancellation: TranslationCancellation) = TranslationResult("hola")
  }
  private val prefs get() = SharedPreferencesUtils.getSharedPreferences(service)
  private val delegate = object : SpeechController.Delegate {
    override fun isAudioPlaybackActive() = false
    override fun isMicrophoneActiveAndHeadphoneOff() = false
    override fun isSsbActive() = false
    override fun isPhoneCallActive() = false
    override fun onSpeakingForcedFeedback() {}
  }

  @Before fun setup() {
    service = Robolectric.buildService(Service::class.java).get()
    prefs.edit().clear().commit()
    speech = SpeechControllerImpl(service, delegate, null, FailoverTextToSpeech(service, false), false)
    controller = TranslationController(service, speech, TranslationEngine(provider, workers = workers))
  }
  @After fun cleanup() { controller.close(); speech.shutdown() }
  private fun item(origin: String = "example.app", flags: Int = FeedbackItem.FLAG_TRANSLATE_ONCE): FeedbackItem {
    val item = FeedbackItem(null as com.google.android.accessibility.utils.Performance.EventId?)
    item.translationSource = "hello"
    item.addFlag(flags)
    item.addFragment(FeedbackFragment("hello", Bundle().apply { putString(SpeechTextTransformer.SOURCE_PACKAGE, origin) }))
    controller.capture(item)
    return item
  }
  private fun consent() { prefs.edit().putBoolean(TranslationSettings.CONSENT, true).commit() }

  @Test fun remoteIsOffByDefaultAndRequiresConsent() {
    assertFalse(controller.continuous)
    assertFalse(controller.shouldTransform(item()))
    consent()
    assertTrue(controller.shouldTransform(item()))
    assertFalse(controller.shouldTransform(item(flags = 0)))
  }
  @Test fun localProviderDoesNotRequireNetworkConsent() {
    remote = false
    assertTrue(controller.shouldTransform(item()))
  }
  @Test fun sensitiveFlagsOwnAppAndUnknownOriginsAreExcluded() {
    consent()
    val protected = item(flags = FeedbackItem.FLAG_TRANSLATE_ONCE or FeedbackItem.FLAG_NO_TRANSLATION)
    assertTrue(protected.isTranslationProtected)
    assertFalse(controller.shouldTransform(protected))
    assertFalse(controller.shouldTransform(item(service.packageName)))
    assertFalse(controller.shouldTransform(item("")))
    assertFalse(controller.shouldTransform(item(flags = FeedbackItem.FLAG_TRANSLATE_ONCE or FeedbackItem.FLAG_NO_SPEECH)))
  }
  @Test fun exclusionAppliesToSingleAndContinuousCandidates() {
    consent()
    prefs.edit().putStringSet(TranslationSettings.EXCLUDED, setOf("example.app")).commit()
    assertFalse(controller.shouldTransform(item()))
  }
  @Test fun lockServiceStateMuteAndSilencePreventTranslation() {
    consent()
    val item = item()
    val keyguard = shadowOf(service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
    keyguard.setKeyguardLocked(true)
    assertFalse(controller.shouldTransform(item))
    keyguard.setKeyguardLocked(false)
    service.active = false
    assertFalse(controller.shouldTransform(item))
    service.active = true
    speech.setMute(true)
    assertFalse(controller.shouldTransform(item))
    speech.setMute(false)
    speech.setSilenceSpeech(true)
    assertFalse(controller.shouldTransform(item))
  }
  @Test fun alreadyTranslatedAndOversizedUtterancesAreNotUploaded() {
    consent()
    val item = item()
    item.isTranslationApplied = true
    assertFalse(controller.shouldTransform(item))
    item.isTranslationApplied = false
    item.translationSource = "x".repeat(16_001)
    assertFalse(controller.shouldTransform(item))
  }
  @Test fun revokedConsentAndClosedControllerRejectExistingHistory() {
    consent()
    val item = item()
    assertTrue(controller.shouldTransform(item))
    prefs.edit().putBoolean(TranslationSettings.CONSENT, false).commit()
    assertFalse(controller.shouldTransform(item))
    consent()
    controller.close()
    assertFalse(controller.shouldTransform(item))
  }
  @Test fun markedRootIsProtectedEvenWithoutFocusedNode() {
    consent()
    val root = android.view.accessibility.AccessibilityNodeInfo.obtain()
    root.packageName = "example.app"
    root.isAccessibilityDataSensitive = true
    org.robolectric.util.ReflectionHelpers.callInstanceMethod<Void>(root, "setSealed",
      org.robolectric.util.ReflectionHelpers.ClassParameter.from(java.lang.Boolean.TYPE, true))
    shadowOf(service).setRootInActiveWindow(root)
    val item = item()
    assertTrue(item.isTranslationProtected)
    assertFalse(controller.shouldTransform(item))
  }
  @Test fun lockAfterCompletionBeforeSpeechDoesNotFallbackToPrivateOriginal() {
    consent()
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    var callbacks = 0
    controller.transform(item()) { callbacks++ }
    workers.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
    shadowOf(service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setKeyguardLocked(true)
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    assertEquals(0, callbacks)
  }
  @Test fun revokedConsentAfterCompletionDoesNotDispatchOldUtterance() {
    consent()
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    var callbacks = 0
    controller.transform(item()) { callbacks++ }
    workers.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
    prefs.edit().putBoolean(TranslationSettings.CONSENT, false).commit()
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    assertEquals(0, callbacks)
  }
}
