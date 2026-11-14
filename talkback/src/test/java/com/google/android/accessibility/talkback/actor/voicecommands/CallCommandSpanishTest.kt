/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.actor.voicecommands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallCommandSpanishTest {
  private fun normalize(text: String) = VoiceCommandFuzzyMatcher.normalize(text)
  private fun parse(text: String) = CallCommandParser.parse(normalize(text))

  @Test
  fun callsAContactWithoutRemovingAccentsOrArticlesFromItsName() {
    assertEquals(CallCommand.Dial("maría pérez", false), parse("Llama a María Pérez"))
    assertEquals(CallCommand.Dial("josé", false), parse("¿Podrías por favor llamar a José?"))
    assertEquals(CallCommand.Dial("mamá", false), parse("Oye CAN Talk, llámale a mamá"))
    assertEquals(CallCommand.Dial("la oficina", false), parse("Llama a La Oficina por favor"))
    assertEquals(CallCommand.Dial("dentista", false), parse("Llama al dentista"))
  }

  @Test
  fun startsCallsOnSpeakerWhenExplicitlyRequested() {
    for (suffix in listOf("con altavoz", "en altavoz", "con el altavoz", "con manos libres")) {
      assertEquals(suffix, CallCommand.Dial("maría", true), parse("Llama a María $suffix"))
    }
  }

  @Test
  fun understandsCallControlsAsWholeCommands() {
    for (phrase in listOf("Contesta", "contestar", "contesta la llamada", "acepta la llamada")) {
      assertEquals(phrase, CallCommand.Answer, parse(phrase))
    }
    for (phrase in listOf("Rechaza", "rechazar", "rechaza la llamada", "rechazar la llamada")) {
      assertEquals(phrase, CallCommand.Decline, parse(phrase))
    }
    for (phrase in listOf("Cuelga", "colgar", "cuelga la llamada", "termina la llamada")) {
      assertEquals(phrase, CallCommand.HangUp, parse(phrase))
    }
    assertNull(parse("no cuelgues"))
    assertNull(parse("contesta mi pregunta"))
    assertNull(parse("rechaza la propuesta"))
    assertNull(parse("llama"))
  }

  @Test
  fun readsSpanishDigitsAndInternationalPrefixes() {
    assertEquals("5512345678", CallCommandParser.toPhoneNumber("cinco cinco uno dos tres cuatro cinco seis siete ocho"))
    assertEquals("+525512345678", CallCommandParser.toPhoneNumber("más 52 55 1234 5678"))
    assertEquals("+525512345678", CallCommandParser.toPhoneNumber("+52 55 1234 5678"))
    assertEquals("001", CallCommandParser.toPhoneNumber("cero cero uno"))
    assertEquals(CallCommand.Dial("número 5512345678", false), parse("Marca al número 5512345678"))
    assertEquals("5512345678", CallCommandParser.toPhoneNumber("número 5512345678"))
  }

  @Test
  fun joinsSpokenPairsWithoutAddingExtraDigits() {
    assertEquals("551234", CallCommandParser.toPhoneNumber("cincuenta y cinco doce treinta y cuatro"))
    assertEquals("222326", CallCommandParser.toPhoneNumber("veintidós veintitrés veintiséis"))
    assertNull(CallCommandParser.toPhoneNumber("cincuenta y cero"))
    assertNull(CallCommandParser.toPhoneNumber("cincuenta y"))
    assertNull(CallCommandParser.toPhoneNumber("un un un"))
    assertNull(CallCommandParser.toPhoneNumber("María dos"))
  }

  @Test
  fun requiresAnExplicitConfirmationAndUnderstandsCancellation() {
    for (phrase in listOf("Sí", "si", "sí por favor", "sí, llámale", "de acuerdo", "adelante", "vale")) {
      assertTrue(phrase, CallCommandParser.isYes(normalize(phrase)))
    }
    for (phrase in listOf("no", "sí pero no ahora", "sí no llames", "no sé", "")) {
      assertFalse(phrase, CallCommandParser.isYes(normalize(phrase)))
    }
    for (phrase in listOf("No", "no gracias", "cancelar", "cancela", "mejor no", "no llames")) {
      assertTrue(phrase, CallCommandParser.isNo(normalize(phrase)))
    }
  }

  @Test
  fun choosesContactsBySpanishNumbersAndOrdinals() {
    for (phrase in listOf("dos", "número dos", "opción dos", "la segunda", "la opción dos")) {
      assertEquals(phrase, 2, CallCommandParser.parseChoice(normalize(phrase), 3))
    }
    assertEquals(5, CallCommandParser.parseChoice("quinta", 5))
    assertNull(CallCommandParser.parseChoice("cuatro", 3))
  }

  @Test
  fun contactMatchingToleratesMissingAccentsButKeepsEnyeDistinct() {
    val contacts = listOf(
      CallContact(1, "José Pérez", "111"),
      CallContact(2, "María", "222"),
      CallContact(3, "Peña", "333"),
      CallContact(4, "Pena", "444"),
    )
    assertEquals(listOf(contacts[0]), CallContactMatcher.match("Jose Perez", contacts))
    assertEquals(listOf(contacts[1]), CallContactMatcher.match("Maria", contacts))
    assertEquals(listOf(contacts[2]), CallContactMatcher.match("Peña", contacts))
    assertEquals(listOf(contacts[3]), CallContactMatcher.match("Pena", contacts))
  }
}
