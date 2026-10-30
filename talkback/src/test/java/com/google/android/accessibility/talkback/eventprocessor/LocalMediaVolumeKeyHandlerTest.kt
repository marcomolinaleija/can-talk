/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.eventprocessor

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class,
  shadows = [LocalMediaVolumeKeyHandlerTest.StreamAudioManager::class])
class LocalMediaVolumeKeyHandlerTest {
  @Implements(AudioManager::class)
  class StreamAudioManager : ShadowAudioManager() {
    val adjustedStreams = mutableListOf<Int>()
    // Robolectric 4.17's adjustStreamVolume uses its single-argument setStreamVolume,
    // which changes every stream. Preserve the Android API's per-stream contract here.
    @Implementation override fun adjustStreamVolume(stream: Int, direction: Int, flags: Int) {
      adjustedStreams.add(stream)
      setStreamVolume(stream, getStreamVolume(stream) + direction, flags)
    }
  }
  private lateinit var audio: AudioManager
  private lateinit var handler: LocalMediaVolumeKeyHandler
  @Before fun setup() {
    val context = RuntimeEnvironment.getApplication()
    audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audio.mode = AudioManager.MODE_NORMAL
    shadowOf(audio).setIsMusicActive(true)
    listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_ACCESSIBILITY,
      AudioManager.STREAM_VOICE_CALL).forEach { audio.setStreamVolume(it, 7, 0) }
    handler = LocalMediaVolumeKeyHandler(context)
  }
  private fun key(up: Boolean = false, repeat: Int = 0,
    code: Int = KeyEvent.KEYCODE_VOLUME_DOWN) = KeyEvent(1L, 2L,
    if (up) KeyEvent.ACTION_UP else KeyEvent.ACTION_DOWN, code, repeat)
  private fun volume(stream: Int = AudioManager.STREAM_MUSIC) = audio.getStreamVolume(stream)

  @Test fun localPlaybackWithoutTouch_changesOnlyMediaAndConsumesRelease() {
    val speechBefore = volume(AudioManager.STREAM_ACCESSIBILITY)
    val callBefore = volume(AudioManager.STREAM_VOICE_CALL)
    assertTrue(handler.onKeyEvent(key(), true, false))
    assertEquals(6, volume())
    assertEquals(speechBefore, volume(AudioManager.STREAM_ACCESSIBILITY))
    assertEquals(callBefore, volume(AudioManager.STREAM_VOICE_CALL))
    assertTrue(handler.onKeyEvent(key(up = true), true, false))
    assertEquals(6, volume())
    assertFalse(handler.hasCapturedKey(key()))
    assertTrue(handler.onKeyEvent(key(code = KeyEvent.KEYCODE_VOLUME_UP), true, false))
    assertEquals(7, volume())
    assertEquals(listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_MUSIC),
      (shadowOf(audio) as StreamAudioManager).adjustedStreams)
  }
  @Test fun fingerOnScreen_keepsReaderVolumeGesture() {
    assertFalse(handler.onKeyEvent(key(), true, true))
    assertFalse(handler.onKeyEvent(key(up = true), true, false))
    assertEquals(7, volume())
  }
  @Test fun callsAndRinging_neverCaptureEvenWithMediaActive() {
    for (mode in listOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION,
        AudioManager.MODE_RINGTONE)) {
      audio.mode = mode
      assertFalse(handler.onKeyEvent(key(), true, false))
      assertFalse(handler.onKeyEvent(key(up = true), true, false))
    }
    assertEquals(7, volume())
  }
  @Test fun remoteOnlyOrNoPlayback_passesToSystem() {
    shadowOf(audio).setIsMusicActive(false)
    assertFalse(handler.onKeyEvent(key(), true, false))
    assertFalse(handler.onKeyEvent(key(up = true), true, false))
    assertEquals(7, volume())
  }
  @Test fun ineligibleReader_doesNotCapture() {
    assertFalse(handler.onKeyEvent(key(), false, false))
    assertFalse(handler.onKeyEvent(key(up = true), false, false))
    assertEquals(7, volume())
  }
  @Test fun repeats_cannotStealAPressThatStartedWithTouchOrWithoutPlayback() {
    assertFalse(handler.onKeyEvent(key(), true, true))
    assertFalse(handler.onKeyEvent(key(repeat = 1), true, false))
    assertFalse(handler.onKeyEvent(key(up = true), true, false))
    shadowOf(audio).setIsMusicActive(false)
    assertFalse(handler.onKeyEvent(key(), true, false))
    shadowOf(audio).setIsMusicActive(true)
    assertFalse(handler.onKeyEvent(key(repeat = 1), true, false))
    assertEquals(7, volume())
  }
  @Test fun capturedPress_handlesRepeatsAndKeepsOwnershipAfterPlaybackStopsOrCallStarts() {
    assertTrue(handler.onKeyEvent(key(), true, false))
    assertTrue(handler.onKeyEvent(key(repeat = 1), true, false))
    assertEquals(5, volume())
    shadowOf(audio).setIsMusicActive(false)
    assertTrue(handler.onKeyEvent(key(repeat = 2), true, false))
    audio.mode = AudioManager.MODE_IN_CALL
    assertTrue(handler.onKeyEvent(key(up = true), false, false))
    assertEquals(5, volume())
    assertFalse(handler.onKeyEvent(key(), true, false))
  }
}
