/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.clipboard

import android.content.Context
import android.content.SharedPreferences
import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local text history with favorites, optional custom labels, full-text search, and editing.
 * Synchronizes automatically with the underlying [SharedPreferences] so the accessibility service
 * and settings UI always see each other's changes.
 */
class ClipboardHistoryStore(
  private val prefs: SharedPreferences,
  private val maxEntriesProvider: () -> Int = { DEFAULT_MAX_ENTRIES },
) {
  data class Entry(
    val text: String,
    val favorite: Boolean = false,
    val label: String = "",
  )

  enum class EditResult {
    SUCCESS,
    INVALID_TEXT,
    TEXT_TOO_LONG,
    DUPLICATE,
    FAVORITES_FULL,
    NOT_FOUND,
  }

  val maxEntries: Int
    get() = maxEntriesProvider().coerceIn(MIN_ENTRIES_LIMIT, MAX_ENTRIES_LIMIT)

  val maxFavorites: Int
    get() = minOf(MAX_FAVORITES, maxEntries)

  private val entries = mutableListOf<Entry>()
  private var lastSerialized: String? = null

  init {
    reload()
  }

  @Synchronized
  fun reload() {
    val raw = prefs.getString(KEY, "[]") ?: "[]"
    lastSerialized = raw
    entries.clear()
    try {
      val data = JSONArray(raw)
      for (i in 0 until minOf(data.length(), MAX_ENTRIES_LIMIT)) {
        val item = data.optJSONObject(i) ?: continue
        val text = item.optString("text")
        if (valid(text) && entries.none { it.text == text }) {
          val label = sanitizeLabel(item.optString("label", ""))
          entries.add(Entry(text = text, favorite = item.optBoolean("favorite"), label = label))
        }
      }
    } catch (_: RuntimeException) {
      // A damaged history must not prevent the screen reader from starting.
    } catch (_: org.json.JSONException) {
    }
  }

  private fun syncIfChanged() {
    val raw = prefs.getString(KEY, "[]") ?: "[]"
    if (raw != lastSerialized) {
      reload()
    }
  }

  @Synchronized
  fun items(favoritesOnly: Boolean = false): List<Entry> {
    syncIfChanged()
    return entries.filter { !favoritesOnly || it.favorite }
  }

  /**
   * Accent- and case-insensitive search across custom labels and entry text.
   * Multi-word queries require all tokens to appear in the combined label + text.
   * Label matches are ranked ahead of text-only matches while preserving recency within ties.
   */
  @Synchronized
  fun search(query: String, favoritesOnly: Boolean = false): List<Entry> {
    syncIfChanged()
    val pool = entries.filter { !favoritesOnly || it.favorite }
    val needle = normalize(query)
    if (needle.isEmpty()) return pool
    val tokens = needle.split(' ').filter { it.isNotEmpty() }
    return pool.mapIndexedNotNull { position, entry ->
      val rank = matchRank(entry, needle, tokens)
      if (rank < 0) null else Triple(rank, position, entry)
    }.sortedWith(compareBy<Triple<Int, Int, Entry>> { it.first }.thenBy { it.second })
      .map { it.third }
  }

  @Synchronized
  fun record(text: String): Boolean {
    syncIfChanged()
    if (!valid(text)) return false
    val existing = entries.firstOrNull { it.text == text }
    if (entries.firstOrNull()?.text == text) return true
    entries.removeAll { it.text == text }
    entries.add(0, existing ?: Entry(text))
    evictOverflow()
    save()
    return true
  }

  @Synchronized
  fun addManual(text: String, label: String = "", favorite: Boolean = false): EditResult {
    syncIfChanged()
    if (text.isBlank()) return EditResult.INVALID_TEXT
    if (text.length > MAX_TEXT_LENGTH) return EditResult.TEXT_TOO_LONG
    if (entries.any { it.text == text }) return EditResult.DUPLICATE
    if (favorite && entries.count { it.favorite } >= maxFavorites) return EditResult.FAVORITES_FULL
    entries.add(0, Entry(text = text, favorite = favorite, label = sanitizeLabel(label)))
    evictOverflow()
    save()
    return EditResult.SUCCESS
  }

  @Synchronized
  fun update(
    originalText: String,
    newText: String,
    newLabel: String,
    favorite: Boolean? = null,
  ): EditResult {
    syncIfChanged()
    val index = entries.indexOfFirst { it.text == originalText }
    if (index < 0) return EditResult.NOT_FOUND
    if (newText.isBlank()) return EditResult.INVALID_TEXT
    if (newText.length > MAX_TEXT_LENGTH) return EditResult.TEXT_TOO_LONG
    if (newText != originalText && entries.any { it.text == newText }) return EditResult.DUPLICATE
    val current = entries[index]
    val nextFavorite = favorite ?: current.favorite
    if (nextFavorite && !current.favorite && entries.count { it.favorite } >= maxFavorites) {
      return EditResult.FAVORITES_FULL
    }
    entries[index] = Entry(text = newText, favorite = nextFavorite, label = sanitizeLabel(newLabel))
    save()
    return EditResult.SUCCESS
  }

  @Synchronized
  fun rename(text: String, newLabel: String): Boolean {
    syncIfChanged()
    val index = entries.indexOfFirst { it.text == text }
    if (index < 0) return false
    val sanitized = sanitizeLabel(newLabel)
    if (entries[index].label == sanitized) return true
    entries[index] = entries[index].copy(label = sanitized)
    save()
    return true
  }

  /** Returns null if the item is missing or the favorites limit is reached. */
  @Synchronized
  fun toggleFavorite(text: String): Boolean? {
    syncIfChanged()
    val index = entries.indexOfFirst { it.text == text }
    if (index < 0) return null
    val entry = entries[index]
    if (!entry.favorite && entries.count { it.favorite } >= maxFavorites) return null
    entries[index] = entry.copy(favorite = !entry.favorite)
    save()
    return !entry.favorite
  }

  @Synchronized
  fun delete(text: String): Boolean {
    syncIfChanged()
    val removed = entries.removeAll { it.text == text }
    if (removed) save()
    return removed
  }

  /** Removes entries and returns how many were deleted. */
  @Synchronized
  fun clear(keepFavorites: Boolean = true): Int {
    syncIfChanged()
    val before = entries.size
    if (keepFavorites) {
      entries.removeAll { !it.favorite }
    } else {
      entries.clear()
    }
    val removed = before - entries.size
    if (removed > 0) save()
    return removed
  }

  /** Moves [text] by [offset] (-1 up, +1 down) within either all entries or only favorites. */
  @Synchronized
  fun moveInView(text: String, offset: Int, favoritesOnly: Boolean = false): Boolean {
    syncIfChanged()
    if (offset == 0) return false
    val visibleIndices = entries.indices.filter { !favoritesOnly || entries[it].favorite }
    val pos = visibleIndices.indexOfFirst { entries[it].text == text }
    if (pos < 0) return false
    val targetPos = pos + offset
    if (targetPos !in visibleIndices.indices) return false
    val fromIndex = visibleIndices[pos]
    val toIndex = visibleIndices[targetPos]
    val item = entries.removeAt(fromIndex)
    entries.add(toIndex, item)
    save()
    return true
  }

  /** Applies the current [maxEntries] limit immediately, evicting oldest non-favorites first. */
  @Synchronized
  fun applyLimit(): Int {
    syncIfChanged()
    val before = entries.size
    evictOverflow()
    val removed = before - entries.size
    if (removed > 0) save()
    return removed
  }

  private fun evictOverflow() {
    val limit = maxEntries
    while (entries.size > limit) {
      val index = entries.indexOfLast { !it.favorite }
      entries.removeAt(if (index >= 0) index else entries.lastIndex)
    }
  }

  private fun save() {
    val data = JSONArray()
    entries.forEach { entry ->
      val obj = JSONObject()
        .put("text", entry.text)
        .put("favorite", entry.favorite)
      if (entry.label.isNotEmpty()) {
        obj.put("label", entry.label)
      }
      data.put(obj)
    }
    val serialized = data.toString()
    lastSerialized = serialized
    prefs.edit().putString(KEY, serialized).apply()
  }

  companion object {
    const val PREFS_NAME = "clipboard_history"
    const val MAX_ENTRIES = 100
    const val DEFAULT_MAX_ENTRIES = 100
    const val MIN_ENTRIES_LIMIT = 10
    const val MAX_ENTRIES_LIMIT = 500
    const val MAX_FAVORITES = 50
    const val MAX_TEXT_LENGTH = 20_000
    const val MAX_LABEL_LENGTH = 120
    private const val KEY = "entries_v1"
    private val combiningMarks = Regex("\\p{M}+")
    private val whitespace = Regex("\\s+")

    fun open(context: Context): ClipboardHistoryStore {
      val appContext = context.applicationContext
      return ClipboardHistoryStore(
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
      ) { ClipboardHistorySettings.getMaxEntries(appContext) }
    }

    private fun valid(text: String) = text.isNotBlank() && text.length <= MAX_TEXT_LENGTH

    fun sanitizeLabel(label: String): String {
      val cleaned = buildString(label.length) {
        for (ch in label) {
          append(if (ch.isISOControl()) ' ' else ch)
        }
      }.trim().replace(whitespace, " ")
      return cleaned.take(MAX_LABEL_LENGTH)
    }

    internal fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
      .replace(combiningMarks, "")
      .lowercase(Locale.ROOT)
      .trim()
      .replace(whitespace, " ")

    private fun matchRank(entry: Entry, needle: String, tokens: List<String>): Int {
      val normLabel = if (entry.label.isNotEmpty()) normalize(entry.label) else ""
      val normText = normalize(entry.text)
      if (normLabel.isNotEmpty()) {
        if (normLabel.startsWith(needle)) return 0
        if (normLabel.contains(needle)) return 1
        if (tokens.size > 1 && tokens.all { normLabel.contains(it) }) return 2
      }
      if (normText.startsWith(needle)) return 3
      if (normText.contains(needle)) return 4
      if (tokens.size > 1) {
        val combined = if (normLabel.isNotEmpty()) "$normLabel $normText" else normText
        if (tokens.all { combined.contains(it) }) return 5
      }
      return -1
    }
  }
}
