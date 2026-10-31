/* Copyright 2026 CAN Talk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.utils.input;

import static com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED;
import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Replays SoundAlive's unchanged button selections without recording real screen text. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 34})
public class SelectionEventInterpreterSoundAliveTest {
  private SelectionEventInterpreter interpreter;
  private int announcements;

  @Before
  public void setUp() {
    interpreter = new SelectionEventInterpreter();
    interpreter.addListener(value -> {
      if (!value.isTransitional) {
        announcements++;
      }
    });
    advance(200);
  }

  private static void advance(long millis) {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
  }

  private static AccessibilityNodeInfo node(String className, String packageName) {
    AccessibilityNodeInfo source =
        AccessibilityNodeInfo.obtain(new View(RuntimeEnvironment.getApplication()));
    source.setClassName(className);
    source.setPackageName(packageName);
    source.setText("Synthetic preset");
    source.setSelected(true);
    source.setEnabled(true);
    source.setAccessibilityFocused(true);
    return source;
  }

  private static AccessibilityNodeInfo button() {
    return node("android.widget.Button", "com.sec.android.app.soundalive");
  }

  private void select(AccessibilityNodeInfo source) {
    select(source, "Synthetic announcement");
  }

  private void select(AccessibilityNodeInfo source, CharSequence text) {
    AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SELECTED);
    event.setEventTime(SystemClock.uptimeMillis());
    if (source != null) {
      event.setClassName(source.getClassName());
      event.setPackageName(source.getPackageName());
      shadowOf(event).setSourceNode(source);
    }
    event.getText().add(text);
    interpreter.onAccessibilityEvent(event, EVENT_ID_UNTRACKED);
  }

  private void windowEvent(int type) {
    interpreter.onAccessibilityEvent(AccessibilityEvent.obtain(type), EVENT_ID_UNTRACKED);
  }

  @Test
  public void unchangedButtonFloodProducesOneAnnouncementEvenAfterSeveralSeconds() {
    AccessibilityNodeInfo source = button();
    for (int i = 0; i < 100; i++) {
      select(source);
      advance(40);
    }
    assertEquals(1, announcements);
  }

  @Test
  public void interleavedUnchangedButtonsDoNotEvadeTheFilter() {
    AccessibilityNodeInfo first = button();
    AccessibilityNodeInfo second = button();
    for (int i = 0; i < 20; i++) {
      select(first);
      select(second);
    }
    assertEquals(2, announcements);
  }

  @Test
  public void movingFocusDoesNotReenableTheOldButtonsAnnouncements() {
    AccessibilityNodeInfo source = button();
    select(source);
    source.setAccessibilityFocused(false);
    windowEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    windowEvent(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START);
    select(source);
    assertEquals(1, announcements);
  }

  @Test
  public void changedSelectionStillAnnounces() {
    AccessibilityNodeInfo source = button();
    select(source);
    source.setSelected(false);
    select(source);
    source.setSelected(true);
    select(source);
    assertEquals(3, announcements);
  }

  @Test
  public void changedCheckStateStillAnnounces() {
    AccessibilityNodeInfo source = button();
    select(source);
    source.setChecked(true);
    select(source);
    assertEquals(2, announcements);
  }

  @Test
  public void changedValueDescriptionStillAnnounces() {
    AccessibilityNodeInfo source = button();
    select(source);
    androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(source)
        .setStateDescription("Synthetic changed value");
    select(source);
    assertEquals(2, announcements);
  }

  @Test
  public void changedNodeLabelStillAnnounces() {
    AccessibilityNodeInfo source = button();
    select(source);
    source.setText("Another synthetic preset");
    select(source);
    assertEquals(2, announcements);
  }

  @Test
  public void changedContentDescriptionStillAnnounces() {
    AccessibilityNodeInfo source = button();
    select(source);
    source.setContentDescription("Another synthetic description");
    select(source);
    assertEquals(2, announcements);
  }

  @Test
  public void changedEventTextStillAnnouncesAndSnapshotsMutableText() {
    AccessibilityNodeInfo source = button();
    SpannableStringBuilder text = new SpannableStringBuilder("First synthetic value");
    select(source, text);
    text.replace(0, text.length(), "Second synthetic value");
    select(source, text);
    assertEquals(2, announcements);
  }

  @Test
  public void windowsChangedAllowsTheInitialSelectionAgain() {
    AccessibilityNodeInfo source = button();
    select(source);
    windowEvent(AccessibilityEvent.TYPE_WINDOWS_CHANGED);
    // Events ignored during a transition must not consume the first useful announcement.
    select(source);
    advance(150);
    select(source);
    select(source);
    assertEquals(2, announcements);
  }

  @Test
  public void windowStateChangedAllowsTheInitialSelectionAgain() {
    AccessibilityNodeInfo source = button();
    select(source);
    windowEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    select(source);
    assertEquals(2, announcements);
  }

  @Test
  public void otherApplicationsKeepTheirSelectionFeedback() {
    AccessibilityNodeInfo source = node("android.widget.Button", "fixture.other.app");
    select(source);
    select(source);
    assertEquals(2, announcements);
  }

  @Test
  public void slidersKeepTheirValueFeedback() {
    AccessibilityNodeInfo source =
        node("android.widget.SeekBar", "com.sec.android.app.soundalive");
    select(source);
    select(source);
    assertEquals(2, announcements);
  }

  @Test
  public void progressBarsKeepTheirValueFeedback() {
    AccessibilityNodeInfo source =
        node("android.widget.ProgressBar", "com.sec.android.app.soundalive");
    select(source);
    select(source);
    assertEquals(2, announcements);
  }

  @Test
  public void missingSourceKeepsTheExistingBehavior() {
    select(null);
    select(null);
    assertEquals(2, announcements);
  }
}
