/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.brailleime

import android.content.Context
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.utils.output.ThemeSounds
import java.io.File

/** Keyboard choices live in the same device-protected preferences as the other braille settings. */
object TypingSoundSettings {
  const val PROFILE_KEY = "pref_brailleime_sound_profile"
  const val FOLLOW_KEY = "pref_brailleime_sound_follow_reader"
  const val VOLUME_KEY = "pref_brailleime_sound_volume"

  enum class Profile(val value: String, val title: Int, val suffix: String? = null) {
    THEME("theme", R.string.braille_sound_theme),
    ANDROID("android", R.string.braille_sound_android),
    SAMSUNG("samsung", R.string.braille_sound_samsung, ""),
    CALM("calm", R.string.braille_sound_calm, "_Calm"),
    FUN("fun", R.string.braille_sound_fun, "_Fun"),
    RETRO("retro", R.string.braille_sound_retro, "_Retro"),
  }

  private val directories = listOf(
    "/system/media/audio/ui", "/product/media/audio/ui", "/system/product/media/audio/ui")

  internal fun prefs(context: Context) = BrailleUserPreferences.getSharedPreferences(
    context, BrailleUserPreferences.BRAILLE_SHARED_PREFS_FILENAME)

  fun profile(context: Context): Profile = Profile.entries.firstOrNull {
    it.value == prefs(context).getString(PROFILE_KEY, "theme")
  } ?: Profile.THEME

  fun volume(context: Context): Float = volume(
    prefs(context).getBoolean(FOLLOW_KEY, true),
    prefs(context).getInt(VOLUME_KEY, 50), ThemeSounds.readerSpeechVolume)

  internal fun volume(follow: Boolean, percent: Int, readerGain: Float): Float =
    if (follow) 0.5f * readerGain.coerceIn(0f, 1f) else percent.coerceIn(0, 100) / 100f

  /** Only offer Samsung choices whose typing and deletion samples can actually be read. */
  fun availableProfiles(): List<Profile> = Profile.entries.filter { profile ->
    profile.suffix == null || BrailleImeTypingSounds.Key.entries.all {
      path(profile, it) != null
    }
  }

  internal fun path(
    profile: Profile,
    key: BrailleImeTypingSounds.Key,
    roots: List<String> = directories,
    readable: (File) -> Boolean = { it.isFile && it.canRead() },
  ): String? {
    val filename = if (profile.suffix != null) {
      // Samsung uses its SIP sample for text entry and a separate backspace sample.
      (if (key == BrailleImeTypingSounds.Key.DELETE) "S_SIP_Backspace" else "TW_SIP") +
        profile.suffix + ".ogg"
    } else {
      when (key) {
        BrailleImeTypingSounds.Key.CHARACTER -> "KeypressStandard.ogg"
        BrailleImeTypingSounds.Key.SPACE -> "KeypressSpacebar.ogg"
        BrailleImeTypingSounds.Key.DELETE -> "KeypressDelete.ogg"
        BrailleImeTypingSounds.Key.NEW_LINE -> "KeypressReturn.ogg"
      }
    }
    return roots.asSequence().map { File(it, filename) }.firstOrNull(readable)?.path
  }
}
