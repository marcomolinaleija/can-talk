/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.translation

/** Memory only: private speech is never written to a file or Android backup. */
class TranslationCache(
  private val maxEntries: Int = 500,
  private val maxCharacters: Int = 512_000,
  private val ttlMillis: Long = 24 * 60 * 60 * 1000L,
  private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
  data class Key(val provider: String, val scope: String, val request: TranslationRequest)
  private data class Entry(val result: TranslationResult, val time: Long, val size: Int)
  private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
  private var characters = 0
  @Volatile var generation = 0L
    private set

  @Synchronized fun get(key: Key): TranslationResult? {
    val entry = entries[key] ?: return null
    if (clock() - entry.time >= ttlMillis) {
      characters -= entry.size
      entries.remove(key)
      return null
    }
    return entry.result
  }

  @Synchronized fun put(key: Key, result: TranslationResult, expectedGeneration: Long) {
    if (generation != expectedGeneration || result.text.isBlank()) return
    val size = key.request.text.length + result.text.length
    if (size > maxCharacters) return
    entries.remove(key)?.let { characters -= it.size }
    entries[key] = Entry(result, clock(), size)
    characters += size
    while (entries.size > maxEntries || characters > maxCharacters) {
      val first = entries.entries.iterator()
      characters -= first.next().value.size
      first.remove()
    }
  }

  @Synchronized fun clear(scope: String? = null) {
    generation++ // Old in-flight requests cannot repopulate a cleared cache.
    val iterator = entries.entries.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      if (scope == null || entry.key.scope == scope) {
        characters -= entry.value.size
        iterator.remove()
      }
    }
  }
  @Synchronized fun size(): Int = entries.size
}
