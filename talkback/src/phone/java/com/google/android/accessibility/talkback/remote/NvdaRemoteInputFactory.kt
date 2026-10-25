/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.remote

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.widget.TextView
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.common.BrailleUtils
import com.google.android.accessibility.braille.common.TouchDots
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.brailleime.BrailleInputOptions
import com.google.android.accessibility.brailleime.HeldOrientationTracker
import com.google.android.accessibility.brailleime.LayoutOrientator
import com.google.android.accessibility.brailleime.OrientationMonitor
import com.google.android.accessibility.brailleime.input.BrailleInputView
import com.google.android.accessibility.brailleime.input.BrailleInputView.CalibrationTriggeredType
import com.google.android.accessibility.brailleime.input.BrailleInputView.FingersPattern
import com.google.android.accessibility.brailleime.input.DotsOrientation
import com.google.android.accessibility.brailleime.input.DotHoldSwipe
import com.google.android.accessibility.brailleime.input.Swipe
import com.google.android.accessibility.brailleime.R as BrailleR

object NvdaRemoteInputFactory {
  fun dotCount(context: Context): Int = BrailleUserPreferences.getCurrentTypingLanguageType(context).dotCount

  fun create(context: Context, size: Size, listener: NvdaRemoteInput.Listener): NvdaRemoteInput =
    RemoteBrailleView(context, size, listener)
}

