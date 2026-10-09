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

import org.junit.Assert.*
import org.junit.Test

class TranslationCacheTest {
  private fun key(text: String = "hello", app: String = "app", provider: String = "p", source: String = "auto", target: String = "es") =
    TranslationCache.Key(provider, app, TranslationRequest(text, source, target))
  @Test fun isolatesAppProviderSourceTargetAndExactText() {
    val cache = TranslationCache()
    cache.put(key(), TranslationResult("hola"), cache.generation)
    assertEquals("hola", cache.get(key())!!.text)
    for (other in listOf(key(app="other"), key(provider="other"), key(source="en"), key(target="fr"), key(text="Hello"), key(text="hello "))) {
      assertNull(cache.get(other))
    }
  }
  @Test fun lruEvictsLeastRecentlyUsed() {
    val cache = TranslationCache(maxEntries=2)
    for (s in listOf("a", "b")) cache.put(key(s), TranslationResult(s), cache.generation)
    cache.get(key("a"))
    cache.put(key("c"), TranslationResult("c"), cache.generation)
    assertNull(cache.get(key("b")))
    assertNotNull(cache.get(key("a")))
    assertEquals(2, cache.size())
  }
  @Test fun expiresUsingMonotonicClock() {
    var now = 0L
    val cache = TranslationCache(ttlMillis=10, clock={now})
    cache.put(key(), TranslationResult("hola"), cache.generation)
    now = 10
    assertNull(cache.get(key()))
    assertEquals(0, cache.size())
  }
  @Test fun boundsCharactersAndDoesNotStoreEmptyResults() {
    val cache = TranslationCache(maxCharacters=10)
    cache.put(key("abcdef"), TranslationResult("abcdef"), cache.generation)
    cache.put(key("a"), TranslationResult(""), cache.generation)
    assertEquals(0, cache.size())
    cache.put(key("a"), TranslationResult("a"), cache.generation)
    assertEquals(1, cache.size())
  }
  @Test fun clearingOneAppPreservesOthersAndInvalidatesOldWork() {
    val cache = TranslationCache()
    val generation = cache.generation
    cache.put(key(app="a"), TranslationResult("hola"), generation)
    cache.put(key(app="b"), TranslationResult("hola"), generation)
    cache.clear("a")
    cache.put(key(app="a"), TranslationResult("late"), generation)
    assertNull(cache.get(key(app="a")))
    assertNotNull(cache.get(key(app="b")))
    cache.clear()
    assertEquals(0, cache.size())
  }
}
