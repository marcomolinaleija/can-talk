/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.appsearch

import com.google.android.accessibility.talkback.appsearch.AppSearchResults.App
import org.junit.Assert.*
import org.junit.Test

class AppSearchResultsTest {
  private val youtube = App("YouTube", "youtube", "Main")
  private val music = App("YouTube Music", "music", "Main")
  private val other = App("Mi YouTube", "other", "Main")

  @Test fun prefixMatchesComeFirstAndTypingNarrowsResults() {
    val results = AppSearchResults(listOf(other, music, youtube))
    results.search("YOUTU")
    assertEquals(listOf(youtube, music, other), results.matches)
    results.search("youtube music")
    assertEquals(listOf(music), results.matches)
  }

  @Test fun searchIgnoresCaseAccentsAndExtraSpaces() {
    val app = App("Cámara   Pro", "camera", "Main")
    val results = AppSearchResults(listOf(app))
    results.search("  CAMARA pro ")
    assertEquals(app, results.selected)
  }

  @Test fun bothNavigationDirectionsWrapAndNewQueriesResetSelection() {
    val results = AppSearchResults(listOf(youtube, music, other))
    results.search("youtube")
    results.move(-1)
    assertEquals(other, results.selected)
    results.move(1)
    assertEquals(youtube, results.selected)
    repeat(3) { results.move(1) }
    assertEquals(youtube, results.selected)
    results.move(1)
    results.search("you")
    assertEquals(0, results.index)
  }

  @Test fun duplicateComponentsAreRemovedButEqualLabelsKeepDistinctApps() {
    val different = App("YouTube", "different", "Main")
    val results = AppSearchResults(listOf(youtube, youtube, different))
    results.search("youtube")
    assertEquals(2, results.matches.size)
    assertEquals(setOf(youtube, different), results.matches.toSet())
  }

  @Test fun emptyOrMissingQueryHasNoLaunchableSelection() {
    val results = AppSearchResults(listOf(youtube))
    results.search(" ")
    results.move(-1)
    assertNull(results.selected)
    results.search("not installed")
    results.move(1)
    assertNull(results.selected)
  }
}
