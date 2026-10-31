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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
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

/**
 * Full-screen calibration activity that hosts the actual Braille keyboard (BrailleInputView)
 * with TalkBack Explore-by-Touch disabled, allowing the user to calibrate dots 1 through 6
 * with direct single taps (similar to ABK).
 */
public class BraillePointCalibrationActivity extends AppCompatActivity {
  private static final String TAG = "BraillePointCalibration";
  private static TalkBackForBrailleIme talkBackForBrailleIme;

  private BrailleInputView brailleInputView;
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

    boolean isTabletop = (BrailleUserPreferences.readLayoutMode(this) == TouchDots.TABLETOP);
    BrailleInputOptions options =
        BrailleInputOptions.builder()
            .setReverseDots(BrailleUserPreferences.readReverseDotsMode(this))
            .setFlipDotsVertically(BrailleUserPreferences.readFlipDotsVertically(this))
            .setBrailleType(BrailleUserPreferences.getCurrentTypingLanguageType(this))
            .setTutorialMode(false)
            .build();
    Size screenSize = Utils.getDisplaySizeInPixels(this);
    brailleInputView =
        new BrailleInputView(this, inputPlaneCallback, screenSize, options, isTabletop);
    brailleInputView.setAccumulationMode(BrailleUserPreferences.readAccumulateMode(this));
    setContentView(brailleInputView);

    try {
      if (talkBackForBrailleIme != null) {
        if (talkBackForBrailleIme.isVibrationFeedbackEnabled()) {
          BrailleImeVibrator.getInstance(this).enable();
        }
        talkBackForBrailleIme.onBrailleImeActivated(
            /* disableEbt= */ true,
            Utils.useImeSuppliedInputWindow(),
            /* passThroughRegion= */ null);
      }
    } catch (Exception e) {
      BrailleImeLog.e(TAG, "Error notifying TalkBack of calibration activation", e);
    }

    brailleInputView.post(
        () -> {
          if (!isFinishing() && !isCleanedUp) {
            brailleInputView.startSequentialCalibration(sequentialCalibrationListener);
          }
        });
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    if (brailleInputView != null) {
      brailleInputView.onOrientationChanged(
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
    try {
      if (talkBackForBrailleIme != null) {
        talkBackForBrailleIme.onBrailleImeInactivated(Utils.useImeSuppliedInputWindow());
      }
      BrailleImeVibrator.getInstance(this).disable();
    } catch (Exception e) {
      BrailleImeLog.e(TAG, "Error cleaning up TalkBack state", e);
    }
    if (!isFinishing()) {
      finish();
    }
  }

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
