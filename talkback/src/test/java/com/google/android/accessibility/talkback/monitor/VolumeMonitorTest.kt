/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.monitor

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Looper
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.compat.media.AudioManagerCompatUtils
import java.time.Duration
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAudioManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class,
  shadows = [VolumeMonitorTest.RecordingAudioManager::class])
@LooperMode(LooperMode.Mode.PAUSED)
class VolumeMonitorTest {
  @Implements(AudioManager::class)
  class RecordingAudioManager : ShadowAudioManager() {
    val forcedStreams = mutableListOf<Int>()
    @Implementation fun forceVolumeControlStream(stream: Int) { forcedStreams.add(stream) }
  }

  private lateinit var context: Context
  private lateinit var audio: AudioManager
  private lateinit var monitor: VolumeMonitor
  private val speech = mutableListOf<Feedback.Speech>()

  @Before fun setup() {
    context = RuntimeEnvironment.getApplication()
    FormFactorUtils.initialize(context)
    audio = context.getSystemService(AudioManager::class.java)
    SharedPreferencesUtils.getSharedPreferences(context).edit().clear()
      .putInt(context.getString(R.string.pref_a11y_volume_control_hint_times), 3).commit()
    monitor = VolumeMonitor(Pipeline.FeedbackReturner { feedback ->
      feedback.failovers().forEach { it.speech()?.let(speech::add) }
      // Accept the announcement but never complete it, simulating a busy speech queue.
      true
    }, context, null)
  }

  private fun recordingAudio() = shadowOf(audio) as RecordingAudioManager
  private fun change(stream: Int, value: Int = 6, previous: Int = 5) {
    audio.setStreamVolume(stream, value, 0)
    monitor.onReceiveIntent(Intent(AudioManagerCompatUtils.VOLUME_CHANGED_ACTION)
      .putExtra(AudioManagerCompatUtils.EXTRA_VOLUME_STREAM_TYPE, stream)
      .putExtra(AudioManagerCompatUtils.EXTRA_VOLUME_STREAM_VALUE, value)
      .putExtra(AudioManagerCompatUtils.EXTRA_PREV_VOLUME_STREAM_VALUE, previous))
    shadowOf(Looper.getMainLooper()).idle()
  }
  private fun expire() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
  private fun useTv() {
    shadowOf(context.getSystemService(UiModeManager::class.java))
      .setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
    FormFactorUtils.initialize(context)
  }

  @Test fun accessibilityAdjustmentOnPhone_doesNotForceSubsequentSystemKeys() {
    shadowOf(audio).setIsMusicActive(true)
    change(AudioManager.STREAM_ACCESSIBILITY)
    assertTrue(recordingAudio().forcedStreams.isEmpty())
    expire()
    assertTrue(speech.isNotEmpty())
    assertTrue(recordingAudio().forcedStreams.isEmpty())
  }

  @Test fun mediaAfterAccessibility_announcesLatestStreamWithoutChangingEitherVolume() {
    change(AudioManager.STREAM_ACCESSIBILITY, 4)
    change(AudioManager.STREAM_MUSIC, 9)
    expire()
    assertEquals(1, speech.size)
    assertTrue(speech.single().text().toString().contains(monitor.getStreamName(AudioManager.STREAM_MUSIC)))
    assertEquals(4, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
    assertEquals(9, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertTrue(recordingAudio().forcedStreams.isEmpty())
  }

  @Test fun legacyOverride_releasesBeforeAnnouncementEvenIfSpeechNeverCompletes() {
    useTv()
    change(AudioManager.STREAM_MUSIC)
    assertEquals(listOf(AudioManager.STREAM_MUSIC), recordingAudio().forcedStreams)
    expire()
    assertEquals(listOf(AudioManager.STREAM_MUSIC, -1), recordingAudio().forcedStreams)
    assertTrue(speech.isNotEmpty())
    assertNull(speech.single().options()!!.mCompletedAction)
  }

  @Test fun streamSwitch_releasesLegacyOverrideAndTracksNewVolume() {
    useTv()
    change(AudioManager.STREAM_MUSIC)
    change(AudioManager.STREAM_ALARM)
    assertEquals(listOf(AudioManager.STREAM_MUSIC, -1, AudioManager.STREAM_ALARM),
      recordingAudio().forcedStreams)
    expire()
    assertEquals(-1, recordingAudio().forcedStreams.last())
    assertEquals(1, speech.size)
  }

  @Test fun explicitRelease_cancelsOldTimeoutWithoutReleasingUnownedStream() {
    useTv()
    change(AudioManager.STREAM_MUSIC)
    monitor.releaseControl()
    monitor.releaseControl()
    expire()
    assertEquals(listOf(AudioManager.STREAM_MUSIC, -1), recordingAudio().forcedStreams)
    assertTrue(speech.isEmpty())
  }
}
