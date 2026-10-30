/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.clipboard

import android.app.Application
import android.content.Context
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.gesture.GestureShortcutMapping
import com.google.android.accessibility.talkback.preference.base.GestureListPreference
import com.google.android.accessibility.utils.FormFactorUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es-rMX", application = Application::class)
class ClipboardHistoryTest {
  private lateinit var store: ClipboardHistoryStore
  private lateinit var context: Context
  @Before fun setUp() {
    context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), androidx.appcompat.R.style.Theme_AppCompat)
    context.getSharedPreferences("history_test", Context.MODE_PRIVATE).edit().clear().commit()
    store = ClipboardHistoryStore(context.getSharedPreferences("history_test", Context.MODE_PRIVATE))
  }

  @Test fun duplicatesMoveToFront_andFavoritesSurviveRestart() {
    store.record("primero")
    store.toggleFavorite("primero")
    store.record("segundo")
    store.record("primero")
    assertEquals(listOf("primero", "segundo"), store.items().map { it.text })
    val restored = ClipboardHistoryStore(context.getSharedPreferences("history_test", Context.MODE_PRIVATE))
    assertEquals(store.items(), restored.items())
    assertEquals(listOf("primero"), restored.items(true).map { it.text })
  }

  @Test fun oldFavoritesSurviveEviction_andGeneralIsBounded() {
    store.record("favorito antiguo")
    store.toggleFavorite("favorito antiguo")
    repeat(110) { store.record("texto $it") }
    assertEquals(100, store.items().size)
    assertEquals("texto 109", store.items().first().text)
    assertEquals("favorito antiguo", store.items(true).single().text)
    assertFalse(store.items().any { it.text == "texto 0" })
  }

  @Test fun favoritesCanBeRemoved_andHaveALimit() {
    repeat(51) { store.record("texto $it") }
    repeat(50) { assertEquals(true, store.toggleFavorite("texto $it")) }
    assertNull(store.toggleFavorite("texto 50"))
    assertEquals(false, store.toggleFavorite("texto 0"))
    assertEquals(true, store.toggleFavorite("texto 50"))
    assertEquals(50, store.items(true).size)
  }

  @Test fun fullTextPreservedWithoutTruncation_andOversizeRejected() {
    val text = "a".repeat(20_000)
    assertTrue(store.record(text))
    assertFalse(store.record(text + "b"))
    assertFalse(store.record("   "))
    assertEquals(text, store.items().single().text)
  }

  @Test fun damagedData_doesNotPreventOpening() {
    val prefs = context.getSharedPreferences("history_test", Context.MODE_PRIVATE)
    prefs.edit().putString("entries_v1", "broken JSON").commit()
    val restored = ClipboardHistoryStore(prefs)
    assertTrue(restored.items().isEmpty())
    assertTrue(restored.record("nuevo"))
  }

  @Test fun actionAvailableInGestureSettings_andItsSavedValueResolvesInSpanish() {
    FormFactorUtils.initialize(context)
    val preference = GestureListPreference(context, null)
    preference.key = "history_test_gesture"
    preference.setValue("CLIPBOARD_HISTORY")
    assertEquals("Historial del portapapeles", preference.summary)
    assertEquals("Historial del portapapeles",
      GestureShortcutMapping.getActionString(context, "CLIPBOARD_HISTORY"))
    assertEquals("Historial del portapapeles", context.getString(R.string.clipboard_history_title))
  }
}
