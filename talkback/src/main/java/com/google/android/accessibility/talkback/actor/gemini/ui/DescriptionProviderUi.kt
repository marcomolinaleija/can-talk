/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.actor.gemini.ui

import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.gemini.ChatGptAuthManager
import com.google.android.accessibility.talkback.actor.gemini.GroqSettings
import com.google.android.accessibility.talkback.actor.gemini.local.OnDeviceAiSettings
import com.google.android.accessibility.utils.SharedPreferencesUtils

/** Keeps visual and spoken question controls consistent with the provider used for the request. */
internal object DescriptionProviderUi {
  @JvmStatic
  fun bind(root: View) {
    val context = root.context
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val groq = GroqSettings.isSelected(prefs)
    val local = OnDeviceAiSettings.isOnDevice(prefs)
    val chatgpt = !groq && !local && ChatGptAuthManager.isChatGptActive(prefs)
    val provider = when {
      groq -> "Groq"
      local -> context.getString(R.string.description_provider_local_name)
      chatgpt -> "ChatGPT"
      else -> "Gemini"
    }
    val ask = if (groq || local || chatgpt) context.getString(R.string.description_provider_ask, provider)
      else context.getString(R.string.image_qna_button_text)
    val type = if (groq || local || chatgpt) context.getString(R.string.description_provider_type, provider)
      else context.getString(R.string.image_qna_typing_button_text)

    fun label(id: Int, text: String) {
      val view = root.findViewById<View>(id) ?: return
      if (view is TextView && view.text.isNotEmpty()) {
        if (view.text.toString() != text) view.text = text
        if (view.contentDescription != null) view.contentDescription = text
      } else {
        // Keep icon-only buttons icon-only; their accessible label is the description.
        view.contentDescription = text
      }
    }
    label(R.id.ask_gemini, ask)
    label(R.id.ask_gemini_voice_input, ask)
    label(R.id.ask_gemini_typing, type)
    label(R.id.btn_type_question, type)
    root.findViewById<EditText>(R.id.question_edit_filed)?.let {
      if (it.hint?.toString() != type) it.hint = type
    }
    root.findViewById<View>(R.id.gemini_spark)?.contentDescription = provider
    root.findViewById<View>(R.id.stop_listening)?.contentDescription =
      context.getString(R.string.description_stop_listening)
  }
}
