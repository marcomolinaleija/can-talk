/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.remote

import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import java.util.UUID

/** Secondary dialogs for saved computers; no computer rows are added to the connection form. */
internal class NvdaRemoteComputersDialog(
  private val activity: AppCompatActivity,
  private val showDialog: (AlertDialog.Builder) -> AlertDialog,
  private val canShow: () -> Boolean,
  private val report: (String) -> Unit,
  private val onConnect: (NvdaRemoteComputer, String) -> Unit,
  private val onManual: () -> Unit,
  private val onChanged: (NvdaRemoteComputer) -> Unit,
  private val onDeleted: (String) -> Unit,
) {
  private val store = NvdaRemoteComputersStore(activity)
  private val main = Handler(Looper.getMainLooper())
  private var listing = false
  private var preparing = false

  fun show() {
    if (listing) return
    listing = true
    work({ store.list() }, { listing = false; report(activity.getString(R.string.nvda_computer_error)) }) { computers ->
      listing = false
      if (!canShow()) return@work
      val form = Form()
      if (computers.isEmpty()) form.message(R.string.nvda_computers_empty)
      for (computer in computers) {
        form.button(activity.getString(R.string.nvda_computer_connect, computer.name)) { requestPassword(computer) }
        form.button(activity.getString(R.string.nvda_computer_options, computer.name)) { options(computer) }
      }
      showDialog(AlertDialog.Builder(activity).setTitle(R.string.nvda_computers_title).setView(form.scroll)
        .setPositiveButton(R.string.nvda_computer_manual) { _, _ -> onManual() }
        .setNegativeButton(R.string.close_string, null))
    }
  }

  fun prepareSave(connection: NvdaRemoteComputer, selected: NvdaRemoteComputer?, connectAfter: Boolean, ready: (NvdaRemoteComputer) -> Unit) {
    if (preparing) return
    preparing = true
    work({ store.list() }, { preparing = false; report(activity.getString(R.string.nvda_computer_error)) }) { computers ->
      preparing = false
      if (!canShow()) return@work
      val form = Form()
      val name = form.input(R.string.nvda_computer_name, selected?.name.orEmpty()).apply {
        filters = arrayOf(InputFilter.LengthFilter(80))
      }
      val remember = form.check(R.string.nvda_computer_remember_key, selected?.password != null)
      val dialog = showDialog(AlertDialog.Builder(activity).setTitle(R.string.nvda_computer_save_title)
        .setView(form.scroll).setPositiveButton(if (connectAfter) R.string.nvda_remote_connect else R.string.nvda_computer_save, null)
        .setNegativeButton(android.R.string.cancel, null))
      dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
        commitInput(dialog) {
          val value = name.text.toString().trim()
          if (value.isEmpty()) { name.error = activity.getString(R.string.nvda_computer_name_required); return@commitInput }
          if (computers.any { it.id != selected?.id && it.name.equals(value, true) }) {
            name.error = activity.getString(R.string.nvda_computer_duplicate); return@commitInput
          }
          val candidate = connection.copy(id = selected?.id ?: UUID.randomUUID().toString(), name = value,
            password = if (remember.isChecked) connection.password else null)
          dialog.dismiss()
          ready(candidate)
        }
      }
    }
  }

  private fun requestPassword(computer: NvdaRemoteComputer) {
    if (computer.password != null) { onConnect(computer, computer.password); return }
    val form = Form()
    val password = form.password("")
    val dialog = showDialog(AlertDialog.Builder(activity).setTitle(activity.getString(R.string.nvda_computer_connect, computer.name))
      .setView(form.scroll).setPositiveButton(R.string.nvda_remote_connect, null)
      .setNegativeButton(android.R.string.cancel, null))
    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
      commitInput(dialog) {
        val value = password.text.toString()
        if (!validPassword(value)) { password.error = activity.getString(R.string.nvda_remote_invalid_connection); return@commitInput }
        dialog.dismiss()
        onConnect(computer, value)
      }
    }
  }

  private fun options(computer: NvdaRemoteComputer) {
    showDialog(AlertDialog.Builder(activity).setTitle(activity.getString(R.string.nvda_computer_options, computer.name))
      .setItems(arrayOf(activity.getString(R.string.nvda_computer_edit), activity.getString(R.string.nvda_computer_delete))) { _, index ->
        if (index == 0) edit(computer) else confirmDelete(computer)
      }.setNegativeButton(android.R.string.cancel, null))
  }

  private fun edit(computer: NvdaRemoteComputer) {
    val form = Form()
    val name = form.input(R.string.nvda_computer_name, computer.name).apply { filters = arrayOf(InputFilter.LengthFilter(80)) }
    val host = form.input(R.string.nvda_remote_host, computer.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
    val port = form.input(R.string.nvda_remote_port, computer.port.toString(), InputType.TYPE_CLASS_NUMBER)
    val password = form.password(computer.password.orEmpty())
    val remember = form.check(R.string.nvda_computer_remember_key, computer.password != null)
    var modifier = computer.nvdaModifier
    val modifierButton = form.button("") {}
    fun updateModifier() { modifierButton.setText(if (modifier == 0x2D) R.string.nvda_remote_modifier_insert else R.string.nvda_remote_modifier_caps) }
    updateModifier()
    modifierButton.setOnClickListener { modifier = if (modifier == 0x2D) 0x14 else 0x2D; updateModifier() }
    val error = form.message(R.string.nvda_computer_error).apply { visibility = View.GONE }
    val dialog = showDialog(AlertDialog.Builder(activity).setTitle(R.string.nvda_computer_edit)
      .setView(form.scroll).setPositiveButton(R.string.nvda_computer_save, null)
      .setNegativeButton(android.R.string.cancel, null))
    val save = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
    save.setOnClickListener {
      commitInput(dialog) {
        val label = name.text.toString().trim()
        val endpoint = NvdaRemoteEndpoint.parse(host.text.toString(), port.text.toString())
        if (label.isEmpty()) { name.error = activity.getString(R.string.nvda_computer_name_required); return@commitInput }
        if (endpoint == null) { host.error = activity.getString(R.string.nvda_remote_invalid_connection); return@commitInput }
        if (remember.isChecked && !validPassword(password.text.toString())) {
          password.error = activity.getString(R.string.nvda_remote_invalid_connection); return@commitInput
        }
        val updated = computer.copy(name = label, host = endpoint.host, port = endpoint.port, nvdaModifier = modifier,
          password = if (remember.isChecked) password.text.toString() else null)
        save.isEnabled = false
        work({ store.save(updated) }, { failure ->
          if (dialog.isShowing) {
            save.isEnabled = true
            error.setText(if (failure is NvdaRemoteComputersStore.DuplicateName) R.string.nvda_computer_duplicate else R.string.nvda_computer_error)
            error.visibility = View.VISIBLE
            error.announceForAccessibility(error.text)
          }
        }) {
          onChanged(updated)
          dialog.dismiss()
          report(activity.getString(R.string.nvda_computer_saved, updated.name))
          if (canShow()) show()
        }
      }
    }
  }

  private fun confirmDelete(computer: NvdaRemoteComputer) {
    val dialog = showDialog(AlertDialog.Builder(activity).setTitle(R.string.nvda_computer_delete)
      .setMessage(activity.getString(R.string.nvda_computer_delete_message, computer.name))
      .setPositiveButton(R.string.nvda_computer_delete, null).setNegativeButton(android.R.string.cancel, null))
    val delete = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
    delete.setOnClickListener {
      delete.isEnabled = false
      work({ store.delete(computer.id) }, {
        if (dialog.isShowing) delete.isEnabled = true
        report(activity.getString(R.string.nvda_computer_error))
      }) {
        onDeleted(computer.id)
        dialog.dismiss()
        report(activity.getString(R.string.nvda_computer_deleted, computer.name))
        if (canShow()) show()
      }
    }
  }

  private fun commitInput(dialog: AlertDialog, action: () -> Unit) {
    val editor = dialog.currentFocus as? EditText
    if (editor == null) { action(); return }
    TalkBackService.getInstance()?.commitPendingBrailleInput(activity.packageName, editor.id)
    editor.post { if (dialog.isShowing && canShow()) action() }
  }

  private fun <T> work(operation: () -> T, failed: (Throwable) -> Unit = {
    report(activity.getString(if (it is NvdaRemoteComputersStore.DuplicateName) R.string.nvda_computer_duplicate else R.string.nvda_computer_error))
  }, completed: (T) -> Unit) {
    WORKER.execute {
      val result = runCatching(operation)
      main.post {
        if (!activity.isDestroyed && !activity.isFinishing) result.fold(completed, failed)
      }
    }
  }

  private inner class Form {
    private val content = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      val padding = (16 * resources.displayMetrics.density).toInt()
      setPadding(padding, padding, padding, padding)
    }
    val scroll = ScrollView(activity).apply { isSaveEnabled = false; addView(content) }
    fun message(resource: Int) = TextView(activity).apply { setText(resource); content.addView(this) }
    fun input(resource: Int, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
      val label = message(resource)
      return KeyboardSafeEditText(activity).apply {
        id = View.generateViewId(); isSaveEnabled = false
        inputType = type; setText(value)
        label.labelFor = id
        content.addView(this)
      }
    }
    fun check(resource: Int, checked: Boolean) = CheckBox(activity).apply {
      isSaveEnabled = false; setText(resource); isChecked = checked; content.addView(this)
    }
    fun password(value: String): EditText {
      val field = input(R.string.nvda_remote_key, value, InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
      check(R.string.nvda_remote_show_key, false).setOnCheckedChangeListener { _, visible ->
        field.transformationMethod = if (visible) null else PasswordTransformationMethod.getInstance()
        field.setSelection(field.text.length)
      }
      return field
    }
    fun button(label: String, action: () -> Unit) = Button(activity).apply {
      text = label; setOnClickListener { action() }; content.addView(this)
    }
  }

  companion object {
    private val WORKER = NvdaRemoteComputersStore.WORKER
    private fun validPassword(value: String) = value.isNotBlank() && value.length <= 1024
  }
}
