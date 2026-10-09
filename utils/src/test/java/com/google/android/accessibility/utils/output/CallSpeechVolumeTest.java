/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.utils.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CallSpeechVolumeTest {
  private final CallSpeechVolume volume = new CallSpeechVolume();
  private static final float EPSILON = 0.00001f;

  private float call(String route, float db) {
    return volume.adjust(true, true, route, db, 1f, 50);
  }

  @Test public void disabled_preservesNormalVolumeExactly() {
    assertEquals(0.37f, volume.adjust(false, true, "speaker", -20f, 0.37f, 50), 0f);
  }

  @Test public void outsideCall_preservesNormalVolumeExactly() {
    assertEquals(0.83f, volume.adjust(true, false, "speaker", -20f, 0.83f, 50), 0f);
  }

  @Test public void firstPhrase_atFiftyReservesQuarterOfNormalVoiceGain() {
    assertEquals(0.15f, volume.adjust(true, true, "speaker", -20f, 0.6f, 50), EPSILON);
  }

  @Test public void louderCall_keepsCombinedOutputStable() {
    float initialOutput = call("speaker", -20f) * amplitude(-20f);
    float laterOutput = call("speaker", -14f) * amplitude(-14f);
    assertEquals(initialOutput, laterOutput, EPSILON);
  }

  @Test public void quieterCall_keepsCombinedOutputStableWithinHeadroom() {
    float initialOutput = call("speaker", -20f) * amplitude(-20f);
    float laterOutput = call("speaker", -26f) * amplitude(-26f);
    assertEquals(initialOutput, laterOutput, EPSILON);
  }

  @Test public void quieterCallPastHeadroom_neverAmplifiesBeyondOne() {
    call("speaker", -20f);
    assertEquals(1f, call("speaker", -40f), 0f);
  }

  @Test public void returningToInitialCallVolume_restoresChosenGain() {
    call("speaker", -20f);
    call("speaker", -14f);
    assertEquals(0.25f, call("speaker", -20f), EPSILON);
  }

  @Test public void changingVoicePercentage_doesNotRecalibrateCallReference() {
    call("speaker", -20f);
    float compensated = call("speaker", -14f);
    assertEquals(compensated * 2.25f,
        volume.adjust(true, true, "speaker", -14f, 1f, 75), EPSILON);
  }

  @Test public void changingUsualVoiceGain_changesReaderIndependently() {
    call("speaker", -20f);
    float compensated = call("speaker", -14f);
    assertEquals(compensated * 0.8f,
        volume.adjust(true, true, "speaker", -14f, 0.8f, 50), EPSILON);
  }

  @Test public void routeChange_usesFreshReference() {
    call("speaker", -20f);
    call("speaker", -14f);
    assertEquals(0.25f, call("earpiece", -30f), EPSILON);
  }

  @Test public void nextCall_usesFreshReference() {
    call("speaker", -20f);
    volume.adjust(true, false, "speaker", -20f, 1f, 50);
    assertEquals(0.25f, call("speaker", -14f), EPSILON);
  }

  @Test public void modeCallbackReset_handlesCallsWithNoSpeechBetweenThem() {
    call("speaker", -20f);
    volume.reset();
    assertEquals(0.25f, call("speaker", -14f), EPSILON);
  }

  @Test public void reenablingDuringCall_usesFreshReference() {
    call("speaker", -20f);
    volume.adjust(false, true, "speaker", -14f, 1f, 50);
    assertEquals(0.25f, call("speaker", -14f), EPSILON);
  }

  @Test public void unavailableCurve_stillUsesSeparateCallGain() {
    assertEquals(0.25f, call("speaker", Float.NaN), EPSILON);
  }

  @Test public void mutedCurve_doesNotMuteReader() {
    assertEquals(0.25f, call("speaker", Float.NEGATIVE_INFINITY), EPSILON);
  }

  @Test public void invalidCurveClearsReference_recoveryDoesNotCauseVolumeJump() {
    call("speaker", -20f);
    call("speaker", Float.NaN);
    assertEquals(0.25f, call("speaker", -14f), EPSILON);
  }

  @Test public void percentage_isLimitedToSupportedRange() {
    assertEquals(0.0025f, volume.adjust(true, true, "speaker", -20f, 1f, -5), EPSILON);
    assertEquals(1f, volume.adjust(true, true, "speaker", -20f, 1f, 200), EPSILON);
  }

  @Test public void belowTwentyFive_reducesVoiceWhilePreservingCompensation() {
    for (int percent : new int[] {20, 15, 10, 5}) {
      volume.reset();
      float initialOutput = volume.adjust(true, true, "speaker", -20f, 1f, percent) * amplitude(-20f);
      float laterOutput = volume.adjust(true, true, "speaker", -14f, 1f, percent) * amplitude(-14f);
      assertTrue(initialOutput > 0f);
      assertEquals(initialOutput, laterOutput, EPSILON);
    }
  }

  @Test public void nominalPercentages_applyStrongerAttenuationWithFullGainAtOneHundred() {
    int[] percentages = {100, 95, 50, 45, 25, 20, 5};
    float[] expectedGains = {1f, 0.9025f, 0.25f, 0.2025f, 0.0625f, 0.04f, 0.0025f};
    for (int i = 0; i < percentages.length; i++) {
      assertEquals(expectedGains[i],
          volume.adjust(true, true, "speaker", -20f, 1f, percentages[i]), EPSILON);
    }
  }

  @Test public void everyFivePointStep_hasLargerRelativeEffectThanLinearControl() {
    for (int percent = 100; percent >= 10; percent -= 5) {
      float before = volume.adjust(true, true, "speaker", -20f, 1f, percent);
      float after = volume.adjust(true, true, "speaker", -20f, 1f, percent - 5);
      assertTrue(Float.isFinite(after) && after > 0f && after < before);
      assertTrue(after / before < (percent - 5) / (float) percent);
    }
  }

  @Test public void silentSpeech_staysSilent() {
    call("speaker", -20f);
    assertEquals(0f, volume.adjust(true, true, "speaker", -26f, 0f, 50), 0f);
  }

  private static float amplitude(float db) {
    return (float) Math.pow(10.0, db / 20.0);
  }
}
