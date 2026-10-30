/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.brailleime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.ThemeSounds
import org.junit.After
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
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [TypingSoundsTest.RecordingAudio::class])
class TypingSoundsTest {
  private val context: Context get() = RuntimeEnvironment.getApplication()
  private val key = BrailleImeTypingSounds.Key.CHARACTER
  private val path = "/test/keyboard.ogg"

  @Before fun setup() {
    BrailleImeTypingSounds.release()
    TypingSoundSettings.prefs(context).edit().clear().commit()
    SharedPreferencesUtils.getSharedPreferences(context).edit().clear().commit()
    BrailleUserPreferences.writeTypingSounds(context, true)
    TypingSoundSettings.prefs(context).edit().putString(TypingSoundSettings.PROFILE_KEY, "android").commit()
    ThemeSounds.setSpeechVolume(1f)
    ThemeSounds.set(emptyMap())
    RecordingAudio.calls.clear()
  }

  @After fun cleanup() {
    BrailleImeTypingSounds.release()
    ThemeSounds.set(emptyMap())
    ThemeSounds.setSpeechVolume(1f)
  }

  private fun prepare(): SoundPool {
    BrailleImeTypingSounds.prepare(context) { _, _ -> path }
    return ReflectionHelpers.getField(BrailleImeTypingSounds, "pool")
  }

  @Test fun loadedFiles_followReaderGainWithoutChangingSystemVolume() {
    val pool = prepare()
    shadowOf(pool).notifyPathLoaded(path, true)
    ThemeSounds.setSpeechVolume(0.6f)
    val audio = context.getSystemService(AudioManager::class.java)
    val original = audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY)
    BrailleImeTypingSounds.play(context, key)
    assertEquals(0.3f, shadowOf(pool).getPathPlaybacks(path).single().leftVolume, 0.0001f)
    assertEquals(original, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
  }

  @Test fun manualGain_ignoresSoftwareSpeechVolumeAndZeroIsSilent() {
    val pool = prepare()
    shadowOf(pool).notifyPathLoaded(path, true)
    TypingSoundSettings.prefs(context).edit().putBoolean(TypingSoundSettings.FOLLOW_KEY, false)
      .putInt(TypingSoundSettings.VOLUME_KEY, 75).commit()
    ThemeSounds.setSpeechVolume(0.25f)
    BrailleImeTypingSounds.play(context, key)
    assertEquals(0.75f, shadowOf(pool).getPathPlaybacks(path).single().leftVolume, 0f)
    TypingSoundSettings.prefs(context).edit().putInt(TypingSoundSettings.VOLUME_KEY, 0).commit()
    BrailleImeTypingSounds.play(context, key)
    assertEquals(1, shadowOf(pool).getPathPlaybacks(path).size)
    assertTrue(RecordingAudio.calls.isEmpty())
  }

  @Test fun preview_waitsForLoadingButTypingNeverPlaysLate() {
    val pool = prepare()
    BrailleImeTypingSounds.play(context, key)
    shadowOf(pool).notifyPathLoaded(path, true)
    assertFalse(shadowOf(pool).wasPathPlayed(path))
    BrailleImeTypingSounds.release()
    val newPool = prepare()
    BrailleImeTypingSounds.play(context, key, true)
    shadowOf(newPool).notifyPathLoaded(path, true)
    assertEquals(1, shadowOf(newPool).getPathPlaybacks(path).size)
  }

  @Test fun unreadableOrFailedSample_fallsBackToAndroidEffect() {
    val pool = prepare()
    shadowOf(pool).notifyPathLoaded(path, false)
    BrailleImeTypingSounds.play(context, key)
    assertEquals(listOf(AudioManager.FX_KEYPRESS_STANDARD to 0.5f), RecordingAudio.calls)
    assertFalse(shadowOf(pool).wasPathPlayed(path))
  }

  @Test fun turningSoundsOff_cancelsPendingPreview() {
    val pool = prepare()
    BrailleImeTypingSounds.play(context, key, true)
    BrailleUserPreferences.writeTypingSounds(context, false)
    shadowOf(pool).notifyPathLoaded(path, true)
    assertFalse(shadowOf(pool).wasPathPlayed(path))
    assertTrue(RecordingAudio.calls.isEmpty())
  }

  @Test fun currentTheme_isUsedOnlyWhenItsProfileIsSelected() {
    val themePath = "/test/theme.ogg"
    ThemeSounds.set(mapOf(key.themeName to themePath))
    val themePool = ReflectionHelpers.getField<SoundPool>(ThemeSounds, "pool")
    shadowOf(themePool).notifyPathLoaded(themePath, true)
    TypingSoundSettings.prefs(context).edit().putString(TypingSoundSettings.PROFILE_KEY, "theme").commit()
    val androidPool = prepare()
    shadowOf(androidPool).notifyPathLoaded(path, true)
    BrailleImeTypingSounds.play(context, key)
    assertTrue(shadowOf(themePool).wasPathPlayed(themePath))
    assertFalse(shadowOf(androidPool).wasPathPlayed(path))
    TypingSoundSettings.prefs(context).edit().putString(TypingSoundSettings.PROFILE_KEY, "android").commit()
    val explicitPool = prepare()
    shadowOf(explicitPool).notifyPathLoaded(path, true)
    BrailleImeTypingSounds.play(context, key)
    assertTrue(shadowOf(explicitPool).wasPathPlayed(path))
    assertEquals(1, shadowOf(themePool).getPathPlaybacks(themePath).size)
  }

