/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.utils.output

import android.content.Context
import android.media.AudioManager
import android.media.AudioTrack
import android.media.SoundPool
import android.os.HandlerThread
import android.os.Looper
import android.os.Vibrator
import com.google.android.accessibility.utils.R
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [CallSoundVolumeTest.RecordingTrack::class])
@LooperMode(LooperMode.Mode.LEGACY)
class CallSoundVolumeTest {
  private val context: Context get() = RuntimeEnvironment.getApplication()
  private val prefs get() = SharedPreferencesUtils.getSharedPreferences(context)
  private val sound = R.raw.window_state
  private lateinit var audio: AudioManager
  private lateinit var controller: FeedbackController
  private var pcm: LowLatencyAudio? = null
  private val pool: SoundPool get() = ReflectionHelpers.getField(controller, "mSoundPool")

  @Before fun setup() {
    prefs.edit().clear().commit()
    LowLatencyAudio.shutdownAll()
    audio = context.getSystemService(AudioManager::class.java)
    audio.mode = AudioManager.MODE_NORMAL
    controller = FeedbackController(context, SoundPool.Builder().setMaxStreams(10).build(),
      context.getSystemService(Vibrator::class.java))
    controller.setUseAccessibilityStream(true)
    controller.setAuditoryEnabled(true)
    controller.setVolumeAdjustment(0.8f)
    controller.setSpatialMode(FeedbackController.SPATIAL_STEREO)
    RecordingTrack.volumes.clear()
  }

  @After fun cleanup() {
    pcm?.let { ReflectionHelpers.setField(it, "thread", null) }
    controller.shutdown()
    drain()
  }

