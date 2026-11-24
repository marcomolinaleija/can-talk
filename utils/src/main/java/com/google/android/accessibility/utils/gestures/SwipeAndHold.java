/*
 * Copyright (C) 2026 CAN Talk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.utils.gestures;

import static com.google.android.accessibility.utils.gestures.GestureUtils.MM_PER_CM;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.annotation.RequiresApi;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.R;
import com.google.android.accessibility.utils.gestures.GestureManifold.GestureConfigProvider;

/**
 * Matches a one-finger swipe in a single direction followed by keeping the finger held stationary
 * for {@link GestureConfiguration#SWIPE_AND_HOLD_TIMEOUT_MS}. Lifting early cancels this matcher so
 * normal flicks and two-direction swipes continue to work unchanged.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class SwipeAndHold extends GestureMatcher {

  // Deliberate swipe-and-hold starts slower than a quick flick; allow up to 400 ms to reach
  // the initial swipe confirmation distance before cancelling.
  private static final int MIN_START_THRESHOLD_MS = 400;

  private final int direction;
  private final GestureConfigProvider configProvider;
  private float gestureDetectionThresholdPixels;
  private float processingPixels;
  private float swipeConfirmPixels;
  private int maxStartThreshold;
  private float holdSlop;
  private float cancelDirectionSlop;

  private float baseX;
  private float baseY;
  private long baseTime;
  private float holdAnchorX;
  private float holdAnchorY;
  private boolean processingNotified;
  private boolean swipeConfirmed;

  SwipeAndHold(
      Context context,
      int direction,
      int gesture,
      GestureMatcher.StateChangeListener listener,
      GestureConfigProvider configProvider,
      GestureMatcher.AnalyticsEventLogger logger) {
    super(gesture, new Handler(context.getMainLooper()), listener, logger);
    this.direction = direction;
    this.configProvider = configProvider;
    initializeParameters(context);
    clear();
  }

  private void initializeParameters(Context context) {
    float gestureConfirmDistanceCm =
        context.getResources().getFloat(R.dimen.config_gesture_confirm_distance_cm);
    int maxTimeToStartSwipeMsPerCm =
        context.getResources().getInteger(R.integer.config_max_time_to_start_swipe_ms_per_cm);
    int baseStartThreshold = (int) (maxTimeToStartSwipeMsPerCm * gestureConfirmDistanceCm);
    maxStartThreshold = Math.max(baseStartThreshold * 2, MIN_START_THRESHOLD_MS);
    DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
    gestureDetectionThresholdPixels =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, MM_PER_CM, displayMetrics)
            * gestureConfirmDistanceCm;
    int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    processingPixels = touchSlop * 1.5f;
    swipeConfirmPixels = Math.max(touchSlop * 2.5f, gestureDetectionThresholdPixels * 0.45f);
    holdSlop = Math.max(touchSlop * 2.5f, gestureDetectionThresholdPixels * 0.50f);
    cancelDirectionSlop = Math.max(holdSlop, gestureDetectionThresholdPixels * 0.85f);
  }

  @Override
  public void onConfigurationChanged(Context context) {
    initializeParameters(context);
  }

  @Override
  public void clear() {
    baseX = Float.NaN;
    baseY = Float.NaN;
    baseTime = 0;
    holdAnchorX = Float.NaN;
    holdAnchorY = Float.NaN;
    processingNotified = false;
    swipeConfirmed = false;
    super.clear();
  }

  @Override
  protected void onDown(EventId eventId, MotionEvent event) {
    if (!configProvider.enableSwipeAndHold(getGestureId())) {
      cancelGesture(event);
      return;
    }
    if (Float.isNaN(baseX) && Float.isNaN(baseY)) {
      baseX = event.getX();
      baseY = event.getY();
      baseTime = event.getEventTime();
    } else {
      cancelGesture(event);
    }
  }

  @Override
  protected void onMove(EventId eventId, MotionEvent event) {
    final float x = event.getX();
    final float y = event.getY();
    final long time = event.getEventTime();

    if (!swipeConfirmed) {
      final long timeDelta = time - baseTime;
      if (timeDelta > maxStartThreshold) {
        cancelGesture(event);
        return;
      }

      final float dx = x - baseX;
      final float dy = y - baseY;
      final double moveDelta = Math.hypot(dx, dy);

      if (moveDelta >= swipeConfirmPixels) {
        if (Swipe.toDirection(dx, dy) != direction) {
          cancelGesture(event);
          return;
        }
        swipeConfirmed = true;
        holdAnchorX = x;
        holdAnchorY = y;
        startGesture(event);
        completeAfter(GestureConfiguration.SWIPE_AND_HOLD_TIMEOUT_MS, eventId, event);
        return;
      }

      if (!processingNotified
          && moveDelta >= processingPixels
          && projectAlong(dx, dy) >= processingPixels * 0.5f) {
        processingNotified = true;
        processGesture(eventId, event);
      }
      return;
    }

    // Swipe confirmed: keep the hold timer running while the finger stays near holdAnchor,
    // advance the anchor and restart the timer if moving forward along direction (allowing natural
    // thumb curvature), and cancel on deliberate reversals or perpendicular L-turns.
    final float holdDx = x - holdAnchorX;
    final float holdDy = y - holdAnchorY;
    final float along = projectAlong(holdDx, holdDy);
    final float cross = projectCross(holdDx, holdDy);
    if (along > holdSlop && along >= cross * 0.75f) {
      holdAnchorX = x;
      holdAnchorY = y;
      completeAfter(GestureConfiguration.SWIPE_AND_HOLD_TIMEOUT_MS, eventId, event);
    } else if (along <= -cancelDirectionSlop
        || (cross >= cancelDirectionSlop && cross > Math.max(0f, along) * 1.25f)) {
      cancelGesture(event);
    }
  }

  private float projectAlong(float dx, float dy) {
    return switch (direction) {
      case Swipe.UP -> -dy;
      case Swipe.DOWN -> dy;
      case Swipe.LEFT -> -dx;
      case Swipe.RIGHT -> dx;
      default -> 0f;
    };
  }

  private float projectCross(float dx, float dy) {
    return switch (direction) {
      case Swipe.UP, Swipe.DOWN -> Math.abs(dx);
      case Swipe.LEFT, Swipe.RIGHT -> Math.abs(dy);
      default -> 0f;
    };
  }

  @Override
  protected void onUp(EventId eventId, MotionEvent event) {
    if (getState() == STATE_CLEAR) {
      clear();
    } else {
      cancelGesture(event);
    }
  }

  @Override
  protected void onPointerDown(EventId eventId, MotionEvent event) {
    cancelGesture(event);
  }

  @Override
  protected void onPointerUp(EventId eventId, MotionEvent event) {
    cancelGesture(event);
  }

  @Override
  protected String getGestureName() {
    return "Swipe " + Swipe.directionToString(direction) + " and hold";
  }

  @Override
  public String toString() {
    return super.toString() + ", swipeConfirmed=" + swipeConfirmed;
  }
}
