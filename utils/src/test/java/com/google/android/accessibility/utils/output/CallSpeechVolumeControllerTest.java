/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.utils.output;

import static org.junit.Assert.assertEquals;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.telephony.TelephonyManager;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CallSpeechVolumeControllerTest {
  private Context context;
  private AudioManager audio;
  private SharedPreferences prefs;
  private CallSpeechVolumeController controller;

  @Before public void setUp() {
    context = RuntimeEnvironment.getApplication();
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    prefs.edit().clear().commit();
    audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    controller = new CallSpeechVolumeController(context);
  }

  @After public void tearDown() {
    controller.shutdown();
  }

  private float gain() {
    return controller.adjust(0.8f, AudioManager.STREAM_ACCESSIBILITY);
  }

  @Test public void defaultsOff_preservesExistingBehaviorDuringCall() {
    audio.setMode(AudioManager.MODE_IN_CALL);
    assertEquals(0.8f, gain(), 0f);
  }

  @Test public void enablingWhileRunning_appliesToNextPhrase() {
    audio.setMode(AudioManager.MODE_IN_CALL);
    prefs.edit().putBoolean(CallSpeechVolumeController.ENABLED_KEY, true).commit();
    assertEquals(0.2f, gain(), 0.00001f);
  }

  @Test public void voipMode_usesCallGainToo() {
    prefs.edit().putBoolean(CallSpeechVolumeController.ENABLED_KEY, true).commit();
    audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
    assertEquals(0.2f, gain(), 0.00001f);
  }

  @Test public void ringing_doesNotReduceVoice() {
    prefs.edit().putBoolean(CallSpeechVolumeController.ENABLED_KEY, true).commit();
    audio.setMode(AudioManager.MODE_RINGTONE);
    assertEquals(0.8f, gain(), 0f);
  }

  @Test public void callEnds_normalGainReturnsWithoutRestart() {
    prefs.edit().putBoolean(CallSpeechVolumeController.ENABLED_KEY, true).commit();
    audio.setMode(AudioManager.MODE_IN_CALL);
    gain();
    audio.setMode(AudioManager.MODE_NORMAL);
    context.sendBroadcast(new Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        .putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_IDLE));
    assertEquals(0.8f, gain(), 0f);
  }

  @Test public void percentageChange_appliesWithoutRestart() {
    prefs.edit().putBoolean(CallSpeechVolumeController.ENABLED_KEY, true).commit();
    audio.setMode(AudioManager.MODE_IN_CALL);
    gain();
    prefs.edit().putInt(CallSpeechVolumeController.PERCENT_KEY, 75).commit();
    assertEquals(0.45f, gain(), 0.00001f);
  }

  @Test public void disablingDuringCall_restoresNormalGain() {
    prefs.edit().putBoolean(CallSpeechVolumeController.ENABLED_KEY, true).commit();
    audio.setMode(AudioManager.MODE_IN_CALL);
    gain();
    prefs.edit().putBoolean(CallSpeechVolumeController.ENABLED_KEY, false).commit();
    assertEquals(0.8f, gain(), 0f);
  }

  @Test public void belowTwentyFive_appliesSavedGainAndRestoresUsualVolumeAfterCall() {
    prefs.edit().putBoolean(CallSpeechVolumeController.ENABLED_KEY, true).commit();
    audio.setMode(AudioManager.MODE_IN_CALL);
    for (int percent : new int[] {20, 15, 10, 5}) {
      prefs.edit().putInt(CallSpeechVolumeController.PERCENT_KEY, percent).commit();
      assertEquals(0.8f * (percent / 100f) * (percent / 100f), gain(), 0.00001f);
    }
    audio.setMode(AudioManager.MODE_NORMAL);
    assertEquals(0.8f, gain(), 0f);
  }

  @Test @Config(sdk = 26)
  public void androidEight_withoutVolumeCurveUsesCallGain() {
    prefs.edit().putBoolean(CallSpeechVolumeController.ENABLED_KEY, true).commit();
    audio.setMode(AudioManager.MODE_IN_CALL);
    assertEquals(0.2f, gain(), 0.00001f);
  }
}
