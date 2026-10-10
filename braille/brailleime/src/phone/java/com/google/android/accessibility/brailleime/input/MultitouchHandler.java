/*
 * Copyright 2019 Google Inc.
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

import static com.google.common.collect.ImmutableList.toImmutableList;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.graphics.PointF;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Range;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.google.android.accessibility.brailleime.BrailleImeLog;
import com.google.android.accessibility.brailleime.Utils;
import com.google.android.accessibility.brailleime.input.Swipe.Direction;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

/**
 * Provides custom multi-pointer touch input support by processing MotionEvents and returning {@link
 * MultitouchResult} from {@link MultitouchHandler#onTouchEvent} if commission occurred.
 *
 * <p>A client uses this class by continuously forwarding MotionEvents to it via {@link
 * MultitouchHandler#onTouchEvent}. If that method decides that a commission has occurred, then it
 * will return a present {@link MultitouchResult}. There are 3 types of commission that can occur:
 * {@link MultitouchResult#TYPE_TAP}, {@link MultitouchResult#TYPE_SWIPE}, {@link
 * MultitouchResult#TYPE_HOLD}.
 *
 * <p>A {@link MultitouchResult#TYPE_TAP} occurs if the finger(s) press down and eventually all lift
 * up with the final pointer having not travelled too long a distance.
 *
 * <p>A {@link MultitouchResult#TYPE_SWIPE} occurs if the finger(s) press down and eventually all
 * lift up with the final pointer having travelled a sufficient distance, with sufficient speed.
 * Motions that would otherwise produce a Swipe but are not cardinal enough, or not fast enough, do
 * not produce a Swipe, nor do they produce anything else.
 *
 * <p>A {@link MultitouchResult#TYPE_HOLD} occurs if the finger(s) press down and no new pointers
 * are added or removed for a fixed duration, provided that a {@link HoldRecognizer} has been
 * provided to decide how many pointers are needed in order for a Hold to occur.
 */
class MultitouchHandler {

  private static final String TAG = "MultitouchHandler";

  /**
   * Pointers that were released this close in time to the final pointer release are considered
   * recent enough to contribute to a {@link MultitouchResult#TYPE_TAP}, even if accumulation mode
   * is false. Swipes keep the moving fingers from the whole stroke.
   */
  private static final int RECENCY_MAX_MS = 250;

  /** A Tap is not recognizable if any of the pointers travelled more than this distance. */
  private static final int TAP_MAX_DISTANCE_MM = 5;

  /**
   * A Swipe is not recognizable if the max ratio of the x and y components of the absolute
   * displacement vector (between final and initial points) is less than the following threshold.
   * For example, a value of 1.0f means that a perfectly diagonal swipe is recognizable. A value of
   * 2.0f means that the distance travelled in the dominant direction must be twice as long as the
   * distance travelled in the non-dominant direction. This value must exceed 1.0f.
   */
  private static final float SWIPE_MIN_RATIO = 2.0f;

  /**
   * A Swipe is not recognizable if the speed in the dominant direction does not exceed the
   * following threshold.
   */
  private static final int SWIPE_MIN_SPEED_MM_PER_SECOND = 45;

  /**
   * A Swipe is not recognizable if the distance from initial to final point in the dominant
   * direction does not exceed the following threshold.
   */
  private static final int SWIPE_MIN_DISTANCE_MM = 9;

  /**
   * Pointers held for this duration, without additional pointers added or removed, qualify as a
   * possible hold. Whether a hold is actually produced depends on the {@link HoldRecognizer}.
   */
  private static final long HOLD_MIN_DURATION_MS = 2000;

  /** Maps pointerId's to active PointerWithHistory objects. */
  @SuppressLint("UseSparseArrays")
  private final HashMap<Integer, PointerWithHistory> activePointers = new HashMap<>();

  /** Maps pointerId's to inactive PointerWithHistory objects. */
  @SuppressLint("UseSparseArrays")
  private final HashMap<Integer, PointerWithHistory> inactivePointers = new HashMap<>();

  private final Handler handler = new Handler();
  private final HoldRecognizer holdRecognizer;
  private final MultitouchResultListener multitouchResultListener;

