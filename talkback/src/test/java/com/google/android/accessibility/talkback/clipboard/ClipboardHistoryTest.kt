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

  @Test fun renameAndEdit_preservePosition_andSurviveRestartAndRecopy() {
    store.record("primero")
    store.record("segundo")
    assertTrue(store.rename("primero", "  Correo\n personal  "))
    assertEquals("Correo personal", store.items().last().label)

    // Re-copying moves to front while preserving label.
    assertTrue(store.record("primero"))
    assertEquals("primero", store.items().first().text)
    assertEquals("Correo personal", store.items().first().label)

    // Editing updates content, label, and favorite in place.
    val editResult = store.update("segundo", "segundo editado", "Nota importante", favorite = true)
    assertEquals(ClipboardHistoryStore.EditResult.SUCCESS, editResult)
    val restored = ClipboardHistoryStore(context.getSharedPreferences("history_test", Context.MODE_PRIVATE))
    assertEquals(listOf("primero", "segundo editado"), restored.items().map { it.text })
    assertEquals("Nota importante", restored.items()[1].label)
    assertTrue(restored.items()[1].favorite)
  }

  @Test fun addManual_andValidationErrors() {
    assertEquals(ClipboardHistoryStore.EditResult.INVALID_TEXT, store.addManual("   "))
    assertEquals(
      ClipboardHistoryStore.EditResult.TEXT_TOO_LONG,
      store.addManual("x".repeat(ClipboardHistoryStore.MAX_TEXT_LENGTH + 1)),
    )
    assertEquals(
      ClipboardHistoryStore.EditResult.SUCCESS,
      store.addManual("hola@ejemplo.com", "Correo", favorite = true),
    )
    assertEquals(
      ClipboardHistoryStore.EditResult.DUPLICATE,
      store.addManual("hola@ejemplo.com", "Otro nombre"),
    )
    assertEquals(
      ClipboardHistoryStore.EditResult.NOT_FOUND,
      store.update("inexistente", "nuevo", "etiqueta"),
    )
    val entry = store.items().single()
    assertEquals("hola@ejemplo.com", entry.text)
    assertEquals("Correo", entry.label)
    assertTrue(entry.favorite)
  }

  @Test fun search_isAccentAndCaseInsensitive_ranksLabelsFirst_andSupportsMultiWord() {
    store.addManual("Calle Juárez 123, Ciudad de México", "Dirección casa", favorite = true)
    store.addManual("Enviar dirección por mensaje", "", favorite = false)
    store.addManual("012345678901234567", "CLABE del banco principal", favorite = true)

    val byAccent = store.search("direccion")
    assertEquals(2, byAccent.size)
    // Label match comes before text-only match.
    assertEquals("Dirección casa", byAccent.first().label)

    val multiWord = store.search("banco clabe")
    assertEquals(1, multiWord.size)
    assertEquals("012345678901234567", multiWord.single().text)

    val favOnly = store.search("direccion", favoritesOnly = true)
    assertEquals(1, favOnly.size)
    assertEquals("Dirección casa", favOnly.single().label)
  }

  @Test fun deleteClearAndMove_syncAcrossStoreInstances() {
    val prefs = context.getSharedPreferences("history_test", Context.MODE_PRIVATE)
    val secondStore = ClipboardHistoryStore(prefs)
    store.addManual("uno", favorite = true)
    store.addManual("dos", favorite = false)
    store.addManual("tres", favorite = true)

    // secondStore automatically sees changes made by store.
    assertEquals(listOf("tres", "dos", "uno"), secondStore.items().map { it.text })

    assertTrue(secondStore.moveInView("uno", -1, favoritesOnly = true))
    assertEquals(listOf("uno", "tres"), store.items(favoritesOnly = true).map { it.text })

    assertEquals(1, secondStore.clear(keepFavorites = true))
    assertEquals(listOf("uno", "tres"), store.items().map { it.text })

    assertTrue(store.delete("uno"))
    assertEquals(listOf("tres"), secondStore.items().map { it.text })

    assertEquals(1, store.clear(keepFavorites = false))
    assertTrue(secondStore.items().isEmpty())
  }

  @Test fun contextMenu_supportsAppendLastUtteranceToClipboard() {
    val service = org.robolectric.Robolectric.buildService(ClipboardHistoryControllerTest.RecordingService::class.java).create().get()
    val processor = com.google.android.accessibility.talkback.contextmenu.ContextMenuItemClickProcessor(
      service,
      { true },
      null,
    )
    val item = com.google.android.accessibility.talkback.contextmenu.ContextMenuItem(
      service,
      0,
      R.id.append_last_utterance_to_clipboard,
      0,
      "Añadir última frase",
    )
    assertTrue(processor.isItemSupported(item))
  }

  @Test fun customMaxEntries_allowsIncreasingOrLoweringLimit_whileKeepingFavorites() {
    val prefs = context.getSharedPreferences("history_limit_test", Context.MODE_PRIVATE)
    prefs.edit().clear().commit()
    var limit = 150
    val customStore = ClipboardHistoryStore(prefs) { limit }

    customStore.addManual("fav", favorite = true)
    repeat(130) { customStore.record("item $it") }
    assertEquals(131, customStore.items().size)
    assertTrue(customStore.items(favoritesOnly = true).any { it.text == "fav" })

    // Lowering the limit trims non-favorites first while preserving favorites.
    limit = 20
    val removed = customStore.applyLimit()
    assertEquals(111, removed)
    assertEquals(20, customStore.items().size)
    assertTrue(customStore.items(favoritesOnly = true).any { it.text == "fav" })
  }
}
