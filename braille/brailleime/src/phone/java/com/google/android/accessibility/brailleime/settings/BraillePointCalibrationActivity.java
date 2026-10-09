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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Size;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.accessibility.braille.common.BrailleCommonTalkBackSpeaker;
import com.google.android.accessibility.braille.common.BrailleUserPreferences;
import com.google.android.accessibility.braille.common.BrailleUtils;
import com.google.android.accessibility.braille.common.TalkBackSpeaker;
import com.google.android.accessibility.braille.common.TouchDots;
import com.google.android.accessibility.brailleime.BrailleImeLog;
import com.google.android.accessibility.brailleime.R;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen calibration activity allowing user to tap dots 1 through 6 on the screen,
 * saving the dot coordinates sequentially (similar to ABK).
 */
public class BraillePointCalibrationActivity extends AppCompatActivity {
  private static final String TAG = "BraillePointCalibration";
  private static final int TOTAL_DOTS = 6;

  private final List<PointF> calibratedPoints = new ArrayList<>();
  private CalibrationTouchView touchView;
  private ToneGenerator toneGenerator;
  private Vibrator vibrator;
  private final Handler handler = new Handler(Looper.getMainLooper());

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
    getWindow().setFlags(
        WindowManager.LayoutParams.FLAG_FULLSCREEN,
        WindowManager.LayoutParams.FLAG_FULLSCREEN);

    try {
      toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
    } catch (Exception e) {
      BrailleImeLog.e(TAG, "ToneGenerator failed to initialize", e);
    }
    vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

    touchView = new CalibrationTouchView(this);
    setContentView(touchView);
  }

  @Override
  protected void onResume() {
    super.onResume();
    calibratedPoints.clear();
    touchView.invalidate();
    handler.postDelayed(
        () -> speak(getString(R.string.recalibrate_intro)),
        300);
  }

  @Override
  protected void onDestroy() {
    if (toneGenerator != null) {
      toneGenerator.release();
      toneGenerator = null;
    }
    super.onDestroy();
  }

  private void onScreenTapped(float x, float y) {
    if (calibratedPoints.size() >= TOTAL_DOTS) {
      return;
    }

    calibratedPoints.add(new PointF(x, y));
    int recordedDot = calibratedPoints.size();

    // Haptics and sound
    provideFeedback();

    if (recordedDot < TOTAL_DOTS) {
      int nextDot = recordedDot + 1;
      String prompt = getString(R.string.recalibrate_dot_saved_prompt, recordedDot, nextDot);
      speak(prompt);
      touchView.invalidate();
    } else {
      touchView.invalidate();
      saveCalibrationAndFinish();
    }
  }

  private void provideFeedback() {
    touchView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    if (vibrator != null && vibrator.hasVibrator()) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
      } else {
        vibrator.vibrate(50);
      }
    }
    if (toneGenerator != null) {
      try {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 70);
      } catch (Exception ignored) {
      }
    }
  }

  private void saveCalibrationAndFinish() {
    String completedMsg = getString(R.string.recalibrate_completed);
    speak(completedMsg);

    boolean isPhone = BrailleUtils.isPhoneSizedDevice(getResources());
    TouchDots mode = BrailleUserPreferences.readLayoutMode(this);
    boolean isTabletop = (mode == TouchDots.TABLETOP);
    int orientation = getResources().getConfiguration().orientation;
    Size screenSize = new Size(
        touchView.getWidth() > 0 ? touchView.getWidth() : getResources().getDisplayMetrics().widthPixels,
        touchView.getHeight() > 0 ? touchView.getHeight() : getResources().getDisplayMetrics().heightPixels);

    try {
      if (isPhone) {
        BrailleUserPreferences.writeCalibrationPointsPhone(
            this, isTabletop, orientation, calibratedPoints, screenSize);
      } else {
        BrailleUserPreferences.writeCalibrationPointsTablet(
            this, isTabletop, orientation, calibratedPoints, screenSize);
      }
    } catch (ParseException e) {
      BrailleImeLog.e(TAG, "Failed to save calibrated points", e);
    }

    Toast.makeText(this, completedMsg, Toast.LENGTH_SHORT).show();
    handler.postDelayed(this::finish, 1200);
  }

  private void speak(String text) {
    BrailleCommonTalkBackSpeaker.getInstance().speak(text, TalkBackSpeaker.AnnounceType.INTERRUPT);
    touchView.announceForAccessibility(text);
  }

  private class CalibrationTouchView extends View {
    private final Paint backgroundPaint = new Paint();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotSavedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dotRadius;

    public CalibrationTouchView(Context context) {
      super(context);
      setFocusable(true);
      setFocusableInTouchMode(true);

      backgroundPaint.setColor(Color.BLACK);
      textPaint.setColor(Color.WHITE);
      textPaint.setTextSize(getResources().getDisplayMetrics().density * 22);
      textPaint.setTextAlign(Paint.Align.CENTER);

      dotSavedPaint.setColor(Color.parseColor("#4CAF50")); // Green for saved dots
      dotPaint.setColor(Color.parseColor("#2196F3"));      // Blue for touch
      dotTextPaint.setColor(Color.WHITE);
      dotTextPaint.setTextSize(getResources().getDisplayMetrics().density * 18);
      dotTextPaint.setTextAlign(Paint.Align.CENTER);

      dotRadius = getResources().getDisplayMetrics().density * 28;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
        onScreenTapped(event.getX(), event.getY());
        return true;
      }
      return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      canvas.drawPaint(backgroundPaint);

      int currentStep = calibratedPoints.size() + 1;
      String prompt;
      if (currentStep <= TOTAL_DOTS) {
        prompt = getString(R.string.recalibrate_touch_dot_prompt, currentStep)
            + " (" + (currentStep) + "/" + TOTAL_DOTS + ")";
      } else {
        prompt = getString(R.string.recalibrate_completed);
      }

      float centerX = getWidth() / 2f;
      float topMargin = getHeight() * 0.15f;
      canvas.drawText(prompt, centerX, topMargin, textPaint);

      // Draw all saved points
      for (int i = 0; i < calibratedPoints.size(); i++) {
        PointF point = calibratedPoints.get(i);
        canvas.drawCircle(point.x, point.y, dotRadius, dotSavedPaint);
        // Center text vertically in circle
        float textY = point.y - ((dotTextPaint.descent() + dotTextPaint.ascent()) / 2);
        canvas.drawText(String.valueOf(i + 1), point.x, textY, dotTextPaint);
      }
    }
  }
}
