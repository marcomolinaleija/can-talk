/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.eventprocessor;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.view.KeyEvent;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.output.CallSpeechVolumeController;
import java.util.function.IntConsumer;

/** Changes the separate call speech gain without changing any system volume stream. */
public final class CallSpeechVolumeKeyHandler {
  private static final int STEP = 5;
  private final SharedPreferences prefs;
  private final AudioManager audioManager;
  private final IntConsumer announcePercent;
  private final boolean[] captured = new boolean[2];

  public CallSpeechVolumeKeyHandler(Context context, IntConsumer announcePercent) {
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    this.announcePercent = announcePercent;
  }

  public boolean hasCapturedKey(KeyEvent event) {
    int index = keyIndex(event.getKeyCode());
    return index >= 0 && captured[index];
  }

  /** Ownership is decided on the first down and retained until up, even after lifting the finger. */
  public boolean onKeyEvent(KeyEvent event, boolean readerActive, boolean touchingScreen) {
    int index = keyIndex(event.getKeyCode());
    if (index < 0) {
      return false;
    }
    if (event.getAction() == KeyEvent.ACTION_UP) {
      boolean handled = captured[index];
      captured[index] = false;
      return handled;
    }
    if (event.getAction() != KeyEvent.ACTION_DOWN) {
      return false;
    }
    boolean eligible = readerActive && touchingScreen && isEnabledDuringCall();
    if (event.getRepeatCount() == 0) {
      captured[index] = eligible;
    }
    if (!captured[index]) {
      // A repeat must never steal a key that started as a system-volume press.
      return false;
    }
    if (eligible && !event.isCanceled()) {
      int oldPercent = CallSpeechVolumeController.clampPercent(
          prefs.getInt(CallSpeechVolumeController.PERCENT_KEY,
              CallSpeechVolumeController.DEFAULT_PERCENT));
      int percent = CallSpeechVolumeController.clampPercent(oldPercent + (index == 0 ? -STEP : STEP));
      prefs.edit().putInt(CallSpeechVolumeController.PERCENT_KEY, percent).apply();
      if (percent != oldPercent || event.getRepeatCount() == 0) {
        announcePercent.accept(percent);
      }
    }
    return true;
  }

  private boolean isEnabledDuringCall() {
    if (audioManager == null
        || !prefs.getBoolean(CallSpeechVolumeController.ENABLED_KEY, false)) {
      return false;
    }
    try {
      int mode = audioManager.getMode();
      return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static int keyIndex(int keyCode) {
    return switch (keyCode) {
      case KeyEvent.KEYCODE_VOLUME_DOWN -> 0;
      case KeyEvent.KEYCODE_VOLUME_UP -> 1;
      default -> -1;
    };
  }
}
