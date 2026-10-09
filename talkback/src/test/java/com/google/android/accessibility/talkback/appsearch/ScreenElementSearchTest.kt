/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.appsearch

import android.app.Application
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScreenElementSearchTest {
  private lateinit var window: AccessibilityWindowInfo
  private lateinit var root: AccessibilityNodeInfo
  private lateinit var search: ScreenElementSearch
  private val focused = mutableListOf<AccessibilityNodeInfoCompat>()
  private val actions = mutableListOf<Pair<AccessibilityNodeInfoCompat, Int>>()
  private var acceptAction = true
  @Before fun setup() {
    window = AccessibilityWindowInfo.obtain()
    shadowOf(window).setId(10)
    root = node("")
    search = ScreenElementSearch({ if (it == 10) AccessibilityNodeInfoCompat.wrap(root) else null },
      { AccessibilityNodeInfoUtils.getNodeText(it)?.toString() ?: it.hintText?.toString().orEmpty() },
      { focused.add(it); true }, { node, action -> actions.add(node to action); acceptAction })
  }
  private fun node(text: String, parent: AccessibilityNodeInfo? = null): AccessibilityNodeInfo {
    val node = AccessibilityNodeInfo.obtain().apply {
      this.text = text
      isVisibleToUser = true
      isEnabled = true
      setBoundsInScreen(Rect(0, 0, 500, 100))
    }
    shadowOf(node).setAccessibilityWindowInfo(window)
    shadowOf(node).setRefreshReturnValue(true)
    parent?.let { shadowOf(it).addChild(node) }
    return node
  }
  private fun select(query: String): ScreenElementSearch.Entry {
    search.load(10); search.search(query)
    return search.selected!!
  }

  @Test fun findsVisibleLabelsIgnoringAccentsAndPreservesEqualNamedNodes() {
    node("Configuración", root)
    node("Configuración", root)
    node("Configuración oculta", root).isVisibleToUser = false
    node("Configuración secreta", root).isPassword = true
    search.load(10); search.search("CONFIGURACION")
    assertEquals(2, search.matches.size)
    assertNotEquals(search.matches[0].node, search.matches[1].node)
    search.move(-1); assertEquals(1, search.index)
    search.move(1); assertEquals(0, search.index)
    search.search(" "); assertNull(search.selected)
  }

  @Test fun activatesClickableAncestorOfMatchedTextInsteadOfTheTextChild() {
    val row = node("", root).apply { isClickable = true }
    node("Enviar", row)
    val entry = select("enviar")
    assertEquals(ScreenElementSearch.Activation.ACTIVATED, search.activate(entry))
    assertEquals(listOf(AccessibilityNodeInfoCompat.wrap(row) to AccessibilityNodeInfoCompat.ACTION_CLICK), actions)
  }

  @Test fun editableHintFocusesInputWhenThereIsNoClickAction() {
    val editor = node("", root).apply { isEditable = true; hintText = "Buscar" }
    assertEquals(ScreenElementSearch.Activation.ACTIVATED, search.activate(select("buscar")))
    assertEquals(listOf(AccessibilityNodeInfoCompat.wrap(editor) to AccessibilityNodeInfoCompat.ACTION_FOCUS), actions)
  }

  @Test fun plainTextReceivesAccessibilityFocusWithoutClicking() {
    val text = node("Hola mundo", root)
    assertEquals(ScreenElementSearch.Activation.FOCUSED, search.activate(select("mundo")))
    assertEquals(listOf(AccessibilityNodeInfoCompat.wrap(text)), focused)
    assertTrue(actions.isEmpty())
  }

  @Test fun vanishedHiddenOrRenamedNodeCannotActivate() {
    node("Enviar", root).apply { isClickable = true }
    val entry = select("enviar")
    // Robolectric does not fetch changed view data on refresh. Update the captured snapshot.
    val button = entry.node.unwrap()
    shadowOf(button).setRefreshReturnValue(false)
    assertEquals(ScreenElementSearch.Activation.UNAVAILABLE, search.activate(entry))
    shadowOf(button).setRefreshReturnValue(true)
    button.isVisibleToUser = false
    assertEquals(ScreenElementSearch.Activation.UNAVAILABLE, search.activate(entry))
    button.isVisibleToUser = true
    button.text = "Eliminar"
    assertEquals(ScreenElementSearch.Activation.UNAVAILABLE, search.activate(entry))
    assertTrue(actions.isEmpty())
  }

  @Test fun changedOrDisabledAncestorCannotReceiveTheClick() {
    val originalRow = node("Conversación de Ana", root).apply { isClickable = true }
    node("Enviar", originalRow)
    val entry = select("enviar")
    val row = entry.target!!.unwrap()
    row.text = "Conversación de Luis"
    assertEquals(ScreenElementSearch.Activation.UNAVAILABLE, search.activate(entry))
    row.text = "Conversación de Ana"; row.isEnabled = false
    assertEquals(ScreenElementSearch.Activation.UNAVAILABLE, search.activate(entry))
    assertTrue(actions.isEmpty())
  }

  @Test fun failedClickIsReportedAndNeverFallsBackToAnotherTarget() {
    node("Enviar", root).isClickable = true
    acceptAction = false
    assertEquals(ScreenElementSearch.Activation.FAILED, search.activate(select("enviar")))
    assertEquals(1, actions.size)
  }

  @Test fun anotherWindowCannotProvideSearchResults() {
    node("Enviar", root)
    search.load(20); search.search("enviar")
    assertTrue(search.matches.isEmpty())
  }
}
