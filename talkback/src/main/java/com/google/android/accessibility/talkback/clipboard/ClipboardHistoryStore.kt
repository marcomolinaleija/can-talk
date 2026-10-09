/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.clipboard

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Local text history. Favorites survive eviction and duplicates retain their favorite state. */
class ClipboardHistoryStore(private val prefs: SharedPreferences) {
  data class Entry(val text: String, val favorite: Boolean = false)
  private val entries = mutableListOf<Entry>()

  init {
    try {
      val data = JSONArray(prefs.getString(KEY, "[]"))
      for (i in 0 until minOf(data.length(), MAX_ENTRIES)) {
        val item = data.optJSONObject(i) ?: continue
        val text = item.optString("text")
        if (valid(text) && entries.none { it.text == text }) {
          entries.add(Entry(text, item.optBoolean("favorite")))
        }
      }
    } catch (_: RuntimeException) {
      // A damaged history must not prevent the screen reader from starting.
    } catch (_: org.json.JSONException) {
    }
  }

  fun items(favoritesOnly: Boolean = false): List<Entry> =
    entries.filter { !favoritesOnly || it.favorite }

  fun record(text: String): Boolean {
    if (!valid(text)) return false
    val existing = entries.firstOrNull { it.text == text }
    if (entries.firstOrNull()?.text == text) return true
    entries.removeAll { it.text == text }
    entries.add(0, existing ?: Entry(text))
    while (entries.size > MAX_ENTRIES) {
      val index = entries.indexOfLast { !it.favorite }
      entries.removeAt(if (index >= 0) index else entries.lastIndex)
    }
    save()
    return true
  }

  /** Returns null if the item is missing or the favorites limit is reached. */
  fun toggleFavorite(text: String): Boolean? {
    val index = entries.indexOfFirst { it.text == text }
    if (index < 0) return null
    val entry = entries[index]
    if (!entry.favorite && entries.count { it.favorite } >= MAX_FAVORITES) return null
    entries[index] = entry.copy(favorite = !entry.favorite)
    save()
    return !entry.favorite
  }

  private fun save() {
    val data = JSONArray()
    entries.forEach { data.put(JSONObject().put("text", it.text).put("favorite", it.favorite)) }
    prefs.edit().putString(KEY, data.toString()).apply()
  }

  companion object {
    const val MAX_ENTRIES = 100
    const val MAX_FAVORITES = 50
    const val MAX_TEXT_LENGTH = 20_000
    private const val KEY = "entries_v1"
    private fun valid(text: String) = text.isNotBlank() && text.length <= MAX_TEXT_LENGTH
  }
}
