/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.backup

import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.preference.Preference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Inspect, select and confirm before writing. Never restore merely because a file was picked. */
class BackupRestoreFragment : TalkbackBaseFragment(R.xml.backup_restore_preferences) {
  private val state by lazy { ViewModelProvider(this)[BackupUiState::class.java] }
  private var dialog: A11yAlertDialogWrapper? = null
  private val createLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
    state.busy.value = false
    val json = state.pendingExport
    state.pendingExport = null
    if (uri != null && json != null) write(uri, json)
    else if (uri != null) {
      val app = requireContext().applicationContext
      state.run {
        removeEmptyDocument(app, uri)
        BackupUiState.Result.Failed(BackupFailure.INTERRUPTED)
      }
    }
  }
  private val openLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    state.busy.value = false
    if (uri != null) read(uri)
  }

  override fun getTitle(): CharSequence = getText(R.string.title_pref_backup_restore)
  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    findPreference<Preference>(getString(R.string.pref_backup_export_key))?.setOnPreferenceClickListener {
      if (state.busy.value != true) {
        val app = requireContext().applicationContext
        state.run { BackupUiState.Result.Inventory(SelectiveBackup.inventory(app)) }
      }
      true
    }
    findPreference<Preference>(getString(R.string.pref_backup_import_key))?.setOnPreferenceClickListener {
      if (state.busy.value != true) {
        state.busy.value = true
        try { openLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
        catch (_: Exception) { state.busy.value = false; toast(R.string.backup_error_io) }
      }
      true
    }
  }
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    state.busy.observe(viewLifecycleOwner) { busy ->
      for (key in listOf(R.string.pref_backup_export_key, R.string.pref_backup_import_key))
        findPreference<Preference>(getString(key))?.isEnabled = busy != true
    }
    state.result.observe(viewLifecycleOwner) { result ->
      if (result == null) return@observe
      state.result.value = null
      when (result) {
        is BackupUiState.Result.Inventory -> select(result.counts, null)
        is BackupUiState.Result.ExportReady -> {
          state.pendingExport = result.json
          state.busy.value = true
          val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
          try { createLauncher.launch("cantalk-backup-$stamp.json") }
          catch (_: Exception) { state.pendingExport = null; state.busy.value = false; toast(R.string.backup_error_io) }
        }
        is BackupUiState.Result.Encrypted -> { state.encryptedImport = result.json; askPassword() }
        is BackupUiState.Result.ImportReady -> {
          state.encryptedImport = null
          select(result.document.sections.mapValues { it.value.itemCount }, result.document)
        }
        is BackupUiState.Result.Restored -> toast(R.string.toast_backup_restore_success)
        BackupUiState.Result.Saved -> toast(R.string.toast_backup_export_success)
        is BackupUiState.Result.Failed -> {
          toast(errorString(result.failure))
          if (result.failure == BackupFailure.WRONG_PASSWORD && state.encryptedImport != null) askPassword()
          else state.encryptedImport = null
        }
      }
    }
  }
  override fun onDestroyView() {
    dialog?.dismiss(); dialog = null
    super.onDestroyView()
  }

  private fun select(counts: Map<BackupCategory, Int>, document: BackupDocument?) {
    if (counts.isEmpty()) { toast(R.string.backup_nothing); return }
    val exporting = document == null
    val body = column()
    body.addView(text(if (exporting) R.string.backup_export_selection_info else R.string.backup_import_selection_info))
    body.addView(text(R.string.backup_exclusions))
    if (document?.legacy == true) body.addView(text(R.string.backup_legacy_warning))
    val all = CheckBox(requireContext()).apply { setText(R.string.backup_select_all); isSaveEnabled = false }
    body.addView(all)
    val choices = linkedMapOf<BackupCategory, CheckBox>()
    for (category in BackupCategory.entries) {
      val count = counts[category] ?: continue
      val check = CheckBox(requireContext()).apply {
        text = getString(R.string.backup_item_count, getString(categoryString(category)), count)
        isChecked = !category.sensitive && category != BackupCategory.BRAILLE_CALIBRATION
        isSaveEnabled = false
      }
      body.addView(check); choices[category] = check
    }
    body.addView(text(R.string.backup_sensitive_warning))
    if (BackupCategory.BRAILLE_CALIBRATION in counts) body.addView(text(R.string.backup_calibration_warning))
    val protect = if (exporting) CheckBox(requireContext()).apply {
      setText(R.string.backup_encrypt); isSaveEnabled = false; body.addView(this)
    } else null
    val password = if (exporting) passwordField(R.string.backup_password).also(body::addView) else null
    val confirm = if (exporting) passwordField(R.string.backup_password_confirm).also(body::addView) else null
    var changing = false
    fun refresh() {
      if (changing) return
      changing = true
      all.isChecked = choices.values.all { it.isChecked }
      if (exporting) {
        val sensitive = choices.any { (category, check) -> category.sensitive && check.isChecked }
        if (sensitive) protect!!.isChecked = true
        protect!!.isEnabled = !sensitive
        password!!.visibility = if (protect.isChecked) View.VISIBLE else View.GONE
        confirm!!.visibility = password.visibility
      }
      changing = false
    }
    all.setOnCheckedChangeListener { _, checked ->
      if (!changing) {
        changing = true; choices.values.forEach { it.isChecked = checked }; changing = false; refresh()
      }
    }
    choices.values.forEach { it.setOnCheckedChangeListener { _, _ -> refresh() } }
    protect?.setOnCheckedChangeListener { _, _ -> refresh() }
    refresh()
    val wrapper = A11yAlertDialogWrapper.materialDialogBuilder(requireContext())
      .setTitle(if (exporting) R.string.title_pref_backup_export else R.string.title_pref_backup_import)
      .setView(ScrollView(requireContext()).apply { addView(body) })
      .setPositiveButton(if (exporting) R.string.backup_export_button else R.string.dialog_button_restore, null)
      .setNegativeButton(android.R.string.cancel, null).create()
    show(wrapper)
    wrapper.setOnDismissListener {
      password?.text?.clear(); confirm?.text?.clear()
      if (dialog === wrapper) dialog = null
    }
    wrapper.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
      val selected = choices.filterValues { it.isChecked }.keys.toSet()
      if (selected.isEmpty()) { toast(R.string.backup_choose_one); return@setOnClickListener }
      var secret: CharArray? = null
      if (exporting && protect!!.isChecked) {
        val first = password!!.text; val second = confirm!!.text
        if (first.length !in 8..256) {
          password.error = getString(R.string.backup_password_length); password.requestFocus(); return@setOnClickListener
        }
        if (first.length != second.length || (0 until first.length).any { first[it] != second[it] }) {
          confirm.error = getString(R.string.backup_password_mismatch); confirm.requestFocus(); return@setOnClickListener
        }
        secret = CharArray(first.length) { first[it] }
      }
      val app = requireContext().applicationContext
      wrapper.dismiss()
      if (exporting) state.run {
        try { BackupUiState.Result.ExportReady(BackupCodec.encode(SelectiveBackup.capture(app, selected), secret)) }
        finally { secret?.fill('\u0000') }
      } else state.run {
        BackupUiState.Result.Restored(BackupRuntime.restore(app, document, selected))
      }
    }
  }

  private fun askPassword() {
    val json = state.encryptedImport ?: return
    val body = column(); body.addView(text(R.string.backup_password_import_info))
    val password = passwordField(R.string.backup_password).also(body::addView)
    val wrapper = A11yAlertDialogWrapper.materialDialogBuilder(requireContext())
      .setTitle(R.string.backup_unlock).setView(body)
      .setPositiveButton(R.string.backup_unlock, null).setNegativeButton(android.R.string.cancel, null).create()
    var submitted = false
    show(wrapper)
    wrapper.setOnDismissListener {
      password.text.clear()
      if (!submitted) state.encryptedImport = null
      if (dialog === wrapper) dialog = null
    }
    wrapper.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
      if (password.text.isEmpty() || password.text.length > 256) {
        password.error = getString(R.string.backup_password_length); return@setOnClickListener
      }
      val secret = CharArray(password.text.length) { password.text[it] }
      submitted = true; wrapper.dismiss()
      state.run {
        try { BackupUiState.Result.ImportReady(BackupCodec.decode(json, secret)) }
        finally { secret.fill('\u0000') }
      }
    }
  }
  private fun read(uri: Uri) {
    val app = requireContext().applicationContext
    state.run {
      val json = app.contentResolver.openInputStream(uri)?.use(BackupJson::read) ?: throw BackupException(BackupFailure.IO)
      if (BackupCodec.requiresPassword(json)) BackupUiState.Result.Encrypted(json)
      else BackupUiState.Result.ImportReady(BackupCodec.decode(json))
    }
  }
  private fun write(uri: Uri, json: String) {
    val app = requireContext().applicationContext
    state.run {
      try {
        app.contentResolver.openOutputStream(uri, "wt")?.use {
          it.write(json.toByteArray(Charsets.UTF_8)); it.flush()
        } ?: throw BackupException(BackupFailure.IO)
        BackupUiState.Result.Saved
      } catch (_: Exception) {
        try { DocumentsContract.deleteDocument(app.contentResolver, uri) } catch (_: Exception) { }
        throw BackupException(BackupFailure.IO)
      }
    }
  }
  private fun removeEmptyDocument(app: android.content.Context, uri: Uri) {
    try {
      app.contentResolver.openFileDescriptor(uri, "r")?.use {
        if (it.statSize == 0L) DocumentsContract.deleteDocument(app.contentResolver, uri)
      }
    } catch (_: Exception) { }
  }
  private fun column() = LinearLayout(requireContext()).apply {
    orientation = LinearLayout.VERTICAL
    val padding = (20 * resources.displayMetrics.density).toInt()
    setPadding(padding, padding / 2, padding, padding / 2); isSaveEnabled = false
  }
  private fun text(resource: Int) = TextView(requireContext()).apply { setText(resource); isSaveEnabled = false }
  private fun passwordField(hint: Int) = EditText(requireContext()).apply {
    setHint(hint)
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS; isSaveEnabled = false
  }
  private fun show(wrapper: A11yAlertDialogWrapper) {
    dialog?.dismiss(); dialog = wrapper; wrapper.show()
    wrapper.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
  }
  private fun toast(resource: Int) { if (isAdded) Toast.makeText(requireContext(), resource, Toast.LENGTH_LONG).show() }
  private fun categoryString(category: BackupCategory): Int = when (category) {
    BackupCategory.SETTINGS -> R.string.backup_category_settings
    BackupCategory.READER_GESTURES -> R.string.backup_category_reader_gestures
    BackupCategory.KEYBOARD_SHORTCUTS -> R.string.backup_category_keyboard_shortcuts
    BackupCategory.BRAILLE_SETTINGS -> R.string.backup_category_braille_settings
    BackupCategory.BRAILLE_GESTURES -> R.string.backup_category_braille_gestures
    BackupCategory.BRAILLE_CALIBRATION -> R.string.backup_category_calibration
    BackupCategory.API_KEYS -> R.string.backup_category_api_keys
    BackupCategory.EMOJI_FAVORITES -> R.string.backup_category_emoji
    BackupCategory.CLIPBOARD_HISTORY -> R.string.backup_category_clipboard
    BackupCategory.CUSTOM_LABELS -> R.string.backup_category_labels
    BackupCategory.SOUND_THEMES -> R.string.backup_category_themes
  }
  private fun errorString(failure: BackupFailure): Int = when (failure) {
    BackupFailure.INVALID_FORMAT -> R.string.backup_error_invalid_format
    BackupFailure.UNSUPPORTED_VERSION -> R.string.backup_error_unsupported_version
    BackupFailure.CHECKSUM -> R.string.backup_error_checksum_mismatch
    BackupFailure.TOO_LARGE -> R.string.backup_error_too_large
    BackupFailure.PASSWORD_REQUIRED -> R.string.backup_error_password_required
    BackupFailure.WRONG_PASSWORD -> R.string.backup_error_password_wrong
    BackupFailure.TYPE_MISMATCH -> R.string.backup_error_type_mismatch
    BackupFailure.STORAGE_LOCKED -> R.string.backup_error_locked
    BackupFailure.WRITE_FAILED -> R.string.backup_error_commit_failed
    BackupFailure.ROLLBACK_FAILED -> R.string.backup_error_recovery
    BackupFailure.IO -> R.string.backup_error_io
    BackupFailure.INTERRUPTED -> R.string.backup_error_interrupted
  }
}
