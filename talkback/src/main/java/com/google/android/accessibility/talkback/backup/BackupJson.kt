/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.backup

import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** A bounded strict parser: org.json alone accepts duplicate names, coercions and non-JSON syntax. */
internal object BackupJson {
  const val MAX_BYTES = 128 * 1024 * 1024
  const val MAX_ASSET_BYTES = 64 * 1024 * 1024
  const val MAX_FILE_BYTES = 5 * 1024 * 1024
  const val MAX_ITEMS = 10_000
  // A 100-entry clipboard can contain 2 million characters, plus JSON escapes, inside one string.
  const val MAX_STRING = 16 * 1024 * 1024
  private const val MAX_JSON_STRING = MAX_BYTES // Encrypted envelope; inner fields have tighter limits.

  fun read(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      total += count
      if (total > MAX_BYTES) throw BackupException(BackupFailure.TOO_LARGE)
      checkMemoryBudget(total.toLong())
      output.write(buffer, 0, count)
    }
    return decode(output.toByteArray())
  }

  fun decode(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
  } catch (_: java.nio.charset.CharacterCodingException) {
    throw BackupException(BackupFailure.INVALID_FORMAT)
  }

  fun parse(json: String): JSONObject {
    if (json.length > MAX_BYTES) throw BackupException(BackupFailure.TOO_LARGE)
    var nodes = 0
    fun readValue(reader: JsonReader, depth: Int): Any {
      if (depth > 24 || ++nodes > 100_000) throw BackupException(BackupFailure.TOO_LARGE)
      return when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
          val result = JSONObject()
          reader.beginObject()
          while (reader.hasNext()) {
            val name = reader.nextName()
            if (name.length > 1024 || result.has(name)) throw BackupException(BackupFailure.INVALID_FORMAT)
            result.put(name, readValue(reader, depth + 1))
          }
          reader.endObject()
          result
        }
        JsonToken.BEGIN_ARRAY -> {
          val result = JSONArray()
          reader.beginArray()
          while (reader.hasNext()) result.put(readValue(reader, depth + 1))
          reader.endArray()
          result
        }
        JsonToken.STRING -> reader.nextString().also {
          if (it.length > MAX_JSON_STRING) throw BackupException(BackupFailure.TOO_LARGE)
        }
        JsonToken.NUMBER -> {
          val number = reader.nextString()
          if (number.length > 64) throw BackupException(BackupFailure.INVALID_FORMAT)
          BigDecimal(number).also {
            if (kotlin.math.abs(it.scale().toLong()) > 64 || it.precision() > 64)
              throw BackupException(BackupFailure.INVALID_FORMAT)
          }
        }
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
        else -> throw BackupException(BackupFailure.INVALID_FORMAT)
      }
    }
    try {
      JsonReader(StringReader(json)).use { reader ->
        reader.isLenient = false
        val root = readValue(reader, 0) as? JSONObject
          ?: throw BackupException(BackupFailure.INVALID_FORMAT)
        if (reader.peek() != JsonToken.END_DOCUMENT) throw BackupException(BackupFailure.INVALID_FORMAT)
        return root
      }
    } catch (e: BackupException) {
      throw e
    } catch (_: Exception) {
      throw BackupException(BackupFailure.INVALID_FORMAT)
    }
  }

  fun checkMemoryBudget(bytes: Long) {
    val runtime = Runtime.getRuntime()
    val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
    // Parsing, checksumming and optional encryption need temporary copies. Refuse, never truncate.
    if (bytes * 6 > available) throw BackupException(BackupFailure.TOO_LARGE)
  }

  /** Sorted JSON with normalized numbers covers identities/metadata, not just values. */
  fun canonical(value: Any): String = when (value) {
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
      JSONObject.quote(it) + ":" + canonical(value.get(it))
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    is Boolean -> value.toString()
    JSONObject.NULL -> "null"
    else -> throw BackupException(BackupFailure.INVALID_FORMAT)
  }

  fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

  fun string(obj: JSONObject, name: String, max: Int = MAX_STRING): String =
    (obj.opt(name) as? String)?.takeIf { it.length <= max }
      ?: throw BackupException(BackupFailure.INVALID_FORMAT)

  fun integer(value: Any?): Long = try {
    if (value !is Number) throw BackupException(BackupFailure.INVALID_FORMAT)
    BigDecimal(value.toString()).longValueExact()
  } catch (_: ArithmeticException) {
    throw BackupException(BackupFailure.INVALID_FORMAT)
  } catch (_: NumberFormatException) {
    throw BackupException(BackupFailure.INVALID_FORMAT)
  }

  fun fields(obj: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
    val names = obj.keys().asSequence().toSet()
    if (!names.containsAll(required) || (names - required - optional).isNotEmpty())
      throw BackupException(BackupFailure.INVALID_FORMAT)
  }
}
