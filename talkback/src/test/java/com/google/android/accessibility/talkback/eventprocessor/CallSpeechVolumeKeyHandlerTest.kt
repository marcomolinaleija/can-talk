/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.eventprocessor

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.view.KeyEvent
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.CallSpeechVolumeController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CallSpeechVolumeKeyHandlerTest {
  private lateinit var prefs: SharedPreferences
  private lateinit var audio: AudioManager
  private lateinit var handler: CallSpeechVolumeKeyHandler
  private val announcements = mutableListOf<Int>()

  @Before fun setUp() {
    val context = RuntimeEnvironment.getApplication()
    prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs.edit().clear().putBoolean(CallSpeechVolumeController.ENABLED_KEY, true).commit()
    audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audio.mode = AudioManager.MODE_IN_CALL
    handler = CallSpeechVolumeKeyHandler(context) { announcements.add(it) }
  }

  private fun key(code: Int = KeyEvent.KEYCODE_VOLUME_DOWN, up: Boolean = false,
    repeat: Int = 0): KeyEvent = KeyEvent(1L, 2L,
    if (up) KeyEvent.ACTION_UP else KeyEvent.ACTION_DOWN, code, repeat)

  private fun percent() = prefs.getInt(CallSpeechVolumeController.PERCENT_KEY, 50)

  @Test fun touchAndVolume_changesPersistedGain_withoutChangingAnySystemStream() {
    val streams = listOf(AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_ACCESSIBILITY,
      AudioManager.STREAM_MUSIC)
    val before = streams.map { audio.getStreamVolume(it) }
    assertTrue(handler.onKeyEvent(key(), true, true))
    assertEquals(45, percent())
    assertEquals(listOf(45), announcements)
    assertTrue(handler.onKeyEvent(key(up = true), true, true))
    assertEquals(before, streams.map { audio.getStreamVolume(it) })
    val recreated = CallSpeechVolumeKeyHandler(RuntimeEnvironment.getApplication()) { }
    assertTrue(recreated.onKeyEvent(key(KeyEvent.KEYCODE_VOLUME_UP), true, true))
    assertEquals(50, percent())
  }

  @Test fun liftingFinger_consumesRepeatsAndRelease_withoutFurtherChanges() {
    assertTrue(handler.onKeyEvent(key(), true, true))
    assertTrue(handler.onKeyEvent(key(repeat = 1), true, false))
    assertTrue(handler.onKeyEvent(key(up = true), true, false))
    assertEquals(listOf(45), announcements)
    assertFalse(handler.hasCapturedKey(key()))
    assertFalse(handler.onKeyEvent(key(), true, false))
  }

  @Test fun pressStartingWithoutTouch_cannotBeCapturedByRepeats() {
    assertFalse(handler.onKeyEvent(key(), true, false))
    assertFalse(handler.onKeyEvent(key(repeat = 1), true, true))
    assertFalse(handler.onKeyEvent(key(up = true), true, true))
    assertEquals(50, percent())
    assertTrue(announcements.isEmpty())
  }

  @Test fun callEnding_consumesRemainingPress_withoutChangingNormalVoice() {
    assertTrue(handler.onKeyEvent(key(), true, true))
    audio.mode = AudioManager.MODE_NORMAL
    assertTrue(handler.onKeyEvent(key(repeat = 1), true, true))
    assertTrue(handler.onKeyEvent(key(up = true), true, false))
    assertFalse(handler.onKeyEvent(key(), true, true))
    assertEquals(listOf(45), announcements)
  }

  @Test fun disablingSettingOrPausing_consumesCapturedPress() {
    assertTrue(handler.onKeyEvent(key(), true, true))
    prefs.edit().putBoolean(CallSpeechVolumeController.ENABLED_KEY, false).commit()
    assertTrue(handler.onKeyEvent(key(repeat = 1), true, true))
    assertTrue(handler.onKeyEvent(key(up = true), false, false))
    assertFalse(handler.onKeyEvent(key(), true, true))
    prefs.edit().putBoolean(CallSpeechVolumeController.ENABLED_KEY, true).commit()
    assertFalse(handler.onKeyEvent(key(), false, true))
    assertEquals(listOf(45), announcements)
  }

  @Test fun voipMode_usesSameSeparateGain() {
    audio.mode = AudioManager.MODE_IN_COMMUNICATION
    assertTrue(handler.onKeyEvent(key(KeyEvent.KEYCODE_VOLUME_UP), true, true))
    assertEquals(55, percent())
  }

  @Test fun keyChange_reachesExistingSpeechController_beforeAnnouncement() {
    val controller = CallSpeechVolumeController(RuntimeEnvironment.getApplication())
    try {
      assertEquals(0.25f, controller.adjust(1f, AudioManager.STREAM_ACCESSIBILITY), 0.001f)
      var announcedGain = 0f
      val liveHandler = CallSpeechVolumeKeyHandler(RuntimeEnvironment.getApplication()) {
        announcedGain = controller.adjust(1f, AudioManager.STREAM_ACCESSIBILITY)
      }
      assertTrue(liveHandler.onKeyEvent(key(), true, true))
      assertEquals(0.2025f, announcedGain, 0.001f)
    } finally {
      controller.shutdown()
    }
  }

  @Test fun holdingButton_repeatsUntilLowerLimit_withoutRepeatedLimitAnnouncements() {
    prefs.edit().putInt(CallSpeechVolumeController.PERCENT_KEY, 30).commit()
    assertTrue(handler.onKeyEvent(key(), true, true))
    for (repeat in 1..6) assertTrue(handler.onKeyEvent(key(repeat = repeat), true, true))
    assertEquals(5, percent())
    assertEquals(listOf(25, 20, 15, 10, 5), announcements)
    assertTrue(handler.onKeyEvent(key(up = true), true, true))
    assertTrue(handler.onKeyEvent(key(), true, true))
    assertEquals(listOf(25, 20, 15, 10, 5, 5), announcements)
  }

  @Test fun reducingBelowTwentyFive_reachesLiveVoiceGain_withoutChangingCallVolume() {
    prefs.edit().putInt(CallSpeechVolumeController.PERCENT_KEY, 25).commit()
    val controller = CallSpeechVolumeController(RuntimeEnvironment.getApplication())
    val callVolume = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
    try {
      assertEquals(0.0625f, controller.adjust(1f, AudioManager.STREAM_ACCESSIBILITY), 0.00001f)
      val gains = mutableListOf<Float>()
      val liveHandler = CallSpeechVolumeKeyHandler(RuntimeEnvironment.getApplication()) {
        gains.add(controller.adjust(1f, AudioManager.STREAM_ACCESSIBILITY))
      }
      assertTrue(liveHandler.onKeyEvent(key(), true, true))
      for (repeat in 1..3) assertTrue(liveHandler.onKeyEvent(key(repeat = repeat), true, true))
      val expected = listOf(0.04f, 0.0225f, 0.01f, 0.0025f)
      assertEquals(expected.size, gains.size)
      expected.zip(gains).forEach { (wanted, actual) -> assertEquals(wanted, actual, 0.00001f) }
      assertEquals(callVolume, audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
    } finally {
      controller.shutdown()
    }
  }

  @Test fun upperLimit_andIndependentKeyReleases() {
    prefs.edit().putInt(CallSpeechVolumeController.PERCENT_KEY, 100).commit()
    assertTrue(handler.onKeyEvent(key(KeyEvent.KEYCODE_VOLUME_UP), true, true))
    assertEquals(100, percent())
    assertTrue(handler.onKeyEvent(key(), true, true))
    assertEquals(95, percent())
    assertTrue(handler.onKeyEvent(key(up = true), true, false))
    assertTrue(handler.hasCapturedKey(key(KeyEvent.KEYCODE_VOLUME_UP)))
    assertTrue(handler.onKeyEvent(key(KeyEvent.KEYCODE_VOLUME_UP, up = true), true, false))
    assertFalse(handler.hasCapturedKey(key(KeyEvent.KEYCODE_VOLUME_UP)))
  }

  @Test fun unrelatedKeysAndUncapturedReleases_passThrough() {
    assertFalse(handler.onKeyEvent(key(KeyEvent.KEYCODE_HEADSETHOOK), true, true))
    assertFalse(handler.onKeyEvent(key(up = true), true, true))
    audio.mode = AudioManager.MODE_RINGTONE
    assertFalse(handler.onKeyEvent(key(), true, true))
    assertTrue(announcements.isEmpty())
  }
}
