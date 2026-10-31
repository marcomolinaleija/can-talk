/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.actor.gemini

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.view.View
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.gemini.local.OnDeviceAiSettings
import com.google.android.accessibility.talkback.actor.gemini.ui.DescriptionProviderUi
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], qualifiers = "es-rMX")
class GroqQuestionUiTest {
  private val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.ModalBottomSheetTheme)
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)

  @Test fun allQuestionPagesRefreshVisibleAndSpokenLabelsForEachProvider() {
    for (layout in listOf(R.layout.caption_result_bottomsheet_dialog,
      R.layout.image_qna_chat_ui_voice_input, R.layout.image_qna_chat_ui_typing,
      R.layout.image_qna_voice_input_bottomsheet_dialog)) {
      val root = LayoutInflater.from(context).inflate(layout, FrameLayout(context), false)
      for (provider in listOf(GroqSettings.PROVIDER, OnDeviceAiSettings.PROVIDER_DEVICE,
        OnDeviceAiSettings.PROVIDER_CLOUD, GroqSettings.PROVIDER)) {
        prefs.edit().putString(OnDeviceAiSettings.PREF_PROVIDER, provider).commit()
        DescriptionProviderUi.bind(root)
        val name = if (provider == GroqSettings.PROVIDER) "Groq"
          else context.getString(R.string.description_provider_local_name)
        val ask = if (provider == OnDeviceAiSettings.PROVIDER_CLOUD)
          context.getString(R.string.image_qna_button_text)
          else context.getString(R.string.description_provider_ask, name)
        val type = if (provider == OnDeviceAiSettings.PROVIDER_CLOUD)
          context.getString(R.string.image_qna_typing_button_text)
          else context.getString(R.string.description_provider_type, name)
        fun check(id: Int, expected: String) {
          val view = root.findViewById<View>(id) ?: return
          val actual = if (view is TextView && view.text.isNotEmpty()) view.text
            else view.contentDescription
          assertEquals("layout=$layout, provider=$provider, id=$id", expected, actual.toString())
        }
        check(R.id.ask_gemini, ask)
        check(R.id.ask_gemini_voice_input, ask)
        check(R.id.ask_gemini_typing, type)
        check(R.id.btn_type_question, type)
        root.findViewById<EditText>(R.id.question_edit_filed)?.let {
          assertEquals(type, it.hint.toString())
          it.setText("My unfinished question")
          DescriptionProviderUi.bind(root)
          assertEquals("My unfinished question", it.text.toString())
        }
      }
    }
  }
}