  private boolean isAccumulationMode;
  private boolean isProcessed;
  private boolean hasCommittedCommand;
  private long lastReleaseTime;
  private long releaseOrder;

  private float tapMaxDistancePixels;
  private float swipeMinSpeedPixelsPerSecond;
  private float swipeMinDistancePixels;

  private long longHoldDurationMinMillis;

  /** Interface for recognizing a Hold. */
  interface HoldRecognizer {
    /**
     * Returns true if, in the current context, a Hold for calibration should occur.
     *
     * @param pointersHeldCount how many pointers are currently being held
     */
    boolean isCalibrationHoldRecognized(int pointersHeldCount);

    /**
     * Return true if, in the current context, a Hold should occur.
     *
     * @param pointersHeldCount how many pointers are currently being held
     */
    boolean isHoldRecognized(int pointersHeldCount);
  }

  /** Listens multi-touch events. */
  interface MultitouchResultListener {
    @CanIgnoreReturnValue
    boolean detect(Optional<MultitouchResult> resultOptional);
  }

  /** Constructs a MultitouchHandler with an optional {@link HoldRecognizer}. */
  MultitouchHandler(
      Resources resources,
      @Nullable HoldRecognizer holdRecognizer,
      MultitouchResultListener multitouchResultListener) {
    tapMaxDistancePixels = Utils.mmToPixels(resources, TAP_MAX_DISTANCE_MM);
    swipeMinSpeedPixelsPerSecond = Utils.mmToPixels(resources, SWIPE_MIN_SPEED_MM_PER_SECOND);
    swipeMinDistancePixels = Utils.mmToPixels(resources, SWIPE_MIN_DISTANCE_MM);
    longHoldDurationMinMillis = HOLD_MIN_DURATION_MS;
    this.multitouchResultListener = multitouchResultListener;
    isAccumulationMode = false;
    this.holdRecognizer = holdRecognizer;
  }

  /**
   * Sets the accumulation mode on or off.
   *
   * <p>In accumulation mode, all pointers pressed after the initial pointer will contribute to a
   * potential final commission regardless of when they are released. With accumulation mode off,
   * only those pointers released just prior to the final release contribute to a tap. Swipe touch
   * counts always include the moving fingers from the whole stroke.
   */
  void setAccumulationMode(boolean accumulationMode) {
    this.isAccumulationMode = accumulationMode;
  }

  /** Gets the accumulation mode. */
  boolean isAccumulationMode() {
    return isAccumulationMode;
  }

  /** Gets a copy of the currently active touch points. */
  ImmutableList<PointF> getActivePoints() {
    return activePointers.values().stream()
        .map(pointerWithHistory -> pointerWithHistory.pointCurrent)
        .collect(toImmutableList());
  }

  private final Runnable tapOrSwipeRunnable =
      new Runnable() {
        @Override
        public void run() {
          BrailleImeLog.d(TAG, "tap or swipe task is running.");
          long eventTime = lastReleaseTime;
          Optional<Swipe> swipe =
              createSwipe(
                  getSwipeReferencePointer(), getSwipePointsHistory());
          BrailleImeLog.d(TAG, "swipe is present: " + swipe.isPresent());
          if (swipe.isPresent()) {
            ImmutableList<PointF> heldPoints = getRecentlyReleasedHeldPoints(eventTime);
            isProcessed =
                multitouchResultListener.detect(
                    Optional.of(
                        heldPoints.isEmpty()
                            ? MultitouchResult.createSwipe(swipe.get(), getSwipeInitialPoints())
                            : MultitouchResult.createHoldAndDotSwipe(
                                heldPoints, swipe.get(), getSwipeInitialPoints())));
            BrailleImeLog.d(TAG, "swipe result: " + isProcessed);
          } else if (getMaximumDistanceMovedAmongInactivePointers() <= tapMaxDistancePixels
              && inactivePointers.values().stream().noneMatch(pointer -> pointer.isHoldInProgress)) {
            // Check if the motion is a legitimate tap.
            isProcessed =
                multitouchResultListener.detect(
                    Optional.of(
                        MultitouchResult.createTap(
                            getRecentlyInactivatedCurrentPoints(eventTime))));
            BrailleImeLog.d(TAG, "tap result: " + isProcessed);
          } else {
            isProcessed =
                multitouchResultListener.detect(
                    Optional.of(MultitouchResult.createInvalidGesture()));
            BrailleImeLog.d(TAG, "invalid gesture result: " + isProcessed);
          }
          clearPointerCollections();
          handler.removeCallbacksAndMessages(null);
        }
      };