/** Reuses CAN Talk's calibrated dots and orientation rules without starting or taking over its IME. */
private class RemoteBrailleView(
  context: Context, size: Size, private val listener: NvdaRemoteInput.Listener,
) : NvdaRemoteInput(context), BrailleInputView.Callback {
  private var closed = false
  private var consumedHold = false
  private var heldDots = ""
  private var heldOrientation = HeldOrientationTracker.getLastHeld()
  private val orientationListener: OrientationEventListener
  private val input: BrailleInputView
  private val description: TextView
  private val layout: LayoutOrientator

  init {
    OrientationMonitor.init(context)
    orientationListener = object : OrientationEventListener(context) {
      override fun onOrientationChanged(degrees: Int) {
        if (degrees != ORIENTATION_UNKNOWN) heldOrientation = OrientationMonitor.toOrientation(context, degrees)
      }
    }
    val options = BrailleInputOptions.builder()
      .setBrailleType(BrailleUserPreferences.getCurrentTypingLanguageType(context))
      .setReverseDots(BrailleUserPreferences.readReverseDotsMode(context))
      .setFlipDotsVertically(BrailleUserPreferences.readFlipDotsVertically(context)).build()
    input = BrailleInputView(context, this, size, options,
      BrailleUserPreferences.readLayoutMode(context) == TouchDots.TABLETOP)
    input.setAccumulationMode(BrailleUserPreferences.readAccumulateMode(context))
    addView(input, LayoutParams(-1, -1))
    description = TextView(context).apply {
      setTextColor(Color.WHITE)
      textSize = 18f
      gravity = Gravity.CENTER
      setPadding(16, 16, 16, 8)
    }
    addView(description, LayoutParams(-1, -2, Gravity.TOP))
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    layout = LayoutOrientator(context, object : LayoutOrientator.LayoutOrientatorCallback {
      override fun useSensorsToDetectLayout() = BrailleUserPreferences.readLayoutMode(context) == TouchDots.AUTO_DETECT
      override fun uprightFacesUser(): Boolean {
        val away = BrailleUserPreferences.readTabletHeldUpFacesAway(context)
        return !away && DotsOrientation.heldFacingUser(HeldOrientationTracker.getLastHeld(),
          BrailleUtils.isPhoneSizedDevice(resources), away)
      }
      override fun onDetectionChanged(tabletop: Boolean, first: Boolean) {
        if (!closed) input.setTabletopMode(tabletop)
      }
    })
  }

  override fun setDescription(text: String) { description.text = text }
  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    orientationListener.enable()
    layout.startIfNeeded()
  }
  override fun onDetachedFromWindow() { close(); super.onDetachedFromWindow() }
  override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
    super.onSizeChanged(width, height, oldWidth, oldHeight)
    if (width > 0 && height > 0 && !closed) input.onOrientationChanged(resources.configuration.orientation, Size(width, height))
  }
  override fun onConfigurationChanged(configuration: Configuration) {
    super.onConfigurationChanged(configuration)
    if (!closed && width > 0 && height > 0) input.onOrientationChanged(configuration.orientation, Size(width, height))
  }
  override fun close() {
    if (closed) return
    closed = true
    orientationListener.disable()
    layout.stop()
    val now = SystemClock.uptimeMillis()
    val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
    input.onTouchEvent(cancel)
    cancel.recycle()
  }
  override fun onBrailleProduced(cell: BrailleCharacter): String {
    if (!closed && !consumedHold) listener.onGesture(NvdaRemoteGesture(dots = cell.toString()))
    return "" // Never show typed text or password characters in the local caption.
  }
  override fun onSwipeProduced(swipe: Swipe): Boolean {
    if (!closed) listener.onGesture(gesture(swipe))
    return true
  }
  override fun onDotHoldAndDotSwipe(swipe: DotHoldSwipe): Boolean {
    if (!closed) listener.onGesture(gesture(swipe.swipe, swipe.heldDots.toString()))
    return true
  }
  private fun gesture(swipe: Swipe, held: String = "") = NvdaRemoteGesture(direction = swipe.direction.name,
    fingers = swipe.touchCount, swipedDot = swipe.swipedDot, held = held)
  // Marking a standalone hold as handled discards the cell when the fingers lift.
  // Keep it available for release; an actual hold-and-swipe consumes it separately.
  override fun onHoldProduced(count: Int) = false
  override fun onDotsHeld(dots: BrailleCharacter, count: Int): Boolean {
    if (closed) return false
    heldDots = dots.toString()
    val handled = listener.onHold(heldDots)
    consumedHold = consumedHold || handled
    return handled
  }
  override fun onHeldDotsChanged(dots: BrailleCharacter) {
    if (closed) return
    val current = dots.toString()
    if (current != heldDots) {
      heldDots = current
      listener.onHeldDotsChanged(current)
    }
  }
  override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    if (event.actionMasked == MotionEvent.ACTION_DOWN) consumedHold = false
    return super.dispatchTouchEvent(event)
  }
  override fun onInvalidGesture(): Boolean {
    if (!closed) listener.onAnnouncement(context.getString(com.google.android.accessibility.talkback.R.string.nvda_remote_invalid_gesture))
    return true
  }
  override fun wasHeldUprightInScreenAway() = layout.wasHeldUprightInScreenAway()
  override fun getScreenAwayOrientation() = heldOrientation
  override fun isCalibrationHoldRecognized(twoStep: Boolean, count: Int): Boolean {
    val dots = input.brailleDotCount
    return count in 5..dots || (twoStep && count == dots / 2)
  }
  override fun onCalibration(type: CalibrationTriggeredType, fingers: FingersPattern): Boolean {
    if (closed) return true
    consumedHold = true
    heldDots = ""
    listener.onHeldDotsChanged("")
    val eight = input.brailleDotCount == 8
    if (fingers == (if (eight) FingersPattern.EIGHT_FINGERS else FingersPattern.SIX_FINGERS) ||
        fingers == (if (eight) FingersPattern.REMAINING_FOUR_FINGERS else FingersPattern.REMAINING_THREE_FINGERS)) {
      input.savePoints()
      listener.onAnnouncement(context.getString(BrailleR.string.calibration_finish_announcement))
    } else announceHand(fingers != FingersPattern.FIRST_THREE_FINGERS && fingers != FingersPattern.FIRST_FOUR_FINGERS)
    return true
  }
  override fun onTwoStepCalibrationRetry(first: Boolean) { if (!closed) announceHand(first) }
  private fun announceHand(first: Boolean) {
    val reverse = BrailleUserPreferences.readReverseDotsMode(context)
    listener.onAnnouncement(context.getString(if (input.brailleDotCount == 8)
      BrailleR.string.calibration_hold_left_or_right_four_finger_announcement
      else BrailleR.string.calibration_hold_left_or_right_three_finger_announcement,
      context.getString(if (first != reverse) BrailleR.string.left_hand else BrailleR.string.right_hand)))
  }
  override fun onCalibrationFailed(type: CalibrationTriggeredType) {
    if (!closed) listener.onAnnouncement(context.getString(BrailleR.string.calibration_fail_announcement))
  }
}
