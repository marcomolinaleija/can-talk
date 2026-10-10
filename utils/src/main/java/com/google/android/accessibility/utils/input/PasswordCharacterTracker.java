/*
 * Copyright (C) 2024 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.utils.input;

import androidx.annotation.Nullable;

/**
 * Tracks the most recently typed character from physical or on-screen keyboards,
 * and tracks Braille IME handled events, enabling password character echo when
 * "Speak passwords" preference is enabled.
 */
public class PasswordCharacterTracker {

  private static final long TIMEOUT_MS = 2000;
  private static final PasswordCharacterTracker INSTANCE = new PasswordCharacterTracker();

  private final Object lock = new Object();
  private @Nullable String lastTypedChar = null;
  private long lastTypedTime = 0;
  private boolean brailleHandled = false;
  private long brailleHandledTime = 0;

  public static PasswordCharacterTracker getInstance() {
    return INSTANCE;
  }

  private PasswordCharacterTracker() {}

  /** Records a typed character. */
  public void recordTypedChar(char c) {
    synchronized (lock) {
      lastTypedChar = String.valueOf(c);
      lastTypedTime = System.currentTimeMillis();
    }
  }

  /** Records a typed character from a CharSequence if length is 1. */
  public void recordTypedChar(@Nullable CharSequence text) {
    if (text != null && text.length() == 1) {
      recordTypedChar(text.charAt(0));
    }
  }

  /**
   * Consumes and returns the most recently recorded character if within the timeout.
   * Returns null if no character was recorded or if it has expired.
   */
  public @Nullable String consumeTypedChar() {
    synchronized (lock) {
      if (lastTypedChar == null) {
        return null;
      }
      long elapsed = System.currentTimeMillis() - lastTypedTime;
      String result = null;
      if (elapsed <= TIMEOUT_MS) {
        result = lastTypedChar;
      }
      lastTypedChar = null;
      lastTypedTime = 0;
      return result;
    }
  }

  /** Records that the Braille IME has handled speaking a password character. */
  public void recordBrailleHandled() {
    synchronized (lock) {
      brailleHandled = true;
      brailleHandledTime = System.currentTimeMillis();
    }
  }

  /**
   * Checks and consumes the flag indicating that Braille IME handled the character.
   */
  public boolean consumeBrailleHandled() {
    synchronized (lock) {
      if (!brailleHandled) {
        return false;
      }
      long elapsed = System.currentTimeMillis() - brailleHandledTime;
      boolean result = (elapsed <= TIMEOUT_MS);
      brailleHandled = false;
      brailleHandledTime = 0;
      return result;
    }
  }

  /** Clears any recorded state. */
  public void clear() {
    synchronized (lock) {
      lastTypedChar = null;
      lastTypedTime = 0;
      brailleHandled = false;
      brailleHandledTime = 0;
    }
  }
}
