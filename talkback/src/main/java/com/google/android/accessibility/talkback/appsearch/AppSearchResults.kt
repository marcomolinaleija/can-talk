/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.appsearch

import java.text.Normalizer
import java.util.Locale

/** Search only launchable activities, retaining each component's identity even for equal labels. */
class AppSearchResults(apps: List<App>) {
  data class App(val label: String, val packageName: String, val className: String)
  private val apps = apps.distinctBy { it.packageName to it.className }
  var query: String = ""
    private set
  var matches: List<App> = emptyList()
    private set
  var index: Int = 0
    private set
  val selected: App? get() = matches.getOrNull(index)

  fun search(text: String) {
    query = text
    index = 0
    val needle = normalize(text)
    matches = if (needle.isEmpty()) emptyList() else apps
      .filter { normalize(it.label).contains(needle) }
      .sortedWith(compareBy<App> { !normalize(it.label).startsWith(needle) }
        .thenBy { normalize(it.label) }.thenBy { it.packageName }.thenBy { it.className })
  }

  fun move(step: Int) {
    if (matches.isNotEmpty()) index = Math.floorMod(index + step, matches.size)
  }

  companion object {
    private val marks = Regex("\\p{M}+")
    private val spaces = Regex("\\s+")
    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
      .replace(marks, "").lowercase(Locale.ROOT).trim().replace(spaces, " ")
  }
}
