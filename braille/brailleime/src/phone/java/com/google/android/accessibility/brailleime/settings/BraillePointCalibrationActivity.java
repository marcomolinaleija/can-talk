/*
 * Copyright (C) 2026 Can Talk contributors
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

package com.google.android.accessibility.brailleime.settings;

import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.accessibility.braille.common.BrailleUserPreferences;
import com.google.android.accessibility.braille.common.TouchDots;
import com.google.android.accessibility.braille.interfaces.BrailleCharacter;
import com.google.android.accessibility.braille.interfaces.TalkBackForBrailleIme;
import com.google.android.accessibility.brailleime.BrailleIme;
import com.google.android.accessibility.brailleime.BrailleImeLog;
import com.google.android.accessibility.brailleime.BrailleImeVibrator;
import com.google.android.accessibility.brailleime.BrailleInputOptions;
import com.google.android.accessibility.brailleime.Utils;
import com.google.android.accessibility.brailleime.input.BrailleInputPlane;
import com.google.android.accessibility.brailleime.input.BrailleInputView;
import com.google.android.accessibility.brailleime.input.DotHoldSwipe;
import com.google.android.accessibility.brailleime.input.Swipe;
import com.google.android.accessibility.brailleime.keyboardview.AccessibilityOverlayKeyboardView;
import com.google.android.accessibility.brailleime.keyboardview.KeyboardView;

/**
 * Full-screen calibration activity that hosts the actual Braille keyboard (BrailleInputView)
 * with TalkBack Explore-by-Touch disabled, allowing the user to calibrate dots 1 through 6
 * with direct single taps (similar to ABK).
 */
public class BraillePointCalibrationActivity extends AppCompatActivity {
  private static final String TAG = "BraillePointCalibration";
  private static TalkBackForBrailleIme talkBackForBrailleIme;

  private KeyboardView keyboardView;
  private BrailleInputView fallbackInputView;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean isCleanedUp = false;

  public static void initialize(TalkBackForBrailleIme talkBackForBrailleIme) {
    BraillePointCalibrationActivity.talkBackForBrailleIme = talkBackForBrailleIme;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
    getWindow().setFlags(
        WindowManager.LayoutParams.FLAG_FULLSCREEN,
        WindowManager.LayoutParams.FLAG_FULLSCREEN);

    if (talkBackForBrailleIme == null) {
      talkBackForBrailleIme = BrailleIme.getTalkBackForBrailleIme();
    }

    View backgroundView = new View(this);
    backgroundView.setBackgroundColor(Color.BLACK);
    setContentView(backgroundView);

    boolean isTabletop = (BrailleUserPreferences.readLayoutMode(this) == TouchDots.TABLETOP);

    if (talkBackForBrailleIme != null) {
      keyboardView = new AccessibilityOverlayKeyboardView(this, keyboardViewCallback);
      keyboardView.setWindowManager(talkBackForBrailleIme.getWindowManager());
      keyboardView.createViewContainer();
      keyboardView.createAndAddInputView(inputPlaneCallback, isTabletop);
    } else {
      BrailleInputOptions options =
          BrailleInputOptions.builder()
              .setReverseDots(BrailleUserPreferences.readReverseDotsMode(this))
              .setFlipDotsVertically(BrailleUserPreferences.readFlipDotsVertically(this))
              .setBrailleType(BrailleUserPreferences.getCurrentTypingLanguageType(this))
              .setTutorialMode(false)
              .build();
      Size screenSize = Utils.getDisplaySizeInPixels(this);
      fallbackInputView =
          new BrailleInputView(this, inputPlaneCallback, screenSize, options, isTabletop);
      fallbackInputView.setAccumulationMode(BrailleUserPreferences.readAccumulateMode(this));
      setContentView(fallbackInputView);
      fallbackInputView.post(
          () -> fallbackInputView.startSequentialCalibration(sequentialCalibrationListener));
    }
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    if (keyboardView != null) {
      keyboardView.onOrientationChanged(newConfig.orientation);
    }
    if (fallbackInputView != null) {
      fallbackInputView.onOrientationChanged(
          newConfig.orientation, Utils.getDisplaySizeInPixels(this));
    }
  }

  @Override
  public void onBackPressed() {
    cleanupAndFinish();
    super.onBackPressed();
  }

  @Override
  protected void onDestroy() {
    cleanupAndFinish();
    super.onDestroy();
  }

  private synchronized void cleanupAndFinish() {
    if (isCleanedUp) {
      return;
    }
    isCleanedUp = true;
    handler.removeCallbacksAndMessages(null);
    if (keyboardView != null) {
      keyboardView.tearDown();
      keyboardView = null;
    }
    if (talkBackForBrailleIme != null) {
      talkBackForBrailleIme.onBrailleImeInactivated(Utils.useImeSuppliedInputWindow());
    }
    if (!isFinishing()) {
      finish();
    }
  }

  private final KeyboardView.KeyboardViewCallback keyboardViewCallback =
      new KeyboardView.KeyboardViewCallback() {
        @Override
        public void onViewReady() {
          BrailleImeLog.d(TAG, "onViewReady");
          if (talkBackForBrailleIme != null) {
            if (talkBackForBrailleIme.isVibrationFeedbackEnabled()) {
              BrailleImeVibrator.getInstance(BraillePointCalibrationActivity.this).enable();
            }
            talkBackForBrailleIme.onBrailleImeActivated(
                /* disableEbt= */ true,
                Utils.useImeSuppliedInputWindow(),
                keyboardView.obtainImeViewRegion().orElse(null));
          }
          if (keyboardView != null) {
            keyboardView.startSequentialCalibration(sequentialCalibrationListener);
          }
        }

        @Override
        public void onViewUpdated() {}

        @Override
        public void onViewCleared() {
          BrailleImeLog.d(TAG, "onViewCleared");
          BrailleImeVibrator.getInstance(BraillePointCalibrationActivity.this).disable();
        }

        @Override
        public boolean isHideScreenMode() {
          return talkBackForBrailleIme != null && talkBackForBrailleIme.isHideScreenMode();
        }
      };

  private final BrailleInputPlane.SequentialCalibrationListener sequentialCalibrationListener =
      new BrailleInputPlane.SequentialCalibrationListener() {
        @Override
        public void onDotSaved(int dotNumber, int nextDotNumber) {}

        @Override
        public void onCalibrationCompleted() {
          handler.postDelayed(BraillePointCalibrationActivity.this::cleanupAndFinish, 1200);
        }
      };

  private final BrailleInputView.Callback inputPlaneCallback =
      new BrailleInputView.Callback() {
        @Override
        public boolean onSwipeProduced(Swipe swipe) {
          return false;
        }

        @Override
        public boolean onDotHoldAndDotSwipe(DotHoldSwipe dotHoldSwipe) {
          return false;
        }

        @Override
        public boolean onHoldProduced(int pointersHeldCount) {
          return false;
        }

        @Override
        public String onBrailleProduced(BrailleCharacter brailleCharacter) {
          return "";
        }
      };
}
