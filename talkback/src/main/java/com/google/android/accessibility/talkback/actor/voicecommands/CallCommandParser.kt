/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.google.android.accessibility.talkback.actor.voicecommands

/** A spoken request about phone calls. */
sealed interface CallCommand {
  /** Place a call to [target], which is a contact name or a phone number. */
  data class Dial(val target: String, val speaker: Boolean) : CallCommand

  object Answer : CallCommand

  object Decline : CallCommand

  object HangUp : CallCommand
}

/** Understands what a user said about calls. Pure, so it can be unit tested. */
object CallCommandParser {

  private val DIAL_REGEX =
    Regex("^(?:call|dial)\\s+(?:up\\s+)?(.+?)(?:\\s+(?:on|with|using)\\s+(?:the\\s+)?speaker(?:\\s*phone)?)?$")
  private val SPEAKER_REGEX = Regex("\\s(?:on|with|using)\\s+(?:the\\s+)?speaker(?:\\s*phone)?$")
  private val SPANISH_DIAL_REGEX =
    Regex("^(?:llamar|llama|llame|ll[áa]male|ll[áa]mele|marcar|marca|marque)\\s+(?:(?:a|al)\\s+)?(.+?)(?:\\s+(?:en|con|por)\\s+(?:el\\s+)?(?:altavoz|manos\\s+libres))?$")
  private val SPANISH_SPEAKER_REGEX =
    Regex("\\s(?:en|con|por)\\s+(?:el\\s+)?(?:altavoz|manos\\s+libres)$")

  private val ANSWER = setOf("answer", "answer call", "answer phone", "pick up", "pick up call", "pick up phone", "accept call", "accept",
    "contesta", "contestar", "contesta llamada", "contestar llamada", "responde llamada", "responder llamada", "acepta llamada", "aceptar llamada", "atiende llamada")
  private val DECLINE = setOf("decline", "decline call", "reject", "reject call", "decline phone", "reject phone",
    "rechaza", "rechazar", "rechaza llamada", "rechazar llamada", "declina llamada", "declinar llamada")
  private val HANG_UP = setOf("hang up", "hang up call", "hang up phone", "end call", "end phone call", "disconnect call",
    "cuelga", "colgar", "cuelga llamada", "colgar llamada", "cuelga telefono", "cuelga teléfono", "termina llamada", "terminar llamada", "finaliza llamada", "finalizar llamada", "corta llamada")