  private final Runnable holdRunnable =
      new Runnable() {
        @Override
        public void run() {
          BrailleImeLog.d(TAG, "hold task is running.");
          if (holdRecognizer != null
              && !activePointers.isEmpty()
              && activePointers.values().stream()
                  .allMatch(pointer -> pointer.isHoldInProgress
                      || pointer.distanceMoved() <= tapMaxDistancePixels)
              && holdRecognizer.isHoldRecognized(getActivePoints().size())) {
            Optional<PointerWithHistory> inactivePointHistory =
                getLastRecentlyInactivatedPointsHistory(
                    SystemClock.uptimeMillis() - ViewConfiguration.getLongPressTimeout());
            if (inactivePointHistory.isPresent()
                && !inactivePointHistory.get().isHoldInProgress
                && !holdRecognizer.isHoldRecognized(getActivePoints().size() + 1)) {
              return;
            }
            for (PointerWithHistory value : activePointers.values()) {
              if (!value.isHoldInProgress) {
                value.pointHeld = new PointF(value.pointCurrent.x, value.pointCurrent.y);
                value.maximumDistanceSinceHold = 0;
                value.momentMovementStarted = -1;
                value.isHoldInProgress = true;
              }
            }
            isProcessed =
                multitouchResultListener.detect(
                    Optional.of(MultitouchResult.createHold(getHeldPoints())));
            BrailleImeLog.d(TAG, "hold result: " + isProcessed);
          }
        }
      };

  private final Runnable longHoldRunnable =
      new Runnable() {
        @Override
        public void run() {
          BrailleImeLog.d(TAG, "long hold task is running.");
          if (holdRecognizer != null
              && !activePointers.isEmpty()
              && activePointers.values().stream()
                  .allMatch(pointer -> pointer.distanceMoved() <= tapMaxDistancePixels)
              && holdRecognizer.isCalibrationHoldRecognized(getActivePoints().size())) {
            MultitouchResult result = MultitouchResult.createCalibrationHold(getActivePoints());
            isProcessed = multitouchResultListener.detect(Optional.of(result));
            hasCommittedCommand = isProcessed;
            BrailleImeLog.d(TAG, "long hold result: " + isProcessed);
          }
        }
      };

  private final Runnable holdAndSwipeRunnable =
      new Runnable() {
        @Override
        public void run() {
          BrailleImeLog.d(TAG, "hold and swipe is running.");
          ImmutableList<PointF> heldPoints = getHeldPoints();
          if (!heldPoints.isEmpty()) {
            if (heldPoints.size() == getActivePoints().size()
                && activePointers.values().stream()
                    .allMatch(pointer -> pointer.maximumDistanceSinceHold <= tapMaxDistancePixels)) {
              heldPoints = ImmutableList.<PointF>builder().addAll(heldPoints)
                  .addAll(getRecentlyReleasedHeldPoints(lastReleaseTime)).build();
              Optional<Swipe> swipe =
                  createSwipe(
                      getSwipeReferencePointer(), getSwipePointsHistory());
              BrailleImeLog.d(
                  TAG, "swipe is present: " + (swipe.isPresent() ? swipe.get() : false));
              if (swipe.isPresent()) {
                MultitouchResult result =
                    MultitouchResult.createHoldAndDotSwipe(
                        heldPoints,
                        swipe.get(),
                        getSwipeInitialPoints());
                multitouchResultListener.detect(Optional.of(result));
                // A recognized modifier gesture must not type the held dots on release, even
                // when the assigned action is unavailable in the current editor.
                isProcessed = true;
                hasCommittedCommand = true;
                // Each swipe while keeping modifiers held is a separate command. Recently
                // released fingers from the previous command must not inflate its touch count.
                inactivePointers.clear();
                lastReleaseTime = 0;
                releaseOrder = 0;
                BrailleImeLog.d(TAG, "hold and swipe result: " + isProcessed);
                handler.removeCallbacksAndMessages(null);
              }
            }
          }
        }
      };

