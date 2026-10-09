/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.brailleime

import android.content.Context
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.SoundPool
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.utils.output.FailoverTextToSpeech
import com.google.android.accessibility.utils.output.FeedbackController
import com.google.android.accessibility.utils.output.ThemeSounds

/**
 * Plays the chosen keyboard sounds using the reader's audio stream, with a system-effect fallback
 * when the phone does not expose readable sound files. Preloading avoids delayed typing feedback.
 */
object BrailleImeTypingSounds {
  /** The kind of key to sound like. */
  enum class Key(internal val effect: Int, internal val themeName: String) {
    CHARACTER(AudioManager.FX_KEYPRESS_STANDARD, "braille_keyboard_character"),
    SPACE(AudioManager.FX_KEYPRESS_SPACEBAR, "braille_keyboard_space"),
    DELETE(AudioManager.FX_KEYPRESS_DELETE, "braille_keyboard_delete"),
    NEW_LINE(AudioManager.FX_KEYPRESS_RETURN, "braille_keyboard_new_line"),
  }

  private var pool: SoundPool? = null
  private var attributes: AudioAttributes? = null
  private var paths: Map<Key, String> = emptyMap()
  private var preparedProfile: TypingSoundSettings.Profile? = null
  private val ids = mutableMapOf<Key, Int>()
  private val ready = mutableSetOf<Int>()
  private val failed = mutableSetOf<Int>()
  private var pendingPreview: Key? = null

  @JvmStatic
  @Synchronized
  fun prepare(context: Context) {
    prepare(context) { profile, key -> TypingSoundSettings.path(profile, key) }
  }

  @JvmStatic
  @Synchronized
  internal fun prepare(context: Context, resolve: (TypingSoundSettings.Profile, Key) -> String?) {
    val profile = TypingSoundSettings.profile(context)
    val selectedAttributes = FeedbackController.feedbackAttributes(
      FailoverTextToSpeech.shouldUseAccessibilityStream(context))
    if (preparedProfile == profile && attributes == selectedAttributes) return
    val selectedPaths = Key.entries.mapNotNull { key ->
      (resolve(profile, key)
        ?: resolve(TypingSoundSettings.Profile.ANDROID, key))?.let { key to it }
    }.toMap()
    release()
    preparedProfile = profile
    paths = selectedPaths
    attributes = selectedAttributes
    if (paths.isEmpty()) return
    val appContext = context.applicationContext
    val newPool = SoundPool.Builder().setMaxStreams(4)
      .setAudioAttributes(selectedAttributes).build()
    pool = newPool
    newPool.setOnLoadCompleteListener { loadedPool, id, status ->
      // @JvmStatic synchronized entry points lock the class, not the Kotlin object instance.
      synchronized(BrailleImeTypingSounds::class.java) {
        if (pool !== loadedPool) return@synchronized
        if (status == 0) ready.add(id) else failed.add(id)
        val preview = pendingPreview
        if (preview != null && ids[preview] == id) {
          pendingPreview = null
          val gain = TypingSoundSettings.volume(appContext)
          if (BrailleUserPreferences.readTypingSounds(appContext) && gain > 0f) {
            if (status == 0) loadedPool.play(id, gain, gain, 1, 0, 1f)
            else systemEffect(appContext, preview, gain)
          }
        }
      }
    }
    val loaded = mutableMapOf<String, Int>()
    for ((key, path) in paths) ids[key] = loaded.getOrPut(path) { newPool.load(path, 1) }
  }

  @JvmStatic
  @Synchronized
  fun release() {
    pool?.release()
    pool = null
    preparedProfile = null
    paths = emptyMap()
    ids.clear()
    ready.clear()
    failed.clear()
    pendingPreview = null
  }

  @JvmStatic
  @JvmOverloads
  @Synchronized
  fun play(context: Context, key: Key, preview: Boolean = false) {
    if (!BrailleUserPreferences.readTypingSounds(context)) {
      return
    }
    val volume = TypingSoundSettings.volume(context)
    if (volume <= 0f) return
    if (TypingSoundSettings.profile(context) == TypingSoundSettings.Profile.THEME &&
      ThemeSounds.play(key.themeName, volume)) {
      return
    }
    prepare(context)
    val id = ids[key]
    if (id != null && id != 0 && id !in failed) {
      if (id in ready) pool?.play(id, volume, volume, 1, 0, 1f)
      else if (preview) pendingPreview = key
      return
    }
    systemEffect(context, key, volume)
  }

  private fun systemEffect(context: Context, key: Key, volume: Float) {
    // This final fallback is routed by Android rather than the reader's audio attributes.
    context.getSystemService(AudioManager::class.java)?.playSoundEffect(key.effect, volume)
  }
}
