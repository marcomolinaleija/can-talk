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

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.annotation.RequiresApi;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.gestures.GestureManifold.GestureConfigProvider;

/**
 * Matches a prolonged single-finger tap and hold without blocking normal touch exploration.
 * Only activates when enabled by {@link GestureConfigProvider#enableOneFingerTapAndHold()}.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class SingleFingerTapAndHold extends GestureMatcher {

  private final GestureConfigProvider configProvider;
  private float holdSlop;
  private float baseX;
  private float baseY;
  private boolean tracking;

  SingleFingerTapAndHold(
      Context context,
      int gesture,
      GestureMatcher.StateChangeListener listener,
      GestureConfigProvider configProvider,
      GestureMatcher.AnalyticsEventLogger logger) {
    super(gesture, new Handler(context.getMainLooper()), listener, logger);
    this.configProvider = configProvider;
    initializeParameters(context);
    clear();
  }

  private void initializeParameters(Context context) {
    holdSlop = ViewConfiguration.get(context).getScaledTouchSlop() * 2.5f;
  }

  @Override
  public void onConfigurationChanged(Context context) {
    initializeParameters(context);
  }

  @Override
  public void clear() {
    baseX = Float.NaN;
    baseY = Float.NaN;
    tracking = false;
    super.clear();
  }

  @Override
  protected void onDown(EventId eventId, MotionEvent event) {
    if (tracking || !configProvider.enableOneFingerTapAndHold()) {
      cancelGesture(event);
      return;
    }
    tracking = true;
    baseX = event.getX();
    baseY = event.getY();
    // Stay in STATE_CLEAR so normal touch exploration still works unless the finger remains
    // stationary for the full prolonged hold timeout.
    completeAfter(GestureConfiguration.PROLONGED_HOLD_TIMEOUT_MS, eventId, event);
  }

  @Override
  protected void onMove(EventId eventId, MotionEvent event) {
    if (!tracking) {
      return;
    }
    final double moveDelta = Math.hypot(event.getX() - baseX, event.getY() - baseY);
    if (moveDelta > holdSlop) {
      cancelGesture(event);
    }
  }

  @Override
  protected void onUp(EventId eventId, MotionEvent event) {
    cancelGesture(event);
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
    return "1-Finger Tap and prolonged hold";
  }
}
