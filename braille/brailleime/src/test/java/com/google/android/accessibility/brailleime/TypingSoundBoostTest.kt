/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.brailleime

import org.junit.Assert.*
import org.junit.Test

class TypingSoundBoostTest {
  private class Fixture {
    val background = ArrayDeque<() -> Unit>()
    val main = ArrayDeque<() -> Unit>()
    val loaded = mutableListOf<String>()
    val played = mutableListOf<Pair<String, Float>>()
    var decodeWorks = true
    var playerWorks = true
    val boost = TypingSoundBoost(
      load = { path: String -> loaded.add(path); if (decodeWorks) path else null },
      playSample = { sample: String, gain: Float -> played.add(sample to gain); playerWorks },
      background = { background.addLast(it) },
      main = { main.addLast(it) },
    )
    fun finishLoading() {
      while (background.isNotEmpty()) background.removeFirst().invoke()
      while (main.isNotEmpty()) main.removeFirst().invoke()
    }
  }

  @Test fun manualVolume_passesGainBeyondUnityToPcmPlaybackAndReusesTheLoadedSample() {
    val f = Fixture()
    f.boost.prepare(setOf("typing.ogg"))
    f.finishLoading()
    assertTrue(f.boost.play("typing.ogg", 1.5f))
    assertTrue(f.boost.play("typing.ogg", 2f))
    f.boost.prepare(setOf("typing.ogg"))
    f.finishLoading()
    assertEquals(listOf("typing.ogg"), f.loaded)
    assertEquals(listOf("typing.ogg" to 1.5f, "typing.ogg" to 2f), f.played)
  }

  @Test fun normalTyping_isSkippedWhileLoadingAndNeverPlaysLate() {
    val f = Fixture()
    f.boost.prepare(setOf("typing.ogg"))
    assertTrue(f.boost.play("typing.ogg", 2f))
    f.finishLoading()
    assertTrue(f.played.isEmpty())
    assertTrue(f.boost.play("typing.ogg", 2f))
    assertEquals(1, f.played.size)
  }

  @Test fun preview_waitsForLoadingAndReadsTheLatestVolume() {
    val f = Fixture()
    var gain = 2f
    f.boost.prepare(setOf("typing.ogg"))
    assertTrue(f.boost.play("typing.ogg", gain) { f.boost.play("typing.ogg", gain) })
    gain = 1.5f
    f.finishLoading()
    assertEquals(listOf("typing.ogg" to 1.5f), f.played)
  }

  @Test fun turningSoundsOff_cancelsAnAlreadyDecodedPendingPreview() {
    val f = Fixture()
    f.boost.prepare(setOf("typing.ogg"))
    f.boost.play("typing.ogg", 2f) { fail("Obsolete preview must not play") }
    f.background.removeFirst().invoke()
    f.boost.clear()
    f.finishLoading()
    assertTrue(f.played.isEmpty())
    assertFalse(f.boost.play("typing.ogg", 2f))
  }

  @Test fun changingProfileOrTheme_cancelsOldWorkAndLoadsOnlyTheNewPaths() {
    val f = Fixture()
    f.boost.prepare(setOf("old.ogg"))
    f.boost.play("old.ogg", 2f) { fail("Previous theme must not play") }
    f.boost.prepare(setOf("new.ogg"))
    f.finishLoading()
    assertEquals(listOf("new.ogg"), f.loaded)
    assertFalse(f.boost.play("old.ogg", 2f))
    assertTrue(f.boost.play("new.ogg", 2f))
  }

  @Test fun failedDecode_fallsBackAndIsNotRetriedOnEveryKey() {
    val f = Fixture()
    f.decodeWorks = false
    f.boost.prepare(setOf("broken.ogg"))
    var fallback = false
    f.boost.play("broken.ogg", 2f) { fallback = !f.boost.play("broken.ogg", 2f) }
    f.finishLoading()
    assertTrue(fallback)
    assertFalse(f.boost.play("broken.ogg", 2f))
    f.boost.prepare(setOf("broken.ogg"))
    f.finishLoading()
    assertEquals(listOf("broken.ogg"), f.loaded)
  }

  @Test fun failedAudioPlayer_allowsTheNormalPlayerToHandleTheKey() {
    val f = Fixture()
    f.boost.prepare(setOf("typing.ogg"))
    f.finishLoading()
    f.playerWorks = false
    assertFalse(f.boost.play("typing.ogg", 2f))
  }

  @Test fun gain_isBoundedAndNormalOrInvalidVolumesDoNotUseAmplification() {
    val f = Fixture()
    f.boost.prepare(setOf("typing.ogg"))
    f.finishLoading()
    for (gain in listOf(-1f, 0f, 0.5f, 1f, Float.NaN, Float.POSITIVE_INFINITY))
      assertFalse(f.boost.play("typing.ogg", gain))
    assertFalse(f.boost.play("missing.ogg", 2f))
    assertTrue(f.boost.play("typing.ogg", 50f))
    assertEquals(listOf("typing.ogg" to 2f), f.played)
  }

  @Test fun repeatedPreviewRequests_onlyReplayTheLastRequestedKey() {
    val f = Fixture()
    f.boost.prepare(setOf("typing.ogg", "delete.ogg"))
    f.boost.play("typing.ogg", 2f) { fail("Superseded preview must not play") }
    f.boost.play("delete.ogg", 1.5f) { f.boost.play("delete.ogg", 1.5f) }
    f.finishLoading()
    assertEquals(listOf("delete.ogg" to 1.5f), f.played)
  }

  @Test fun leavingTheUi_cancelsItsPreviewAndKeepsTheSampleReadyForTyping() {
    val f = Fixture()
    f.boost.prepare(setOf("typing.ogg"))
    f.boost.play("typing.ogg", 2f) { fail("Hidden preview must not play") }
    f.boost.cancelPreview()
    f.finishLoading()
    assertTrue(f.played.isEmpty())
    assertTrue(f.boost.play("typing.ogg", 2f))
    assertEquals(listOf("typing.ogg"), f.loaded)
    assertEquals(listOf("typing.ogg" to 2f), f.played)
  }
}
