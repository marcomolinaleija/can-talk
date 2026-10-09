/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.utils.output;

/** Per-call gain, with an experimental inverse of the call output's volume curve. */
final class CallSpeechVolume {
  private String referenceRoute;
  private float referenceDb = Float.NaN;

  synchronized void reset() {
    referenceRoute = null;
    referenceDb = Float.NaN;
  }

  synchronized float adjust(
      boolean enabled, boolean inCall, String route, float callDb, float normalGain, int percent) {
    if (!enabled || !inCall) {
      reset();
      return normalGain;
    }

    // Keep headroom for a drop in call volume. Never amplify past the TTS API's maximum of 1.
    float baseGain = normalGain * Math.max(25, Math.min(100, percent)) / 100f;
    if (!Float.isFinite(callDb)) {
      reset();
      return Math.max(0f, Math.min(1f, baseGain));
    }
    if (!route.equals(referenceRoute) || !Float.isFinite(referenceDb)) {
      referenceRoute = route;
      referenceDb = callDb;
    }
    double correction = Math.pow(10.0, (referenceDb - callDb) / 20.0);
    return (float) Math.max(0.0, Math.min(1.0, baseGain * correction));
  }
}
