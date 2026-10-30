/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.brailleime

/** Preloads a bounded set of keyboard samples. Only an explicit preview may wait for decoding. */
internal class TypingSoundBoost<T : Any>(
  private val load: (String) -> T?,
  private val playSample: (T, Float) -> Boolean,
  private val background: (() -> Unit) -> Unit,
  private val main: (() -> Unit) -> Unit,
) {
  private val lock = Any()
  private var generation = 0
  private var paths = emptySet<String>()
  private val samples = mutableMapOf<String, T>()
  private val failed = mutableSetOf<String>()
  private var pendingPreview: Pair<String, () -> Unit>? = null

  fun prepare(selectedPaths: Set<String>) {
    synchronized(lock) {
      if (paths == selectedPaths) return
      clear()
      paths = selectedPaths.toSet()
      val token = generation
      for (path in paths) background {
        if (synchronized(lock) { token != generation }) return@background
        val sample = runCatching { load(path) }.getOrNull()
        main {
          val preview = synchronized(lock) {
            if (token != generation) return@main
            if (sample == null) failed.add(path) else samples[path] = sample
            pendingPreview?.takeIf { it.first == path }?.second.also {
              if (it != null) pendingPreview = null
            }
          }
          // Re-read the current volume/enabled state, and use normal playback if decoding failed.
          preview?.invoke()
        }
      }
    }
  }

  /** True also consumes a key during loading, so typing feedback is never replayed late. */
  fun play(path: String, gain: Float, preview: (() -> Unit)? = null): Boolean {
    synchronized(lock) {
      if (!gain.isFinite() || gain <= 1f || path !in paths || path in failed) return false
      val sample = samples[path]
      if (sample != null) return playSample(sample, gain.coerceAtMost(2f))
      if (preview != null) pendingPreview = path to preview
      return true
    }
  }

  fun clear() {
    synchronized(lock) {
      generation++
      paths = emptySet()
      samples.clear()
      failed.clear()
      pendingPreview = null
    }
  }

  /** Closing the UI cancels its preview while retaining preloaded samples for the next key. */
  fun cancelPreview() = synchronized(lock) { pendingPreview = null }
}
