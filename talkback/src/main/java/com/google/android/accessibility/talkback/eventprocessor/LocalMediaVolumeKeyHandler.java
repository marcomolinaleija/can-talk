/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.eventprocessor;

import android.content.Context;
import android.media.AudioManager;
import android.view.KeyEvent;
import com.google.android.accessibility.utils.compat.media.AudioManagerCompatUtils;

/** Keeps ordinary volume presses on locally playing media instead of speech or a remote session. */
public final class LocalMediaVolumeKeyHandler {
  private final AudioManager audioManager;
  private final boolean[] captured = new boolean[2];

  public LocalMediaVolumeKeyHandler(Context context) {
    audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
  }

  public boolean hasCapturedKey(KeyEvent event) {
    int index = keyIndex(event.getKeyCode());
    return index >= 0 && captured[index];
  }

  /** Decides on the first down and consumes the entire press, including its release. */
  public boolean onKeyEvent(KeyEvent event, boolean eligibleReader, boolean touchingScreen) {
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
    boolean eligible = eligibleReader && !touchingScreen && isLocalMediaPlaying();
    if (event.getRepeatCount() == 0) {
      captured[index] = eligible;
    }
    if (!captured[index]) {
      return false;
    }
    if (eligible && !event.isCanceled()) {
      // A suggested stream still lets System UI select accessibility or Spotify Connect.
      // Use the local media stream explicitly only while local playback is active.
      AudioManagerCompatUtils.adjustStreamVolume(
          audioManager, AudioManager.STREAM_MUSIC,
          index == 0 ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_RAISE,
          AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_VIBRATE,
          getClass().getName());
    }
    return true;
  }

  private boolean isLocalMediaPlaying() {
    if (audioManager == null) {
      return false;
    }
    try {
      // Calls and ringing always retain the existing system/call-speech controls.
      return audioManager.getMode() == AudioManager.MODE_NORMAL && audioManager.isMusicActive();
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
