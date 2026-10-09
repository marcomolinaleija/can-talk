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

package com.google.android.accessibility.talkback.backup

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Settings screen allowing users to back up and restore all TalkBack/Backtalk
 * preferences, gestures, and custom configurations.
 */
class BackupRestoreFragment : TalkbackBaseFragment(R.xml.backup_restore_preferences) {

  private val createBackupLauncher =
    registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
      if (uri != null) {
        writeBackup(uri)
      }
    }

  private val openBackupLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        confirmAndRestore(uri)
      }
    }

  override fun getTitle(): CharSequence = getText(R.string.title_pref_backup_restore)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)

    findPreference<Preference>(getString(R.string.pref_backup_export_key))
      ?.setOnPreferenceClickListener {
        createBackupLauncher.launch(generateBackupFileName())
        true
      }

    findPreference<Preference>(getString(R.string.pref_backup_import_key))
      ?.setOnPreferenceClickListener {
        openBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        true
      }
  }

  private fun generateBackupFileName(): String {
    val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
    return "cantalk-backup-$dateStr.json"
  }

  private fun writeBackup(uri: Uri) {
    val context = context?.applicationContext ?: return
    executor.execute {
      val json = try {
        TalkBackBackup.exportBackup(context)
      } catch (e: Exception) {
        LogUtils.e(TAG, "Failed to export backup: %s", e)
        null
      }

      val success = if (json != null) {
        try {
          context.contentResolver.openOutputStream(uri)?.use { os ->
            os.write(json.toByteArray(Charsets.UTF_8))
            os.flush()
          } != null
        } catch (e: IOException) {
          LogUtils.e(TAG, "Failed to write backup to uri: %s", e)
          false
        }
      } else false

      view?.post {
        if (!isAdded) return@post
        val msgRes = if (success) {
          R.string.toast_backup_export_success
        } else {
          R.string.toast_backup_export_failed
        }
        Toast.makeText(requireContext(), msgRes, Toast.LENGTH_SHORT).show()
      }
    }
  }

  private fun confirmAndRestore(uri: Uri) {
    val context = context ?: return
    A11yAlertDialogWrapper.materialDialogBuilder(context)
      .setTitle(R.string.dialog_title_confirm_restore)
      .setMessage(R.string.dialog_message_confirm_restore)
      .setPositiveButton(R.string.dialog_button_restore) { _, _ ->
        performRestore(uri)
      }
      .setNegativeButton(android.R.string.cancel, null)
      .create()
      .show()
  }

  private fun performRestore(uri: Uri) {
    val context = context?.applicationContext ?: return
    executor.execute {
      val json = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
          stream.readBytes().decodeToString()
        }
      } catch (e: IOException) {
        LogUtils.e(TAG, "Failed to read backup from uri: %s", e)
        null
      }

      val result = if (json != null) {
        TalkBackBackup.importBackup(context, json)
      } else {
        TalkBackBackup.ImportResult.Failure(TalkBackBackup.ImportError.IO_ERROR)
      }

      view?.post {
        if (!isAdded) return@post
        when (result) {
          is TalkBackBackup.ImportResult.Success -> {
            Toast.makeText(
              requireContext(),
              R.string.toast_backup_restore_success,
              Toast.LENGTH_LONG,
            ).show()
          }
          is TalkBackBackup.ImportResult.Failure -> {
            val errorMsgRes = when (result.error) {
              TalkBackBackup.ImportError.INVALID_JSON,
              TalkBackBackup.ImportError.INVALID_FORMAT -> R.string.backup_error_invalid_format
              TalkBackBackup.ImportError.UNSUPPORTED_VERSION -> R.string.backup_error_unsupported_version
              TalkBackBackup.ImportError.CHECKSUM_MISMATCH -> R.string.backup_error_checksum_mismatch
              TalkBackBackup.ImportError.COMMIT_FAILED -> R.string.backup_error_commit_failed
              TalkBackBackup.ImportError.IO_ERROR -> R.string.backup_error_io
            }
            val msg = getString(R.string.toast_backup_restore_failed, getString(errorMsgRes))
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
          }
        }
      }
    }
  }

  companion object {
    private const val TAG = "BackupRestoreFragment"
    private val executor = Executors.newSingleThreadExecutor()
  }
}
