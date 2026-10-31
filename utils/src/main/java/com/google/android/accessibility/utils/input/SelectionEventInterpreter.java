/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.google.android.accessibility.utils.input;

import static android.view.accessibility.AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED;
import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED;
import static android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
import static com.google.android.accessibility.utils.Role.ROLE_CHECK_BOX;
import static com.google.android.accessibility.utils.StringBuilderUtils.optionalTag;

import android.os.SystemClock;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.AccessibilityEventListener;
import com.google.android.accessibility.utils.AccessibilityEventUtils;
import com.google.android.accessibility.utils.Consumer;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.Role;
import com.google.android.accessibility.utils.StringBuilderUtils;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Intended for interpreting special cases involving selection-type events. */
public final class SelectionEventInterpreter implements AccessibilityEventListener {
  private static final int WINDOW_TRANSITION_PERIOD_MILLISECONDS = 100;
  private static final String TAG = "SelectionInterpreter";
  private static final String SOUND_ALIVE_PACKAGE = "com.sec.android.app.soundalive";

  /** Event types that are handled by SelectionEventInterpreter. */
  private static final int MASK_EVENTS =
      AccessibilityEvent.TYPE_WINDOWS_CHANGED
          | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
          | AccessibilityEvent.TYPE_VIEW_SELECTED
          | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;

  /** Data-structure containing raw-event and interpretation, sent to listeners. */
  public static class Interpretation {
    public final AccessibilityEvent event;
    public final EventId eventId;
    public final boolean isTransitional;
    public final boolean isSelected; // Selected versus de-selected

    public Interpretation(
        AccessibilityEvent event,
        @NonNull EventId eventId,
        boolean isTransitional,
        boolean isSelected) {
      this.event = event;
      this.eventId = eventId;
      this.isTransitional = isTransitional;
      this.isSelected = isSelected;
    }

    @Override
    public String toString() {
      return StringBuilderUtils.joinFields(
          eventId.toString(),
          optionalTag("transition", isTransitional),
          optionalTag("selected", isSelected));
    }
  }

  private long windowChangeTimeInMillis = -1;
  private final List<Consumer<Interpretation>> listeners = new ArrayList<>();

  // SoundAlive repeatedly selects its equalizer preset button without changing it. Replaying
  // feedback for every event interrupts navigation and repeats the focus sound/vibration.
  private final LruCache<AccessibilityNodeInfoCompat, List<Object>> soundAliveButtonSelections =
      new LruCache<>(32);

  public SelectionEventInterpreter() {}

  public void addListener(Consumer<Interpretation> listener) {
    listeners.add(listener);
  }

  @Override
  public int getEventTypes() {
    return MASK_EVENTS;
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event, EventId eventId) {
    int eventType = event.getEventType();
    boolean selected = false;
    @Nullable AccessibilityNodeInfoCompat source = null;

    if (eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
      soundAliveButtonSelections.evictAll();
      windowChangeTimeInMillis = SystemClock.uptimeMillis();
      return;
    } else if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
      soundAliveButtonSelections.evictAll();
      return;
    } else if (eventType == TYPE_WINDOW_CONTENT_CHANGED) {
      // Upon deselection, View.setSelected() only sends TYPE_WINDOW_CONTENT_CHANGED, not
      // TYPE_VIEW_SELECTED.
      if (event.getContentChangeTypes() != CONTENT_CHANGE_TYPE_UNDEFINED) {
        return;
      }
      source = AccessibilityEventUtils.sourceCompat(event);
      if (Role.getRole(source) != ROLE_CHECK_BOX) {
        LogUtils.v(TAG, "Skip ROLE_CHECK_BOX for de-selection");
        return;
      }
      if ((source == null) || source.isSelected()) {
        return; // Only need to check TYPE_WINDOW_CONTENT_CHANGED for de-selection.
      }
    } else if (eventType == TYPE_VIEW_SELECTED) {
      source = AccessibilityEventUtils.sourceCompat(event);
      selected = (source != null) && source.isSelected();
    } else {
      return;
    }

    // TYPE_VIEW_SELECTED events triggered within 100ms of a window change are labeled as
    // transitional.
    boolean transitional = isWithinWindowTransitionPeriod();
    // Transitional events are discarded downstream, so they must not consume the first useful
    // announcement. Suppress the whole duplicate feedback, including its sound and vibration.
    if (eventType == TYPE_VIEW_SELECTED
        && !transitional
        && isRepeatedSoundAliveButtonSelection(event, source)) {
      return;
    }
    Interpretation interpretation = new Interpretation(event, eventId, transitional, selected);
    for (Consumer<Interpretation> listener : listeners) {
      listener.accept(interpretation);
    }
  }

  private boolean isRepeatedSoundAliveButtonSelection(
      AccessibilityEvent event, @Nullable AccessibilityNodeInfoCompat source) {
    if (source == null || !isSoundAliveButton(source)) {
      return false;
    }
    List<String> eventText = new ArrayList<>(event.getText().size());
    for (CharSequence text : event.getText()) {
      eventText.add(snapshotText(text));
    }
    AccessibilityNodeInfoCompat.RangeInfoCompat range = source.getRangeInfo();
    List<Object> state =
        Arrays.asList(
            source.isSelected(),
            source.isChecked(),
            source.isCheckable(),
            source.isEnabled(),
            snapshotText(source.getText()),
            snapshotText(source.getContentDescription()),
            snapshotText(source.getStateDescription()),
            snapshotText(event.getContentDescription()),
            eventText,
            event.isChecked(),
            event.isEnabled(),
            event.getItemCount(),
            event.getFromIndex(),
            event.getToIndex(),
            event.getCurrentItemIndex(),
            range == null
                ? null
                : Arrays.asList(range.getType(), range.getMin(), range.getMax(), range.getCurrent()));
    // Focus itself is announced through its own events. Moving it must not re-enable unchanged
    // selection announcements from the old button and interrupt the new focused control.
    if (state.equals(soundAliveButtonSelections.get(source))) {
      return true;
    }
    soundAliveButtonSelections.put(AccessibilityNodeInfoCompat.obtain(source), state);
    return false;
  }

  private static @Nullable String snapshotText(@Nullable CharSequence text) {
    return text == null ? null : text.toString();
  }

  /** Identifies SoundAlive buttons whose selection events describe equalizer state. */
  public static boolean isSoundAliveButton(@Nullable AccessibilityNodeInfoCompat source) {
    return source != null
        && TextUtils.equals(SOUND_ALIVE_PACKAGE, source.getPackageName())
        && Role.getRole(source) == Role.ROLE_BUTTON;
  }

  @VisibleForTesting
  boolean isWithinWindowTransitionPeriod() {
    long selectedTimeInMillis = SystemClock.uptimeMillis();
    LogUtils.v(
        TAG,
        "TYPE_VIEW_SELECTED time=%d and TYPE_WINDOWS_CHANGED time=%d",
        selectedTimeInMillis,
        windowChangeTimeInMillis);
    return selectedTimeInMillis - windowChangeTimeInMillis <= WINDOW_TRANSITION_PERIOD_MILLISECONDS;
  }
}
