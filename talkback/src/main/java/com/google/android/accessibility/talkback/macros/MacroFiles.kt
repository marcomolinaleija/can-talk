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

package com.google.android.accessibility.talkback.macros

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON export and import helper for macros, compatible with SandFly's `sandfly-items` file format.
 */
class MacroFileLauncher(
  private val fragment: Fragment,
  private val onImported: () -> Unit,
) {
  private var pendingExport: ((Context) -> String)? = null

  private val exportLauncher =
    fragment.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
      uri ->
      val producer = pendingExport
      pendingExport = null
      if (uri != null && producer != null) {
        writeUri(uri, producer)
      }
    }

  private val importLauncher =
    fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        importUri(uri)
      }
    }

  fun export(defaultName: String, producer: (Context) -> String) {
    pendingExport = producer
    val safeName =
      defaultName.trim().ifEmpty { "macros" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    exportLauncher.launch("$safeName.json")
  }

  fun importMacros() {
    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
  }

  fun importFile() = importMacros()

  private fun writeUri(uri: Uri, producer: (Context) -> String) {
    val context = fragment.context ?: return
    try {
      val content = producer(context)
      context.contentResolver.openOutputStream(uri)?.use { stream ->
        stream.write(content.toByteArray(Charsets.UTF_8))
      } ?: error("Null output stream")
      Toast.makeText(context, R.string.item_file_exported, Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
      Toast.makeText(context, R.string.item_file_export_failed, Toast.LENGTH_SHORT).show()
    }
  }

  private fun importUri(uri: Uri) {
    val context = fragment.context ?: return
    try {
      val raw =
        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
          it.readText()
        } ?: error("Null input stream")
      val imported = parseMacrosPayload(raw)
      if (imported.isEmpty()) {
        Toast.makeText(context, R.string.macro_import_none, Toast.LENGTH_SHORT).show()
        return
      }
      val prefs = SharedPreferencesUtils.getSharedPreferences(context)
      val existing = Macros.read(prefs).toMutableList()
      val existingIds = existing.mapTo(HashSet()) { it.id }
      var added = 0
      for (macro in imported) {
        val nextId = if (macro.id in existingIds || macro.id.isBlank()) Macros.newId() else macro.id
        existingIds += nextId
        existing += macro.copy(id = nextId, shortcutToken = null)
        added++
      }
      Macros.write(prefs, existing)
      val countText =
        context.resources.getQuantityString(R.plurals.item_file_count_macros, added, added)
      Toast.makeText(
        context,
        context.getString(R.string.item_file_imported, countText),
        Toast.LENGTH_SHORT,
      ).show()
      onImported()
    } catch (_: Exception) {
      Toast.makeText(context, R.string.macro_import_failed, Toast.LENGTH_SHORT).show()
    }
  }

  companion object {
    const val isAvailable: Boolean = true

    fun exportMacros(context: Context, ids: Set<String>?): String {
      val prefs = SharedPreferencesUtils.getSharedPreferences(context)
      val all = Macros.read(prefs)
      val wanted = if (ids == null) all else all.filter { it.id in ids }
      val root =
        JSONObject()
          .put("sandfly-items", 1)
          .put("macros", JSONArray(Macros.toJson(wanted, shortcuts = false)))
      return root.toString(2)
    }

    private fun parseMacrosPayload(raw: String): List<Macros.Macro> {
      val trimmed = raw.trim()
      if (trimmed.isEmpty()) return emptyList()
      val arrayJson =
        if (trimmed.startsWith("[")) {
          trimmed
        } else {
          val obj = JSONObject(trimmed)
          obj.optJSONArray("macros")?.toString() ?: return emptyList()
        }
      return Macros.fromJson(arrayJson, moveAppTests = true)
    }
  }
}
