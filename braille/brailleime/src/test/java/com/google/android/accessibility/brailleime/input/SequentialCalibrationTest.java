/*
 * Copyright 2026 Can Talk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.brailleime.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.PointF;
import android.os.SystemClock;
import android.util.Size;
import android.view.MotionEvent;
import com.google.android.accessibility.braille.common.Constants.BrailleType;
import com.google.android.accessibility.brailleime.BrailleInputOptions;
import com.google.android.accessibility.brailleime.input.BrailleInputPlane.DotTarget;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Tests for sequential point calibration on {@link BrailleInputView}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SequentialCalibrationTest {

  private Context context;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
  }

  @Test
  public void sequentialCalibration_tabletop_recordsExactDotPositionsWithoutScrambling() {
    BrailleInputView.Callback callback =
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
        };

    Size size = new Size(1080, 1920);
    BrailleInputOptions options =
        BrailleInputOptions.builder()
            .setReverseDots(false)
            .setFlipDotsVertically(false)
            .setBrailleType(BrailleType.SIX_DOT)
            .setTutorialMode(false)
            .build();

    BrailleInputView inputView =
        new BrailleInputView(context, callback, size, options, /* isTableTopMode= */ true);

    assertFalse(inputView.isSequentialCalibrationActive());

    AtomicInteger dotsSavedCount = new AtomicInteger(0);
    AtomicBoolean completed = new AtomicBoolean(false);

    inputView.startSequentialCalibration(
        new BrailleInputPlane.SequentialCalibrationListener() {
          @Override
          public void onDotSaved(int dotNumber, int nextDotNumber) {
            dotsSavedCount.incrementAndGet();
          }

          @Override
          public void onCalibrationCompleted() {
            completed.set(true);
          }
        });

    assertTrue(inputView.isSequentialCalibrationActive());

    // Coordinates for dots 1 through 6
    PointF[] expectedPoints = new PointF[] {
      new PointF(150f, 250f), // Dot 1
      new PointF(150f, 450f), // Dot 2
      new PointF(150f, 650f), // Dot 3
      new PointF(600f, 250f), // Dot 4
      new PointF(600f, 450f), // Dot 5
      new PointF(600f, 650f)  // Dot 6
    };

    long downTime = SystemClock.uptimeMillis();
    for (int i = 0; i < 6; i++) {
      PointF pt = expectedPoints[i];
      MotionEvent downEvent =
          MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, pt.x, pt.y, 0);
      inputView.onTouchEvent(downEvent);
      downEvent.recycle();

      MotionEvent upEvent =
          MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, pt.x, pt.y, 0);
      inputView.onTouchEvent(upEvent);
      upEvent.recycle();
    }

    assertEquals(5, dotsSavedCount.get()); // onDotSaved called for dots 1..5 before completion
    assertTrue(completed.get());
    assertFalse(inputView.isSequentialCalibrationActive());

    // Verify dot targets order and exact coordinates
    List<DotTarget> dotTargets = inputView.getDotTargets();
    assertEquals(6, dotTargets.size());

    for (DotTarget target : dotTargets) {
      int dotNum = target.getDotNumber();
      PointF expectedPt = expectedPoints[dotNum - 1];
      PointF actualPt = target.getCenter();
      assertEquals("Dot " + dotNum + " X coordinate", expectedPt.x, actualPt.x, 0.01f);
      assertEquals("Dot " + dotNum + " Y coordinate", expectedPt.y, actualPt.y, 0.01f);
    }
  }

  @Test
  public void sequentialCalibration_cancel_stopsCalibration() {
    BrailleInputView.Callback callback =
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
        };

    Size size = new Size(1080, 1920);
    BrailleInputOptions options =
        BrailleInputOptions.builder()
            .setReverseDots(false)
            .setFlipDotsVertically(false)
            .setBrailleType(BrailleType.SIX_DOT)
            .setTutorialMode(false)
            .build();

    BrailleInputView inputView =
        new BrailleInputView(context, callback, size, options, /* isTableTopMode= */ false);

    inputView.startSequentialCalibration(null);
    assertTrue(inputView.isSequentialCalibrationActive());

    inputView.cancelSequentialCalibration();
    assertFalse(inputView.isSequentialCalibrationActive());
  }
}
