/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.utils.output;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.telephony.TelephonyManager;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.libraries.accessibility.utils.log.LogUtils;

/** Adjusts reader audio gain. Does not change any system stream, mode or audio route. */
public final class CallSpeechVolumeController {
  public static final String ENABLED_KEY = "pref_call_speech_volume_enabled";
  public static final String PERCENT_KEY = "pref_call_speech_volume_percent";
  public static final int DEFAULT_PERCENT = 50;
  public static final int MIN_PERCENT = 5;
  public static final int MAX_PERCENT = 100;

  public static int clampPercent(int value) {
    return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, value));
  }

  private static final String TAG = "CallSpeechVolume";
  private final Context context;
  private final AudioManager audioManager;
  private final SharedPreferences prefs;
  private final CallSpeechVolume compensation = new CallSpeechVolume();
  private volatile boolean enabled;
  private volatile int percent;
  private Object modeListener;
  private int previousMode = AudioManager.MODE_NORMAL;
  private float previousGain = Float.NaN;

  private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
      (preferences, key) -> {
        if (key == null || ENABLED_KEY.equals(key) || PERCENT_KEY.equals(key)) {
          readSettings();
        }
      };

  private final BroadcastReceiver phoneStateReceiver = new BroadcastReceiver() {
    @Override public void onReceive(Context receiverContext, Intent intent) {
      if (TelephonyManager.EXTRA_STATE_IDLE.equals(
          intent.getStringExtra(TelephonyManager.EXTRA_STATE))) {
        compensation.reset();
      }
    }
  };

  public CallSpeechVolumeController(Context context) {
    this.context = context;
    audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    readSettings();
    prefs.registerOnSharedPreferenceChangeListener(preferenceListener);
    ContextCompat.registerReceiver(context, phoneStateReceiver,
        new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
        ContextCompat.RECEIVER_EXPORTED);
    if (audioManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      modeListener = registerModeListener();
    }
  }

  private void readSettings() {
    boolean newEnabled = prefs.getBoolean(ENABLED_KEY, false);
    if (newEnabled != enabled) {
      compensation.reset();
    }
    enabled = newEnabled;
    percent = clampPercent(prefs.getInt(PERCENT_KEY, DEFAULT_PERCENT));
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private Object registerModeListener() {
    AudioManager.OnModeChangedListener listener = mode -> compensation.reset();
    audioManager.addOnModeChangedListener(context.getMainExecutor(), listener);
    return listener;
  }

  /** Recomputed for each utterance or sound, independently of the playback backend. */
  public float adjust(float normalGain, int speechStream) {
    if (!enabled || audioManager == null) {
      compensation.reset();
      return normalGain;
    }
    try {
      int mode = audioManager.getMode();
      if (mode != previousMode) {
        compensation.reset();
        previousMode = mode;
      }
      boolean inCall = mode == AudioManager.MODE_IN_CALL
          || mode == AudioManager.MODE_IN_COMMUNICATION;
      if (!inCall) {
        compensation.reset();
        previousGain = Float.NaN;
        return normalGain;
      }
      AudioDeviceInfo device = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
          ? audioManager.getCommunicationDevice() : null;
      int deviceType = device != null ? device.getType() : legacyDeviceType();
      String route = mode + ":" + speechStream + ":" + deviceType + ":"
          + (device != null ? device.getId() : 0);
      int callIndex = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
      float callDb = readCallVolumeDb(callIndex, deviceType);
      float gain = compensation.adjust(true, true, route, callDb, normalGain, percent);
      if (gain != previousGain) {
        LogUtils.i(TAG, "Call voice: index=%d, dB=%.2f, gain=%.3f", callIndex, callDb, gain);
        previousGain = gain;
      }
      return gain;
    } catch (RuntimeException e) {
      // A manufacturer's audio implementation must never stop the reader from speaking.
      compensation.reset();
      LogUtils.w(TAG, "Cannot read call audio state: %s", e);
      return normalGain;
    }
  }

  private float readCallVolumeDb(int index, int deviceType) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      // These versions have no public volume curve. The separate call gain still works.
      return Float.NaN;
    }
    try {
      return audioManager.getStreamVolumeDb(AudioManager.STREAM_VOICE_CALL, index, deviceType);
    } catch (RuntimeException e) {
      LogUtils.w(TAG, "Cannot read call volume curve: %s", e);
      return Float.NaN;
    }
  }

  @SuppressWarnings("deprecation")
  private int legacyDeviceType() {
    if (audioManager.isBluetoothScoOn()) {
      return AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
    }
    if (audioManager.isSpeakerphoneOn()) {
      return AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
    }
    for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
      int type = device.getType();
      if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET
          || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
          || type == AudioDeviceInfo.TYPE_USB_HEADSET) {
        return type;
      }
    }
    return AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
  }

  public void shutdown() {
    prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener);
    context.unregisterReceiver(phoneStateReceiver);
    if (modeListener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      audioManager.removeOnModeChangedListener((AudioManager.OnModeChangedListener) modeListener);
    }
    compensation.reset();
  }
}
