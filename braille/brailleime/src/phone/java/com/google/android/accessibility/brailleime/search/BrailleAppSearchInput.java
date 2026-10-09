/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.brailleime.search;

import android.content.Context;
import android.graphics.Color;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.google.android.accessibility.braille.common.BrailleUserPreferences;
import com.google.android.accessibility.braille.common.BrailleUtils;
import com.google.android.accessibility.braille.common.TouchDots;
import com.google.android.accessibility.braille.interfaces.BrailleCharacter;
import com.google.android.accessibility.braille.translate.BrailleTranslator;
import com.google.android.accessibility.brailleime.BrailleImeTypingSounds;
import com.google.android.accessibility.brailleime.BrailleInputOptions;
import com.google.android.accessibility.brailleime.HeldOrientationTracker;
import com.google.android.accessibility.brailleime.LayoutOrientator;
import com.google.android.accessibility.brailleime.OrientationMonitor;
import com.google.android.accessibility.brailleime.R;
import com.google.android.accessibility.brailleime.input.BrailleInputView;
import com.google.android.accessibility.brailleime.input.BrailleInputView.CalibrationTriggeredType;
import com.google.android.accessibility.brailleime.input.BrailleInputView.FingersPattern;
import com.google.android.accessibility.brailleime.input.DotsOrientation;
import com.google.android.accessibility.brailleime.input.DotHoldSwipe;
import com.google.android.accessibility.brailleime.input.Swipe;

/** Hosts the existing braille dots without opening an IME or sending text to the underlying app. */
public final class BrailleAppSearchInput extends FrameLayout implements BrailleInputView.Callback {
  public enum Action { PREVIOUS, NEXT, OPEN, CLOSE, HELP, SWITCH_MODE }

  public interface Listener {
    void onQueryChanged(String query);
    void onAction(Action action);
    void onAnnouncement(String text);
  }

  private final Listener listener;
  private final BrailleSearchQuery query;
  private final BrailleInputView input;
  private final TextView description;
  private final LayoutOrientator layout;
  private final OrientationEventListener orientationListener;
  private OrientationMonitor.Orientation heldOrientation = HeldOrientationTracker.getLastHeld();
  private boolean closed;

  public BrailleAppSearchInput(Context context, Size size, Listener listener) {
    this(context, size, listener, createQuery(context));
  }

  private static BrailleSearchQuery createQuery(Context context) {
    var code = BrailleUserPreferences.readCurrentActiveInputCodeAndCorrect(context);
    BrailleTranslator translator = BrailleUserPreferences.readTranslatorFactory(context)
        .create(context, code.name(), BrailleUserPreferences.readContractedMode(context)
            && code.isSupportsContracted(context));
    return new BrailleSearchQuery(translator::translateToPrintPartial);
  }

  /** Also allows a non-native translator in gesture and lifecycle regression tests. */
  public BrailleAppSearchInput(Context context, Size size, Listener listener, BrailleSearchQuery query) {
    super(context);
    this.listener = listener;
    this.query = query;
    OrientationMonitor.init(context);
    orientationListener = new OrientationEventListener(context) {
      @Override public void onOrientationChanged(int degree) {
        heldOrientation = OrientationMonitor.toOrientation(context, degree);
      }
    };
    BrailleInputOptions options = BrailleInputOptions.builder()
        .setBrailleType(BrailleUserPreferences.getCurrentTypingLanguageType(context))
        .setReverseDots(BrailleUserPreferences.readReverseDotsMode(context))
        .setFlipDotsVertically(BrailleUserPreferences.readFlipDotsVertically(context)).build();
    input = new BrailleInputView(context, this, size, options,
        BrailleUserPreferences.readLayoutMode(context) == TouchDots.TABLETOP);
    input.setAccumulationMode(BrailleUserPreferences.readAccumulateMode(context));
    addView(input, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    description = new TextView(context);
    description.setTextColor(Color.WHITE);
    description.setTextSize(18);
    description.setGravity(Gravity.CENTER);
    description.setPadding(20, 30, 20, 10);
    addView(description, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
        Gravity.TOP));
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    layout = new LayoutOrientator(context, new LayoutOrientator.LayoutOrientatorCallback() {
      @Override public boolean useSensorsToDetectLayout() {
        return BrailleUserPreferences.readLayoutMode(context) == TouchDots.AUTO_DETECT;
      }
      @Override public boolean uprightFacesUser() {
        boolean facesAway = BrailleUserPreferences.readTabletHeldUpFacesAway(context);
        return !facesAway && DotsOrientation.heldFacingUser(HeldOrientationTracker.getLastHeld(),
            BrailleUtils.isPhoneSizedDevice(getResources()), facesAway);
      }
      @Override public void onDetectionChanged(boolean tabletop, boolean first) {
        if (!closed) input.setTabletopMode(tabletop);
      }
    });
  }

