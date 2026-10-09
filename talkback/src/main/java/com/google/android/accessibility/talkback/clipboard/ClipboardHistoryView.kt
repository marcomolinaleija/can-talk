/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import kotlin.math.abs

/** A spoken browser: gestures work anywhere on its surface. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ClipboardHistoryView(context: Context, private val action: (Action) -> Unit) : TextView(context) {
  enum class Action { PREVIOUS, NEXT, PREVIOUS_CATEGORY, NEXT_CATEGORY, COPY, PASTE, FAVORITE, CLOSE }
  private var startX = 0f
  private var startY = 0f
  private var twoX = 0f
  private var twoY = 0f
  private var twoEndX = 0f
  private var twoEndY = 0f
  private var fingers = 0
  private val swipeDistance = 48f * resources.displayMetrics.density
  private val slop = ViewConfiguration.get(context).scaledTouchSlop
  private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
    override fun onDown(e: MotionEvent) = true
    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
      action(Action.COPY)
      return true
    }
    override fun onDoubleTap(e: MotionEvent) = true
    override fun onDoubleTapEvent(e: MotionEvent): Boolean {
      if (e.actionMasked == MotionEvent.ACTION_UP) action(Action.PASTE)
      return true
    }
    override fun onLongPress(e: MotionEvent) { action(Action.FAVORITE) }
  })

  init {
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    gravity = Gravity.CENTER
    setBackgroundColor(Color.rgb(20, 24, 30))
    setTextColor(Color.WHITE)
    textSize = 22f
    maxLines = 18
    ellipsize = TextUtils.TruncateAt.END
    val padding = (24 * resources.displayMetrics.density).toInt()
    setPadding(padding, padding, padding, padding)
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        fingers = 1
        startX = event.x
        startY = event.y
      }
      MotionEvent.ACTION_POINTER_DOWN -> {
        fingers = maxOf(fingers, event.pointerCount)
        cancelDetector(event)
        if (event.pointerCount == 2) {
          twoX = (event.getX(0) + event.getX(1)) / 2
          twoY = (event.getY(0) + event.getY(1)) / 2
          twoEndX = twoX
          twoEndY = twoY
        }
        return true
      }
      MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_UP -> {
        if (fingers >= 2) {
          if (event.pointerCount == 2) {
            twoEndX = (event.getX(0) + event.getX(1)) / 2
            twoEndY = (event.getY(0) + event.getY(1)) / 2
          }
          return true
        }
        // Cancel pending taps as soon as a swipe begins, even when it is very slow.
        if (abs(event.x - startX) > slop || abs(event.y - startY) > slop) {
          cancelDetector(event)
          return true
        }
      }
      MotionEvent.ACTION_UP -> {
        if (fingers >= 2) {
          if (fingers == 2 && twoEndX - twoX < -swipeDistance
              && abs(twoEndX - twoX) > abs(twoEndY - twoY) * 1.2f) action(Action.CLOSE)
          return true
        }
        val dx = event.x - startX
        val dy = event.y - startY
        if (maxOf(abs(dx), abs(dy)) >= swipeDistance) {
          cancelDetector(event)
          if (abs(dx) > abs(dy) * 1.2f) action(if (dx < 0) Action.PREVIOUS else Action.NEXT)
          else if (abs(dy) > abs(dx) * 1.2f) action(if (dy < 0) Action.PREVIOUS_CATEGORY else Action.NEXT_CATEGORY)
          return true
        }
      }
      MotionEvent.ACTION_CANCEL -> { cancelDetector(event); return true }
    }
    detector.onTouchEvent(event)
    return true
  }

  private fun cancelDetector(event: MotionEvent) {
    val cancel = MotionEvent.obtain(event)
    cancel.action = MotionEvent.ACTION_CANCEL
    detector.onTouchEvent(cancel)
    cancel.recycle()
  }

  fun cancelPendingTouches() {
    val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
    cancelDetector(event)
    event.recycle()
  }
}