  private val YES = setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "confirm", "do it", "go ahead", "correct", "call",
    "sí", "si", "vale", "de acuerdo", "adelante", "confirmo", "confirmar", "hazlo", "correcto", "claro", "claro que sí", "claro que si", "está bien", "esta bien", "llama")

  /** Words that start a confirmation, so "yes please" and "yes call her" also count. */
  private val YES_STARTS = setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "confirm", "sí", "si", "vale", "confirmo")
  private val YES_FILLER = setOf("please", "call", "her", "him", "them", "now", "it", "go", "ahead",
    "llama", "llamar", "llámale", "llamale", "llámala", "llamala", "llámalo", "llamalo", "ahora", "adelante", "hazlo")
  private val NO = setOf("no", "nope", "cancel", "never mind", "nevermind", "dont", "do not",
    "cancelar", "cancela", "mejor no", "no llames", "no llamar", "olvídalo", "olvidalo", "déjalo", "dejalo")

  /** Words a speech engine often hears for a digit; they are also ordinary words. */
  private val AMBIGUOUS_DIGIT_WORDS = setOf("oh", "o", "to", "too", "for", "ate", "un", "una")

  private val DIGIT_WORDS =
    mapOf(
      "zero" to '0', "oh" to '0', "o" to '0', "one" to '1', "two" to '2', "to" to '2', "too" to '2',
      "three" to '3', "four" to '4', "for" to '4', "five" to '5', "six" to '6', "seven" to '7',
      "eight" to '8', "ate" to '8', "nine" to '9',
      "cero" to '0', "uno" to '1', "un" to '1', "una" to '1', "dos" to '2',
      "tres" to '3', "cuatro" to '4', "cinco" to '5', "seis" to '6',
      "siete" to '7', "ocho" to '8', "nueve" to '9')

  private val SPANISH_GROUPS = mapOf(
    "diez" to 10, "once" to 11, "doce" to 12, "trece" to 13, "catorce" to 14,
    "quince" to 15, "dieciseis" to 16, "dieciséis" to 16, "diecisiete" to 17,
    "dieciocho" to 18, "diecinueve" to 19, "veinte" to 20, "veintiuno" to 21,
    "veintidos" to 22, "veintidós" to 22, "veintitres" to 23, "veintitrés" to 23,
    "veinticuatro" to 24, "veinticinco" to 25, "veintiseis" to 26, "veintiséis" to 26,
    "veintisiete" to 27, "veintiocho" to 28, "veintinueve" to 29,
    "treinta" to 30, "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60,
    "setenta" to 70, "ochenta" to 80, "noventa" to 90)

  private val CHOICE_WORDS =
    mapOf(
      "one" to 1, "first" to 1, "two" to 2, "second" to 2, "to" to 2, "three" to 3, "third" to 3,
      "four" to 4, "fourth" to 4, "for" to 4, "five" to 5, "fifth" to 5,
      "uno" to 1, "primero" to 1, "primera" to 1, "dos" to 2, "segundo" to 2,
      "segunda" to 2, "tres" to 3, "tercero" to 3, "tercera" to 3,
      "cuatro" to 4, "cuarto" to 4, "cuarta" to 4, "cinco" to 5,
      "quinto" to 5, "quinta" to 5)

  /** Returns the call request in [normalized] (already run through the fuzzy matcher's normalize). */
  @JvmStatic
  fun parse(normalized: String): CallCommand? {
    val original = withoutPlease(normalized)
    val text = original.replace(Regex("\\b(?:the|la|el)\\b"), " ").replace(Regex("\\s+"), " ").trim()
    if (text in ANSWER) return CallCommand.Answer
    if (text in DECLINE) return CallCommand.Decline
    if (text in HANG_UP) return CallCommand.HangUp
    // "the" only matters inside a name, so the dial form reads the original.
    val match = SPANISH_DIAL_REGEX.matchEntire(original) ?: DIAL_REGEX.matchEntire(original) ?: return null
    val target = match.groupValues[1].trim()
    if (target.isEmpty()) return null
    return CallCommand.Dial(target,
      SPANISH_SPEAKER_REGEX.containsMatchIn(original) || SPEAKER_REGEX.containsMatchIn(original))
  }

  /**
   * Turns spoken or typed digits into a phone number, or returns null when [text] is not a number.
   * "nine eight seven 6 5 4", "987654" and "+91 98765 43210" all work.
   */
  @JvmStatic
  fun toPhoneNumber(text: String): String? {
    val input = text.trim().removePrefix("el ").removePrefix("número ").removePrefix("numero ")
    val tokens = input.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    val number = StringBuilder()
    var index = 0
    while (index < tokens.size) {
      val token = tokens[index]
      when {
        token in setOf("plus", "+", "más", "mas") -> if (index == 0) number.append('+') else return null
        token.startsWith("+") && index == 0 && token.drop(1).all { it.isDigit() } -> number.append(token)
        token.all { it.isDigit() } -> number.append(token)
        DIGIT_WORDS.containsKey(token) -> number.append(DIGIT_WORDS.getValue(token))
        SPANISH_GROUPS.containsKey(token) -> {
          var group = SPANISH_GROUPS.getValue(token)
          if (group >= 30 && group % 10 == 0 && tokens.getOrNull(index + 1) == "y") {
            val unit = tokens.getOrNull(index + 2)?.let { DIGIT_WORDS[it]?.digitToInt() }
            if (unit == null || unit == 0) return null
            group += unit
            index += 2
          }
          number.append(group)
        }
        else -> return null
      }
      index++
    }
    // "oh oh oh" or "to for ate" are more likely a name than a number.
    if (tokens.all { it in AMBIGUOUS_DIGIT_WORDS }) return null
    return number.toString().takeIf { it.count { c -> c.isDigit() } >= 3 }
  }

  @JvmStatic
  fun isYes(normalized: String): Boolean {
    val text = withoutPlease(normalized)
    if (text in YES) return true
    val words = text.split(' ')
    return words.first() in YES_STARTS && words.drop(1).all { it in YES_FILLER }
  }

  /** True for an explicit refusal; anything else that is not a yes is left for other commands. */
  @JvmStatic fun isNo(normalized: String): Boolean = withoutPlease(normalized) in NO

  private fun withoutPlease(text: String): String =
    text.removeSuffix(" please").removeSuffix(" por favor").removeSuffix(" gracias").trim()

  /** Which of the [count] offered options the user picked, counting from 1, or null. */
  @JvmStatic
  fun parseChoice(normalized: String, count: Int): Int? {
    val word =
      withoutPlease(normalized).removePrefix("la ").removePrefix("el ")
        .removePrefix("number ").removePrefix("option ")
        .removePrefix("número ").removePrefix("numero ").removePrefix("opción ")
        .removePrefix("opcion ").removePrefix("la ").removePrefix("el ").trim()
    val number = word.toIntOrNull() ?: CHOICE_WORDS[word] ?: return null
    return number.takeIf { it in 1..count }
  }
}

/** A phone contact with one number to dial. */
data class CallContact(val id: Long, val name: String, val number: String)

/** Finds the contacts a spoken name could mean. Pure, so it can be unit tested. */
object CallContactMatcher {
  private const val MIN_SCORE = 0.78
  const val MAX_OPTIONS = 5

  /**
   * Returns the best matching contacts, one per person. One result means the name is clear; more
   * than one means the user has to choose. Callers should list [contacts] with each person's
   * preferred number first.
   */
  @JvmStatic
  fun match(spoken: String, contacts: List<CallContact>): List<CallContact> {
    val query = nameKey(spoken)
    if (query.isEmpty()) return emptyList()
    val scored =
      contacts
        .map { it to score(query, nameKey(it.name)) }
        .filter { it.second >= MIN_SCORE }
    val best = scored.maxOfOrNull { it.second } ?: return emptyList()
    return scored
      .filter { it.second >= best - 0.001 }
      .map { it.first }
      .distinctBy { it.id }
      .take(MAX_OPTIONS)
  }

  // Speech recognition may omit acute accents. Keep ñ distinct from n.
  private fun nameKey(text: String): String = VoiceCommandFuzzyMatcher.normalize(text)
    .replace('á', 'a').replace('é', 'e').replace('í', 'i')
    .replace('ó', 'o').replace('ú', 'u').replace('ü', 'u')

  private fun score(query: String, name: String): Double {
    if (name.isEmpty()) return 0.0
    if (name == query) return 1.0
    val nameWords = name.split(" ")
    if (query.split(" ").all { it in nameWords }) return 0.95
    if (nameWords.any { it.startsWith(query) }) return 0.9
    return maxOf(
      VoiceCommandFuzzyMatcher.similarity(query, name),
      nameWords.maxOf { VoiceCommandFuzzyMatcher.similarity(query, it) } * 0.95)
  }
}
