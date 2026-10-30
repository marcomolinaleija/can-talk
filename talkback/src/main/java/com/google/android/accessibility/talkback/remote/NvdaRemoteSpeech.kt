/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.remote

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LocaleSpan
import android.text.style.TtsSpan
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Text, language and spelling support; unknown synth commands are ignored, never executed. */
object NvdaRemoteSpeech {
  fun text(sequence: JSONArray): CharSequence {
    val result = SpannableStringBuilder()
    var language: Locale? = null
    var spelling = false
    for (index in 0 until minOf(sequence.length(), 4096)) {
      when (val item = sequence.opt(index)) {
        is String -> {
          if (result.length + item.length > 32768) break
          val start = result.length
          result.append(item)
          if (item.isNotEmpty()) {
            language?.let { result.setSpan(LocaleSpan(it), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (spelling) result.setSpan(TtsSpan.VerbatimBuilder(item).build(), start, result.length,
              Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
          }
          result.append(' ')
        }
        is JSONArray -> {
          val values = item.optJSONObject(1) ?: JSONObject()
          when (item.optString(0)) {
            "LangChangeCommand" -> language = values.optString("lang").takeIf { it.isNotBlank() && it != "null" }
              ?.let { Locale.forLanguageTag(it.replace('_', '-')) }
            "CharacterModeCommand" -> spelling = values.optBoolean("state")
            "EndUtteranceCommand", "BreakCommand" -> result.append(". ")
          }
        }
      }
    }
    return result
  }

  fun speak(text: CharSequence, interrupt: Boolean = false) {
    if (text.isBlank()) return
    val service = TalkBackService.getInstance() ?: return
    service.speechController.speak(text, null, SpeechController.SpeakOptions.create()
      .setQueueMode(if (interrupt) SpeechController.QUEUE_MODE_INTERRUPT else SpeechController.QUEUE_MODE_QUEUE)
      .setFlags(FeedbackItem.FLAG_NO_HISTORY or FeedbackItem.FLAG_NO_TRANSLATION or
        FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE))
  }

  fun cancel() { TalkBackService.getInstance()?.speechController?.interrupt(false) }
  fun pause(paused: Boolean) {
    val speech = TalkBackService.getInstance()?.speechController ?: return
    if (paused) speech.pause() else speech.resume()
  }
}
