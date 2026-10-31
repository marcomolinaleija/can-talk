/*
 * Copyright 2026 CAN Talk contributors
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

package com.google.android.accessibility.talkback.actor.gemini

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.gemini.local.LocalModelManager
import com.google.android.accessibility.talkback.actor.gemini.local.OnDeviceAiSettings
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText

/**
 * Controller for the ChatGPT settings and AI provider selector in Automatic Descriptions.
 */
object ChatGptPreference {

  const val PREF_KEY_ACCOUNT = "pref_chatgpt_account"
  const val PREF_KEY_PROVIDER = "pref_ai_provider"

  private val mainHandler = Handler(Looper.getMainLooper())

  /**
   * Sets up ChatGPT and AI provider preferences if present in the [fragment].
   */
  @JvmStatic
  fun setUp(fragment: PreferenceFragmentCompat) {
    setUpProviderSelector(fragment)
    setUpAccountPreference(fragment)
  }

  private fun setUpProviderSelector(fragment: PreferenceFragmentCompat) {
    val providerPref = fragment.findPreference<ListPreference>(OnDeviceAiSettings.PREF_PROVIDER)
      ?: fragment.findPreference<ListPreference>(PREF_KEY_PROVIDER)
      ?: return
    val context = fragment.requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)

    // Sync initial value based on on-device, groq, chatgpt or gemini status
    val currentProvider =
      when {
        OnDeviceAiSettings.isOnDevice(prefs) -> OnDeviceAiSettings.PROVIDER_DEVICE
        GroqSettings.isSelected(prefs) -> GroqSettings.PROVIDER
        ChatGptAuthManager.isChatGptActive(prefs) -> ChatGptAuthManager.PROVIDER_CHATGPT
        else -> OnDeviceAiSettings.PROVIDER_CLOUD
      }
    providerPref.isPersistent = false
    providerPref.value = currentProvider
    providerPref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