  /**
   * Processes touch input and returns a {@link MultitouchResult} if commission occurred.
   *
   * <p>The types of commission are discussed atop this file. If no commission has occurred, then an
   * empty {@link Optional} is returned.
   *
   * <p>If a {@code TYPE_HOLD} has occurred, then all subsequent calls to this method will return
   * null until the motion is resolved by either being cancelled or the final pointer has come up.
   *
   * @param event the {@link MotionEvent} as received by the owning view.
   */
  boolean onTouchEvent(MotionEvent event) {
    int action = event.getActionMasked();
    int actionPointerIndex = event.getActionIndex();
    int actionPointerId = event.getPointerId(actionPointerIndex);
    long eventTime = event.getEventTime();
    if (activePointers.isEmpty() && action != MotionEvent.ACTION_DOWN) {
      // Ignore the tail of a gesture cancelled by calibration, rotation or view teardown.
      return true;
    }

    if (!activePointers.isEmpty()) {
      // Update the active pointers
      // All of the PointerWithHistory objects get updated because ACTION_MOVE events are not sent
      // on a per pointer basis (instead they ride along the initial 'action' pointer).
      for (int pointerIndex = 0; pointerIndex < event.getPointerCount(); pointerIndex++) {
        int pointerId = event.getPointerId(pointerIndex);
        if (activePointers.containsKey(pointerId)) {
          PointerWithHistory pointerWithHistory = activePointers.get(pointerId);
          for (int historyIndex = 0; historyIndex < event.getHistorySize(); historyIndex++) {
            pointerWithHistory.updateCurrentPoint(
                event.getHistoricalX(pointerIndex, historyIndex),
                event.getHistoricalY(pointerIndex, historyIndex),
                event.getHistoricalEventTime(historyIndex), tapMaxDistancePixels);
          }
          pointerWithHistory.updateCurrentPoint(
              event.getX(pointerIndex), event.getY(pointerIndex), eventTime, tapMaxDistancePixels);
          if (pointerWithHistory.isHoldInProgress
              && pointerWithHistory.maximumDistanceSinceHold > tapMaxDistancePixels) {
            // A finger that starts swiping is no longer a modifier, even if it previously held.
            pointerWithHistory.isHoldInProgress = false;
            pointerWithHistory.pointHeld = null;
            if (!hasCommittedCommand) {
              isProcessed = false;
            }
          }
          activePointers.replace(pointerId, pointerWithHistory);
        }
      }
    }

    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
      if (action == MotionEvent.ACTION_DOWN) {
        cancel();
      }
      isProcessed = false;
      hasCommittedCommand = false;
      handler.removeCallbacksAndMessages(null);
      PointF point = new PointF(event.getX(actionPointerIndex), event.getY(actionPointerIndex));
      PointerWithHistory pointerWithHistory =
          new PointerWithHistory(actionPointerId, point, eventTime);
      activePointers.put(actionPointerId, pointerWithHistory);
      handler.postDelayed(holdRunnable, ViewConfiguration.getLongPressTimeout());
      handler.postDelayed(longHoldRunnable, longHoldDurationMinMillis);
    } else if (action == MotionEvent.ACTION_CANCEL) {
      clearPointerCollections();
      handler.removeCallbacksAndMessages(null);
    } else if (action == MotionEvent.ACTION_POINTER_UP) {
      // ACTION_POINTER_UP: non-final pointer was released.
      transferPointerToInactive(actionPointerId, eventTime);
      recognizeStationaryModifiers(eventTime);
      handler.removeCallbacksAndMessages(null);
      handler.postDelayed(holdRunnable, ViewConfiguration.getLongPressTimeout());
      handler.postDelayed(longHoldRunnable, longHoldDurationMinMillis);
      // Resolve before another finger lifts or a sensor changes the layout.
      holdAndSwipeRunnable.run();
    } else if (action == MotionEvent.ACTION_UP) {
      // ACTION_UP: final pointer was released.
      handler.removeCallbacksAndMessages(null);
      transferPointerToInactive(actionPointerId, eventTime);
      recognizeStationaryModifiers(eventTime);
      if (hasCommittedCommand || (isProcessed && getSwipePointsHistory().isEmpty())) {
        clearPointerCollections();
      } else {
        // A hold acknowledgement is not a completed swipe command.
        tapOrSwipeRunnable.run();
      }
    }
    // Must be true so that next touch event would come.
    return true;
  }

  private Optional<Swipe> createSwipe(
      Optional<PointerWithHistory> finalPointer,
      List<PointerWithHistory> recentlyInactivatedPoints) {
    if (finalPointer.isEmpty()) {
      return Optional.empty();
    }
    float xDiff = finalPointer.get().pointCurrent.x - finalPointer.get().pointInitial.x;
    float yDiff = finalPointer.get().pointCurrent.y - finalPointer.get().pointInitial.y;
    float xExcess = Math.abs(xDiff) - swipeMinDistancePixels;
    float yExcess = Math.abs(yDiff) - swipeMinDistancePixels;
    // Check if the motion is a legitimate swipe.
    if (!Utils.isVectorNearlyCardinal(new PointF(xDiff, yDiff), SWIPE_MIN_RATIO)) {
      // Reject diagonals even when only one component exceeds the distance threshold.
      return Optional.empty();
    }
    Optional<Direction> optionalDirection = getDirection(xExcess, yExcess, xDiff, yDiff);
    if (optionalDirection.isEmpty()) {
      return Optional.empty();
    }
    Direction direction = optionalDirection.get();
    Speed speed = finalPointer.get().computeSpeed();
    if (direction == Direction.LEFT || direction == Direction.RIGHT) {
      // X displacement threshold was met, and exceeds y displacement.
      if (speed.x < swipeMinSpeedPixelsPerSecond
          || !fingersTravelSameDirection(recentlyInactivatedPoints, direction)) {
        // Not quick enough or fingers moving different directions.
        return Optional.empty();
      }
    } else if (direction == Direction.UP || direction == Direction.DOWN) {
      // Y displacement threshold was met, and exceeds x displacement.
      if (speed.y < swipeMinSpeedPixelsPerSecond
          || !fingersTravelSameDirection(recentlyInactivatedPoints, direction)) {
        // Not quick enough or fingers moving different directions.
        return Optional.empty();
      }
    }
    return Optional.of(new Swipe(direction, recentlyInactivatedPoints.size()));
  }

  private void recognizeStationaryModifiers(long eventTime) {
    Optional<PointerWithHistory> moving = getSwipeReferencePointer()
        .filter(pointer -> pointer.momentMovementStarted >= 0);
    if (holdRecognizer == null || moving.isEmpty() || hasCommittedCommand) {
      return;
    }
    long motionStart = moving.get().momentMovementStarted;
    List<PointerWithHistory> modifiers = new ArrayList<>();
    for (PointerWithHistory pointer : activePointers.values()) {
      if (pointer.isHoldInProgress
          || (pointer.distanceMoved() <= tapMaxDistancePixels
              && pointer.momentMadeInitial <= motionStart)) {
        modifiers.add(pointer);
      }
    }
    for (PointerWithHistory pointer : inactivePointers.values()) {
      if (pointer.momentMadeInactive >= eventTime - RECENCY_MAX_MS
          && pointer.momentMadeInactive >= motionStart
          && (pointer.isHoldInProgress
              || (pointer.distanceMoved() <= tapMaxDistancePixels
                  && pointer.momentMadeInitial <= motionStart))) {
        modifiers.add(pointer);
      }
    }
    if (modifiers.isEmpty() || !holdRecognizer.isHoldRecognized(modifiers.size())) {
      return;
    }
    // A stationary finger overlapping a deliberate swipe is a modifier even if the long-press
    // timer has not fired yet. Taps and multi-finger swipes still use their own motion thresholds.
    for (PointerWithHistory pointer : modifiers) {
      if (!pointer.isHoldInProgress) {
        pointer.pointHeld = new PointF(pointer.pointInitial.x, pointer.pointInitial.y);
        pointer.maximumDistanceSinceHold = pointer.maximumDistanceMoved;
        pointer.isHoldInProgress = true;
      }
    }
  }

  private Optional<Direction> getDirection(float xExcess, float yExcess, float xDiff, float yDiff) {
    if (xExcess > 0 && (xExcess > yExcess)) {
      return Optional.of(xDiff < 0 ? Direction.LEFT : Direction.RIGHT);
    }
    if (yExcess > 0 && (yExcess > xExcess)) {
      return Optional.of(yDiff < 0 ? Direction.UP : Direction.DOWN);
    }
    return Optional.empty();
  }

  private boolean fingersTravelSameDirection(
      List<PointerWithHistory> recentlyInactivatedPoints, Direction direction) {
    return !recentlyInactivatedPoints.isEmpty()
        && recentlyInactivatedPoints.stream()
            .allMatch(pointer -> {
              float x = pointer.displacementX();
              float y = pointer.displacementY();
              return !pointer.isHoldInProgress
                  && Utils.isVectorNearlyCardinal(new PointF(x, y), SWIPE_MIN_RATIO)
                  && getDirection(
                          Math.abs(x) - tapMaxDistancePixels,
                          Math.abs(y) - tapMaxDistancePixels, x, y)
                      .filter(direction::equals).isPresent();
            });
  }

  private double getMaximumDistanceMovedAmongInactivePointers() {
    return inactivePointers.values().stream()
        .mapToDouble(PointerWithHistory::distanceMoved)
        .max()
        .orElse(Double.MAX_VALUE);
  }

  private void clearPointerCollections() {
    activePointers.clear();
    inactivePointers.clear();
    isProcessed = false;
    hasCommittedCommand = false;
    lastReleaseTime = 0;
    releaseOrder = 0;
  }

  /** Cancels the current gesture and its delayed holds when the input surface changes. */
  void cancel() {
    handler.removeCallbacksAndMessages(null);
    clearPointerCollections();
  }

  private ImmutableList<PointF> getRecentlyInactivatedCurrentPoints(long eventTime) {
    Range<Long> recentRange = new Range<>(eventTime - RECENCY_MAX_MS, eventTime);
    return inactivePointers.values().stream()
        .filter(
            pointerWithHistory ->
                isAccumulationMode || recentRange.contains(pointerWithHistory.momentMadeInactive))
        .map(pointerWithHistory -> pointerWithHistory.pointCurrent)
        .collect(toImmutableList());
  }

  private ImmutableList<PointF> getSwipeInitialPoints() {
    return getSwipePointsHistory().stream()
        .map(pointerWithHistory -> pointerWithHistory.pointInitial)
        .collect(toImmutableList());
  }

  private ImmutableList<PointerWithHistory> getSwipePointsHistory() {
    // Swipe fingers belong to the whole stroke. The tap recency window must not turn a
    // two-finger stroke into a one-dot swipe when fingers lift at slightly different times.
    return inactivePointers.values().stream()
        .filter(pointer -> !pointer.isHoldInProgress)
        .collect(toImmutableList());
  }

  private Optional<PointerWithHistory> getSwipeReferencePointer() {
    return getSwipePointsHistory().stream().max(
        Comparator.comparingDouble((PointerWithHistory pointer) ->
                Math.max(Math.abs(pointer.displacementX()), Math.abs(pointer.displacementY())))
            .thenComparingLong(pointer -> pointer.releaseOrder));
  }

  private ImmutableList<PointF> getRecentlyReleasedHeldPoints(long eventTime) {
    return inactivePointers.values().stream()
        .filter(pointer -> pointer.isHoldInProgress
            && pointer.momentMadeInactive >= eventTime - RECENCY_MAX_MS)
        .map(pointer -> pointer.pointHeld).collect(toImmutableList());
  }

  private Optional<PointerWithHistory> getLastRecentlyInactivatedPointsHistory(long eventTime) {
    Range<Long> recentRange = new Range<>(eventTime - RECENCY_MAX_MS, eventTime);
    return inactivePointers.values().stream()
        .filter(pointerWithHistory -> recentRange.contains(pointerWithHistory.momentMadeInactive))
        .max(
            Comparator.comparingLong((PointerWithHistory pointer) -> pointer.momentMadeInactive)
                .thenComparingLong(pointer -> pointer.releaseOrder));
  }

  private ImmutableList<PointF> getHeldPoints() {
    return activePointers.values().stream()
        .filter(pointerWithHistory -> pointerWithHistory.isHoldInProgress)
        .map(pointerWithHistory -> pointerWithHistory.pointHeld)
        .collect(toImmutableList());
  }

  private void transferPointerToInactive(int pointerId, long eventTime) {
    PointerWithHistory pointerWithHistory = activePointers.get(pointerId);
    if (pointerWithHistory != null) {
      pointerWithHistory.markReleased(eventTime);
      pointerWithHistory.releaseOrder = ++releaseOrder;
      lastReleaseTime = eventTime;
      inactivePointers.put(pointerId, pointerWithHistory);
      activePointers.remove(pointerId);
    }
  }

  private static class Speed {
    final float x;
    final float y;

    private Speed(float x, float y) {
      this.x = x;
      this.y = y;
    }
  }

  private static class PointerWithHistory {
    final int pointerId;
    final PointF pointInitial;
    final PointF pointCurrent;
    long momentMadeInactive;
    final long momentMadeInitial;
    boolean isHoldInProgress;
    PointF pointHeld;
    long releaseOrder;
    double maximumDistanceMoved;
    double maximumDistanceSinceHold;
    long momentMovementStarted = -1;
    long momentLastMoved;

    private PointerWithHistory(int pointerId, PointF pointInitial, long initialEventTime) {
      this.pointerId = pointerId;
      this.pointInitial = new PointF(pointInitial.x, pointInitial.y);
      this.pointCurrent = new PointF(pointInitial.x, pointInitial.y);
      momentMadeInitial = initialEventTime;
      momentLastMoved = initialEventTime;
    }

    private void markReleased(long eventTime) {
      this.momentMadeInactive = eventTime;
    }

    private void updateCurrentPoint(float x, float y, long eventTime, float motionSlop) {
      if (x != pointCurrent.x || y != pointCurrent.y) {
        momentLastMoved = eventTime;
      }
      pointCurrent.x = x;
      pointCurrent.y = y;
      PointF motionOrigin = isHoldInProgress ? pointHeld : pointInitial;
      if (momentMovementStarted < 0 && Utils.distance(pointCurrent, motionOrigin) > motionSlop) {
        momentMovementStarted = eventTime;
      }
      if (isHoldInProgress) {
        maximumDistanceSinceHold = Math.max(
            maximumDistanceSinceHold, Utils.distance(pointCurrent, pointHeld));
      }
      maximumDistanceMoved =
          Math.max(maximumDistanceMoved, Utils.distance(pointCurrent, pointInitial));
    }

    private double distanceMoved() {
      return maximumDistanceMoved;
    }

    private Speed computeSpeed() {
      double pointerDurationInSeconds = pointerDurationInMillis() / 1000.0;
      if (pointerDurationInSeconds <= 0) {
        return new Speed(0, 0);
      }
      return new Speed(
          (float) Math.abs(displacementX() / pointerDurationInSeconds),
          (float) Math.abs(displacementY() / pointerDurationInSeconds));
    }

    private float displacementX() {
      return pointCurrent.x - pointInitial.x;
    }

    private float displacementY() {
      return pointCurrent.y - pointInitial.y;
    }

    private long pointerDurationInMillis() {
      // Count motion rather than time spent waiting on a point or waiting to lift a finger.
      return momentMovementStarted >= 0
          ? Math.max(1, momentLastMoved - momentMovementStarted)
          : momentMadeInactive - momentMadeInitial;
    }
  }

  @VisibleForTesting
  void testing_set_holdDurationMinMillis(int holdDurationMinMillis) {
    this.longHoldDurationMinMillis = holdDurationMinMillis;
  }

  @VisibleForTesting
  void testing_set_tapMaxDistancePixels(int tapMaxDistancePixels) {
    this.tapMaxDistancePixels = tapMaxDistancePixels;
  }

  @VisibleForTesting
  void testing_set_swipeMinDistance(int swipeMinDistancePixels) {
    this.swipeMinDistancePixels = swipeMinDistancePixels;
  }

  @VisibleForTesting
  void testing_set_swipeMinSpeedPixelsPerSecond(int swipeMinSpeedPixelsPerSecond) {
    this.swipeMinSpeedPixelsPerSecond = swipeMinSpeedPixelsPerSecond;
  }
}
