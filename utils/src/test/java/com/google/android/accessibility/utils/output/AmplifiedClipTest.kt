/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.utils.output

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Exercise the actual PCM mixer without starting a device audio-writing thread. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmplifiedClipTest {
  private lateinit var player: LowLatencyAudio

  @Before fun setup() {
    LowLatencyAudio.shutdownAll()
    player = LowLatencyAudio.get(RuntimeEnvironment.getApplication(),
      FeedbackController.feedbackAttributes(true))!!
    ReflectionHelpers.setField(player, "thread", Thread())
  }

  @After fun cleanup() {
    ReflectionHelpers.setField(player, "thread", null)
    LowLatencyAudio.shutdownAll()
  }

  private fun clip(sample: Float): LowLatencyAudio.Clip {
    val frames = ReflectionHelpers.getField<Int>(player, "burstFrames")
    return LowLatencyAudio.Clip(FloatArray(frames * 2) { if (it % 2 == 0) sample else -sample },
      player.sampleRate)
  }

  private fun mixed(): FloatArray {
    val frames = ReflectionHelpers.getField<Int>(player, "burstFrames")
    return FloatArray(frames * 2).also {
      ReflectionHelpers.callInstanceMethod<Unit>(player, "mix",
        ClassParameter.from(FloatArray::class.java, it))
    }
  }

  @Test fun twoHundredPercent_doublesTheActualPcmAmplitude() {
    assertTrue(player.playAmplified(clip(0.2f), 2f))
    val samples = mixed()
    assertEquals(0.4f, samples[0], 0.0001f)
    assertEquals(-0.4f, samples[1], 0.0001f)
  }

  @Test fun amplifiedPeaks_stayWithinValidAudioSampleLimits() {
    assertTrue(player.playAmplified(clip(0.8f), 2f))
    val samples = mixed()
    assertEquals(1f, samples[0], 0f)
    assertEquals(-1f, samples[1], 0f)
    assertTrue(samples.all { it.isFinite() && it in -1f..1f })
  }

  @Test fun ordinaryPlayback_keepsItsPreviousGain() {
    player.play(clip(0.4f), 0.5f, 0.5f, 1f)
    val samples = mixed()
    assertEquals(0.2f, samples[0], 0.0001f)
    assertEquals(-0.2f, samples[1], 0.0001f)
  }

  @Test fun invalidGain_andStoppedPlayerRejectAmplification() {
    val clip = clip(0.2f)
    for (gain in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
      assertFalse(player.playAmplified(clip, gain))
    assertTrue(mixed().all { it == 0f })
    ReflectionHelpers.setField(player, "thread", null)
    player.shutdown()
    assertFalse(player.playAmplified(clip, 2f))
  }
}