    providerPref.setOnPreferenceChangeListener { _, newValue ->
      val selected = newValue as String
      if (selected == OnDeviceAiSettings.PROVIDER_DEVICE && !LocalModelManager.get(context).isReady()) {
        Toast.makeText(context, R.string.pref_on_device_ai_enable_needs_model, Toast.LENGTH_LONG).show()
        false
      } else {
        prefs.edit()
          .putString(OnDeviceAiSettings.PREF_PROVIDER, selected)
          .putString(PREF_KEY_PROVIDER, selected)
          .apply()
        OnDeviceAiSettings.setOnDevice(prefs, selected == OnDeviceAiSettings.PROVIDER_DEVICE)
        fragment.findPreference<Preference>(PREF_KEY_ACCOUNT)?.let { updateAccountSummary(it) }
        true
      }
    }
  }

  private fun setUpAccountPreference(fragment: PreferenceFragmentCompat) {
    val preference = fragment.findPreference<Preference>(PREF_KEY_ACCOUNT) ?: return
    updateAccountSummary(preference)
    preference.setOnPreferenceClickListener {
      showAccountDialog(fragment, preference)
      true
    }
  }

  /**
   * Refreshes both the ChatGPT account summary and the AI provider selector summary and value.
   */
  @JvmStatic
  fun refreshUi(fragment: PreferenceFragmentCompat) {
    fragment.findPreference<Preference>(PREF_KEY_ACCOUNT)?.let { updateAccountSummary(it) }
    val providerPref = fragment.findPreference<ListPreference>(OnDeviceAiSettings.PREF_PROVIDER)
      ?: fragment.findPreference<ListPreference>(PREF_KEY_PROVIDER)
    providerPref?.let { pref ->
      val prefs = SharedPreferencesUtils.getSharedPreferences(fragment.requireContext())
      val currentProvider =
        when {
          OnDeviceAiSettings.isOnDevice(prefs) -> OnDeviceAiSettings.PROVIDER_DEVICE
          GroqSettings.isSelected(prefs) -> GroqSettings.PROVIDER
          ChatGptAuthManager.isChatGptActive(prefs) -> ChatGptAuthManager.PROVIDER_CHATGPT
          else -> OnDeviceAiSettings.PROVIDER_CLOUD
        }
      pref.value = currentProvider
    }
  }

  /**
   * Refreshes the summary of the ChatGPT account preference.
   */
  @JvmStatic
  fun updateAccountSummary(preference: Preference) {
    val context = preference.context
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val isConnected = ChatGptAuthManager.isReady(context)
    val email = ChatGptAuthManager.getUserEmail(prefs)
    val model = ChatGptAuthManager.getModel(prefs)

    preference.summary =
      when {
        isConnected && email.isNotEmpty() ->
          context.getString(R.string.summary_pref_chatgpt_account_connected, email, model)
        isConnected ->
          context.getString(R.string.summary_pref_chatgpt_account_connected_no_email, model)
        else ->
          context.getString(R.string.summary_pref_chatgpt_account_not_connected)
      }
  }

  private fun showAccountDialog(fragment: PreferenceFragmentCompat, preference: Preference) {
    val context = fragment.requireContext()
    val isConnected = ChatGptAuthManager.isReady(context)

    if (isConnected) {
      showConnectedDialog(fragment, preference)
    } else {
      showLoginDialog(fragment, preference)
    }
  }

  private fun showLoginDialog(fragment: PreferenceFragmentCompat, preference: Preference) {
    val context = fragment.requireContext()
    AlertDialog.Builder(context)
      .setTitle(R.string.title_pref_chatgpt_account)
      .setMessage(R.string.chatgpt_login_dialog_message)
      .setPositiveButton(R.string.chatgpt_btn_sign_in) { _, _ ->
        ChatGptAuthManager.startAuthorization(context)
      }
      .setNeutralButton(R.string.chatgpt_btn_advanced) { _, _ ->
        showAdvancedDialog(fragment, preference)
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showAdvancedDialog(fragment: PreferenceFragmentCompat, preference: Preference) {
    val context = fragment.requireContext()
    val options = arrayOf(
      context.getString(R.string.chatgpt_btn_paste_code),
      context.getString(R.string.chatgpt_btn_manual_key)
    )

    AlertDialog.Builder(context)
      .setTitle(R.string.chatgpt_btn_advanced)
      .setItems(options) { _, which ->
        when (which) {
          0 -> showPasteCodeDialog(fragment, preference)
          1 -> showManualApiKeyDialog(fragment, preference)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showPasteCodeDialog(fragment: PreferenceFragmentCompat, preference: Preference) {
    val context = fragment.requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val field =
      KeyboardSafeEditText(context).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        isSingleLine = true
        setHint(R.string.chatgpt_paste_code_hint)
      }

    val padding = (24 * context.resources.displayMetrics.density).toInt()
    val container =
      FrameLayout(context).apply {
        setPadding(padding, 0, padding, 0)
        addView(field)
      }

    AlertDialog.Builder(context)
      .setTitle(R.string.chatgpt_paste_code_dialog_title)
      .setMessage(R.string.chatgpt_paste_code_dialog_message)
      .setView(container)
      .setPositiveButton(R.string.gemini_api_key_save) { _, _ ->
        val input = field.text?.toString()?.trim().orEmpty()
        if (input.isNotEmpty()) {
          Toast.makeText(context, R.string.chatgpt_auth_connecting, Toast.LENGTH_SHORT).show()
          ChatGptAuthManager.exchangeCodeOrUrl(context, input, object : ChatGptAuthManager.AuthCallback {
            override fun onSuccess(email: String) {
              mainHandler.post {
                val msg = context.getString(R.string.chatgpt_auth_success, email)
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                updateAccountSummary(preference)
                ChatGptAuthManager.setAiProvider(prefs, ChatGptAuthManager.PROVIDER_CHATGPT)
                refreshUi(fragment)
              }
            }

            override fun onError(errorMessage: String) {
              mainHandler.post {
                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
              }
            }
          })
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showConnectedDialog(fragment: PreferenceFragmentCompat, preference: Preference) {
    val context = fragment.requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val email = ChatGptAuthManager.getUserEmail(prefs)
    val model = ChatGptAuthManager.getModel(prefs)

    val message = context.getString(R.string.chatgpt_connected_dialog_message, email, model)

    AlertDialog.Builder(context)
      .setTitle(R.string.chatgpt_connected_dialog_title)
      .setMessage(message)
      .setPositiveButton(R.string.chatgpt_btn_change_model) { _, _ ->
        showModelSelectionDialog(fragment, preference)
      }
      .setNeutralButton(R.string.chatgpt_btn_disconnect) { _, _ ->
        ChatGptAuthManager.disconnect(context)
        updateAccountSummary(preference)
      }
      .setNegativeButton(R.string.chatgpt_btn_close, null)
      .show()
  }

  private fun showModelSelectionDialog(fragment: PreferenceFragmentCompat, preference: Preference) {
    val context = fragment.requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val currentModel = ChatGptAuthManager.getModel(prefs)

    val models = arrayOf(
      ChatGptAuthManager.MODEL_GPT_6_LUNA,
      ChatGptAuthManager.MODEL_GPT_6_1_SOL,
      ChatGptAuthManager.MODEL_GPT_6_ASTRA,
      ChatGptAuthManager.MODEL_GPT_4O
    )
    val labels = arrayOf(
      "GPT-6 Luna (Recomendado: rápido y fluido)",
      "GPT-6.1 Sol (Plus / Pro)",
      "GPT-6 Astra (Avanzado)",
      "GPT-4o (Clave API manual)"
    )

    val selectedIndex = models.indexOf(currentModel).let { if (it >= 0) it else 0 }

    AlertDialog.Builder(context)
      .setTitle(R.string.chatgpt_select_model_title)
      .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
        ChatGptAuthManager.setModel(prefs, models[which])
        updateAccountSummary(preference)
        dialog.dismiss()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showManualApiKeyDialog(fragment: PreferenceFragmentCompat, preference: Preference) {
    val context = fragment.requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)

    val field =
      KeyboardSafeEditText(context).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        isSingleLine = true
        setHint(R.string.chatgpt_manual_key_hint)
        setText(ChatGptAuthManager.getManualApiKey(prefs))
      }

    val padding = (24 * context.resources.displayMetrics.density).toInt()
    val container =
      FrameLayout(context).apply {
        setPadding(padding, 0, padding, 0)
        addView(field)
      }

    AlertDialog.Builder(context)
      .setTitle(R.string.chatgpt_manual_key_dialog_title)
      .setMessage(R.string.chatgpt_manual_key_dialog_message)
      .setView(container)
      .setPositiveButton(R.string.gemini_api_key_save) { _, _ ->
        ChatGptAuthManager.setManualApiKey(prefs, field.text?.toString().orEmpty())
        updateAccountSummary(preference)
        ChatGptAuthManager.setAiProvider(prefs, ChatGptAuthManager.PROVIDER_CHATGPT)
        refreshUi(fragment)
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }
}