  @Test fun disabledTypingSounds_doesNotPlayFilesOrSystemEffects() {
    val pool = prepare()
    shadowOf(pool).notifyPathLoaded(path, true)
    BrailleUserPreferences.writeTypingSounds(context, false)
    BrailleImeTypingSounds.play(context, key, true)
    assertFalse(shadowOf(pool).wasPathPlayed(path))
    assertTrue(RecordingAudio.calls.isEmpty())
  }

  @Test fun changingReaderStream_rebuildsPoolWithNewAudioAttributes() {
    val first = prepare()
    val firstAttrs = ReflectionHelpers.getField<AudioAttributes>(BrailleImeTypingSounds, "attributes")
    assertEquals(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, firstAttrs.usage)
    SharedPreferencesUtils.getSharedPreferences(context).edit()
      .putBoolean("pref_use_accessibility_stream", false).commit()
    val second = prepare()
    assertNotSame(first, second)
    val attrs = ReflectionHelpers.getField<AudioAttributes>(BrailleImeTypingSounds, "attributes")
    assertEquals(AudioAttributes.USAGE_MEDIA, attrs.usage)
  }

  @Test fun profileChange_discardsPendingPreviewOfOldProfile() {
    val old = prepare()
    BrailleImeTypingSounds.play(context, key, true)
    TypingSoundSettings.prefs(context).edit().putString(TypingSoundSettings.PROFILE_KEY, "retro").commit()
    val current = prepare()
    shadowOf(old).notifyPathLoaded(path, true)
    shadowOf(current).notifyPathLoaded(path, true)
    assertFalse(shadowOf(old).wasPathPlayed(path))
    assertFalse(shadowOf(current).wasPathPlayed(path))
  }

  @Test fun samsungProfile_usesMatchingTypingAndDeletionVariants() {
    assertEquals(File("/phone", "TW_SIP_Retro.ogg").path, TypingSoundSettings.path(
      TypingSoundSettings.Profile.RETRO, key, listOf("/phone")) { true })
    assertEquals(File("/phone", "S_SIP_Backspace_Retro.ogg").path, TypingSoundSettings.path(
      TypingSoundSettings.Profile.RETRO, BrailleImeTypingSounds.Key.DELETE, listOf("/phone")) { true })
    assertNull(TypingSoundSettings.path(TypingSoundSettings.Profile.RETRO, key,
      listOf("/phone")) { false })
  }

  @Test fun availableProfiles_hideMissingSamsungFilesAndRecoverUnknownStoredChoice() {
    assertEquals(listOf(TypingSoundSettings.Profile.THEME, TypingSoundSettings.Profile.ANDROID),
      TypingSoundSettings.availableProfiles())
    TypingSoundSettings.prefs(context).edit().putString(TypingSoundSettings.PROFILE_KEY, "unknown").commit()
    assertEquals(TypingSoundSettings.Profile.THEME, TypingSoundSettings.profile(context))
    assertEquals(0f, TypingSoundSettings.volume(false, -5, 1f), 0f)
    assertEquals(1.5f, TypingSoundSettings.volume(false, 150, 1f), 0f)
    assertEquals(2f, TypingSoundSettings.volume(false, 200, 0.25f), 0f)
    assertEquals(2f, TypingSoundSettings.volume(false, 900, 1f), 0f)
    assertEquals(0.3f, TypingSoundSettings.volume(true, 200, 0.6f), 0.0001f)
  }

  @Test fun boostedVolume_withNoReadableFile_keepsSystemFallbackWithinItsSupportedRange() {
    TypingSoundSettings.prefs(context).edit().putBoolean(TypingSoundSettings.FOLLOW_KEY, false)
      .putInt(TypingSoundSettings.VOLUME_KEY, 200).commit()
    BrailleImeTypingSounds.prepare(context) { _, _ -> null }
    val audio = context.getSystemService(AudioManager::class.java)
    val original = audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY)
    BrailleImeTypingSounds.play(context, key)
    assertEquals(listOf(AudioManager.FX_KEYPRESS_STANDARD to 1f), RecordingAudio.calls)
    assertEquals(original, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
    assertEquals(1f, ThemeSounds.readerSpeechVolume, 0f)
  }

  @Test fun closingTheUi_cancelsNormalPreviewWithoutDiscardingTheLoadedSound() {
    val pool = prepare()
    BrailleImeTypingSounds.play(context, key, true)
    BrailleImeTypingSounds.cancelPendingPreview()
    shadowOf(pool).notifyPathLoaded(path, true)
    assertFalse(shadowOf(pool).wasPathPlayed(path))
    BrailleImeTypingSounds.play(context, key)
    assertEquals(1, shadowOf(pool).getPathPlaybacks(path).size)
  }

  @Implements(AudioManager::class)
  class RecordingAudio : ShadowAudioManager() {
    companion object { val calls = mutableListOf<Pair<Int, Float>>() }
    @Implementation protected fun playSoundEffect(effect: Int, volume: Float) {
      calls.add(effect to volume)
    }
  }
}