  private fun drain() {
    Robolectric.flushBackgroundThreadScheduler()
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun enableCall(mode: Int = AudioManager.MODE_IN_CALL) {
    prefs.edit().putBoolean(CallSpeechVolumeController.ENABLED_KEY, true)
      .putInt(CallSpeechVolumeController.PERCENT_KEY, 50).commit()
    audio.mode = mode
  }

  private fun warm() {
    controller.playAuditoryWithoutHaptic(sound, 1f, 0f, null)
    shadowOf(pool).notifyResourceLoaded(sound, true)
    drain()
    shadowOf(pool).clearPlayed()
  }

  private fun play(): Float {
    controller.playAuditoryWithoutHaptic(sound, 1f, 1f, null)
    drain()
    return shadowOf(pool).getResourcePlaybacks(sound).last().leftVolume
  }

  @Test fun navigationSound_usesCallPercentageAndPreservesSystemVolumes() {
    warm()
    val voiceCallVolume = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
    val accessibilityVolume = audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY)
    enableCall()
    assertEquals(0.2f, play(), 0.00001f)
    assertEquals(voiceCallVolume, audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
    assertEquals(accessibilityVolume, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
  }

  @Test fun percentageChange_affectsNextSoundWithoutSpeechOrRestart() {
    warm()
    enableCall()
    assertEquals(0.2f, play(), 0.00001f)
    prefs.edit().putInt(CallSpeechVolumeController.PERCENT_KEY, 25).commit()
    assertEquals(0.05f, play(), 0.00001f)
    prefs.edit().putInt(CallSpeechVolumeController.PERCENT_KEY, 5).commit()
    assertEquals(0.002f, play(), 0.00001f)
  }

  @Test fun endingCall_restoresSavedSoundGain() {
    warm()
    enableCall()
    play()
    audio.mode = AudioManager.MODE_NORMAL
    assertEquals(0.8f, play(), 0f)
  }

  @Test fun disabledAndRinging_keepOrdinarySoundGain() {
    warm()
    audio.mode = AudioManager.MODE_IN_CALL
    assertEquals(0.8f, play(), 0f)
    enableCall(AudioManager.MODE_RINGTONE)
    assertEquals(0.8f, play(), 0f)
    enableCall()
    play()
    prefs.edit().putBoolean(CallSpeechVolumeController.ENABLED_KEY, false).commit()
    assertEquals(0.8f, play(), 0f)
  }

  @Test fun voipAndMediaOutput_useSameCallReduction() {
    controller.setUseAccessibilityStream(false)
    warm()
    enableCall(AudioManager.MODE_IN_COMMUNICATION)
    assertEquals(0.2f, play(), 0.00001f)
  }

  @Test fun pannedSound_preservesChannelBalance() {
    warm()
    enableCall()
    controller.playAuditoryWithoutHaptic(sound, 1f, 1f, 0.25f, 0.5f, null)
    drain()
    val playback = shadowOf(pool).getResourcePlaybacks(sound).last()
    assertEquals(0.2f, playback.leftVolume, 0.00001f)
    assertEquals(0.1f, playback.rightVolume, 0.00001f)
  }

  @Test fun previewIgnoringOrdinarySoundGain_stillFollowsCallAdjustment() {
    warm()
    enableCall()
    controller.playAuditory(sound, 1f, 1f, true, null)
    drain()
    assertEquals(0.25f,
      shadowOf(pool).getResourcePlaybacks(sound).last().leftVolume, 0.00001f)
  }

  @Test fun loadingSound_readsNewPercentageBeforePlaying() {
    enableCall()
    controller.playAuditoryWithoutHaptic(sound, 1f, 1f, null)
    prefs.edit().putInt(CallSpeechVolumeController.PERCENT_KEY, 25).commit()
    shadowOf(pool).notifyResourceLoaded(sound, true)
    drain()
    assertEquals(0.05f,
      shadowOf(pool).getResourcePlaybacks(sound).last().leftVolume, 0.00001f)
  }

  @Test fun customThemeSound_usesCallReduction() {
    val path = "/test/theme-window.ogg"
    controller.setCustomSounds(mapOf("window_state" to path))
    enableCall()
    controller.playAuditoryWithoutHaptic(sound, 1f, 1f, null)
    shadowOf(pool).notifyPathLoaded(path, true)
    drain()
    assertEquals(0.2f, shadowOf(pool).getPathPlaybacks(path).single().leftVolume, 0.00001f)
  }

  @Test fun lowLatencyPlayback_reducesActualPcmSamples() {
    enableCall()
    val player = LowLatencyAudio.get(context, FeedbackController.feedbackAttributes())!!
    pcm = player
    ReflectionHelpers.setField(player, "thread", Thread())
    val frames = ReflectionHelpers.getField<Int>(player, "burstFrames")
    val clip = LowLatencyAudio.Clip(FloatArray(frames * 2) { 0.4f }, player.sampleRate)
    ReflectionHelpers.getField<MutableMap<String, LowLatencyAudio.Clip>>(controller, "mClips")
      .put("res:$sound", clip)
    controller.setLowLatencyAudio(true)
    controller.playAuditoryWithoutHaptic(sound, 1f, 1f, null)
    val mixed = FloatArray(frames * 2)
    ReflectionHelpers.callInstanceMethod<Unit>(player, "mix",
      ClassParameter.from(FloatArray::class.java, mixed))
    assertEquals(0.08f, mixed[0], 0.00001f)
    assertTrue(shadowOf(pool).getResourcePlaybacks(sound).isEmpty())
  }

  @Test fun spatial3dPlayback_usesSameReducedGain() {
    enableCall()
    controller.setSpatialMode(FeedbackController.SPATIAL_3D)
    val spatial = SpatialSoundPlayer(context)
    ReflectionHelpers.getField<MutableMap<String, FloatArray>>(spatial, "sounds")
      .put("res:$sound", FloatArray(64) { 0.1f })
    ReflectionHelpers.setField(controller, "mSpatialSoundPlayer", spatial)
    controller.playAuditoryWithoutHaptic(sound, 1f, 1f, 0.5f, 0.5f, null)
    shadowOf(ReflectionHelpers.getField<HandlerThread>(spatial, "thread").looper).idle()
    assertEquals(0.2f, RecordingTrack.volumes.single(), 0.00001f)
  }

  @Implements(AudioTrack::class)
  class RecordingTrack : ShadowAudioTrack() {
    @Implementation protected fun setVolume(volume: Float): Int {
      volumes.add(volume)
      return AudioTrack.SUCCESS
    }
    companion object { val volumes = mutableListOf<Float>() }
  }
}