  public void setDescription(String text) { description.setText(text); }

  @Override protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    orientationListener.enable();
    layout.startIfNeeded();
  }

  @Override protected void onDetachedFromWindow() {
    close();
    super.onDetachedFromWindow();
  }

  public void close() {
    if (closed) return;
    closed = true;
    orientationListener.disable();
    layout.stop();
    MotionEvent cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
    input.onTouchEvent(cancel);
    cancel.recycle();
    query.clear();
  }

  @Override public String onBrailleProduced(BrailleCharacter cell) {
    if (closed) return "";
    String text = query.append(cell);
    BrailleImeTypingSounds.INSTANCE.play(getContext(), BrailleImeTypingSounds.Key.CHARACTER, false);
    listener.onQueryChanged(text);
    return text;
  }

  /** Gestures retain the braille view's orientation rules, regardless of customized typing gestures. */
  @Override public boolean onSwipeProduced(Swipe swipe) {
    if (closed) return true;
    int fingers = swipe.getTouchCount();
    Swipe.Direction direction = swipe.getDirection();
    if (fingers == 1) {
      switch (direction) {
        case UP -> listener.onAction(Action.PREVIOUS);
        case DOWN -> listener.onAction(Action.NEXT);
        case LEFT -> {
          listener.onQueryChanged(query.delete());
          BrailleImeTypingSounds.INSTANCE.play(getContext(), BrailleImeTypingSounds.Key.DELETE, false);
        }
        case RIGHT -> {
          listener.onQueryChanged(query.append(new BrailleCharacter()));
          BrailleImeTypingSounds.INSTANCE.play(getContext(), BrailleImeTypingSounds.Key.SPACE, false);
        }
      }
      return true;
    }
    if (fingers == 2 && direction == Swipe.Direction.RIGHT) listener.onAction(Action.OPEN);
    else if (fingers == 2 && (direction == Swipe.Direction.LEFT || direction == Swipe.Direction.DOWN))
      listener.onAction(Action.CLOSE);
    else if (fingers == 3 && direction == Swipe.Direction.UP) listener.onAction(Action.HELP);
    else if (fingers == 3 && (direction == Swipe.Direction.LEFT || direction == Swipe.Direction.RIGHT))
      listener.onAction(Action.SWITCH_MODE);
    return true;
  }

  @Override public boolean onDotHoldAndDotSwipe(DotHoldSwipe swipe) { return true; }
  @Override public boolean onHoldProduced(int count) { return true; }
  @Override public boolean wasHeldUprightInScreenAway() {
    return layout == null || layout.wasHeldUprightInScreenAway();
  }

  @Override public OrientationMonitor.Orientation getScreenAwayOrientation() { return heldOrientation; }

  @Override public boolean isCalibrationHoldRecognized(boolean twoStep, int count) {
    int dots = input.getBrailleDotCount();
    return count >= 5 && count <= dots || twoStep && count == dots / 2;
  }

  @Override public boolean onCalibration(CalibrationTriggeredType type, FingersPattern fingers) {
    if (closed) return true;
    boolean eight = input.getBrailleDotCount() == 8;
    if (fingers == (eight ? FingersPattern.EIGHT_FINGERS : FingersPattern.SIX_FINGERS)
        || fingers == (eight ? FingersPattern.REMAINING_FOUR_FINGERS
            : FingersPattern.REMAINING_THREE_FINGERS)) {
      input.savePoints();
      listener.onAnnouncement(getContext().getString(R.string.calibration_finish_announcement));
    } else {
      boolean first = fingers != FingersPattern.FIRST_THREE_FINGERS
          && fingers != FingersPattern.FIRST_FOUR_FINGERS;
      announceCalibrationHand(first);
    }
    return true;
  }

  @Override public void onTwoStepCalibrationRetry(boolean first) {
    if (!closed) announceCalibrationHand(first);
  }

  private void announceCalibrationHand(boolean first) {
    boolean reverse = BrailleUserPreferences.readReverseDotsMode(getContext());
    listener.onAnnouncement(getContext().getString(input.getBrailleDotCount() == 8
        ? R.string.calibration_hold_left_or_right_four_finger_announcement
        : R.string.calibration_hold_left_or_right_three_finger_announcement,
        getContext().getString(first != reverse ? R.string.left_hand : R.string.right_hand)));
  }

  @Override public void onCalibrationFailed(CalibrationTriggeredType type) {
    if (!closed) listener.onAnnouncement(getContext().getString(R.string.calibration_fail_announcement));
  }
}
