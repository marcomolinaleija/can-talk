/* Copyright 2026 CAN Talk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.interpreters;

import static com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.ActorState;
import com.google.android.accessibility.talkback.ActorStateWritable;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.compositor.GlobalVariables;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.FocusFinder;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.input.SelectionEventInterpreter;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Exercises both independent routes that consume SoundAlive's selection events. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 34})
public class InputFocusInterpreterSoundAliveTest {
  private TalkBackService service;
  private InputFocusInterpreter interpreter;
  private AccessibilityNodeInfoCompat focused;
  private int focusRequests;

  @Before
  public void setUp() {
    service = Robolectric.buildService(TalkBackService.class).get();
    FormFactorUtils.initialize(service);
    interpreter = new InputFocusInterpreter((id, event, node) -> {
      focusRequests++;
      focused = node;
    }, new FocusFinder(service), new GlobalVariables(service, null, null, null));
    interpreter.setActorState(new ActorState(new ActorStateWritable(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null)));
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
  }

  private AccessibilityNodeInfo node(String className, String packageName) {
    AccessibilityNodeInfo result = AccessibilityNodeInfo.obtain(new View(service));
    result.setClassName(className);
    result.setPackageName(packageName);
    result.setText("Synthetic control");
    result.setSelected(true);
    result.setEnabled(true);
    result.setVisibleToUser(true);
    result.setImportantForAccessibility(true);
    result.setFocusable(true);
    result.setClickable(true);
    result.setBoundsInScreen(new Rect(0, 0, 100, 100));
    return result;
  }

  private AccessibilityNodeInfo scrollItem(String className, String packageName) {
    AccessibilityNodeInfo source = node(className, packageName);
    AccessibilityNodeInfo parent = node("android.widget.ListView", packageName);
    parent.setScrollable(true);
    shadowOf(parent).addChild(source);
    AccessibilityNodeInfoCompat compat = AccessibilityNodeInfoCompat.wrap(source);
    assertTrue(AccessibilityNodeInfoUtils.isTopLevelScrollItem(compat));
    assertTrue(AccessibilityNodeInfoUtils.shouldFocusNode(compat));
    return source;
  }

  private static AccessibilityEvent event(int type, AccessibilityNodeInfo source) {
    AccessibilityEvent event = AccessibilityEvent.obtain(type);
    if (source != null) {
      event.setClassName(source.getClassName());
      event.setPackageName(source.getPackageName());
      shadowOf(event).setSourceNode(source);
    }
    event.getText().add("Synthetic preset announcement");
    return event;
  }

  private void select(AccessibilityNodeInfo source) {
    interpreter.onAccessibilityEvent(
        event(AccessibilityEvent.TYPE_VIEW_SELECTED, source), EVENT_ID_UNTRACKED);
  }

  @Test
  public void soundAlivePresetSelectionDoesNotRequestFocus() {
    select(scrollItem("android.widget.Button", "com.sec.android.app.soundalive"));
    assertEquals(0, focusRequests);
  }

  @Test
  public void oldPresetCannotStealFocusFromAnotherControl() {
    AccessibilityNodeInfo source =
        scrollItem("android.widget.Button", "com.sec.android.app.soundalive");
    AccessibilityNodeInfoCompat other = AccessibilityNodeInfoCompat.wrap(
        node("android.widget.SeekBar", "com.sec.android.app.soundalive"));
    focused = other;
    for (int i = 0; i < 20; i++) {
      select(source);
    }
    assertSame(other, focused);
    assertEquals(0, focusRequests);
  }

  @Test
  public void bothSelectionConsumersKeepTheNewFocusAndAvoidRepeatedFeedback() {
    AccessibilityNodeInfo source =
        scrollItem("android.widget.Button", "com.sec.android.app.soundalive");
    AccessibilityNodeInfoCompat other = AccessibilityNodeInfoCompat.wrap(
        node("android.widget.SeekBar", "com.sec.android.app.soundalive"));
    focused = other;
    int[] announcements = {0};
    SelectionEventInterpreter selection = new SelectionEventInterpreter();
    selection.addListener(value -> {
      if (!value.isTransitional) {
        announcements[0]++;
      }
    });
    for (int i = 0; i < 100; i++) {
      AccessibilityEvent selected = event(AccessibilityEvent.TYPE_VIEW_SELECTED, source);
      interpreter.onAccessibilityEvent(selected, EVENT_ID_UNTRACKED);
      selection.onAccessibilityEvent(selected, EVENT_ID_UNTRACKED);
    }
    assertEquals(1, announcements[0]);
    assertEquals(0, focusRequests);
    assertSame(other, focused);
  }

  @Test
  public void genuineKeyboardInputFocusOnSoundAliveStillWorks() {
    AccessibilityNodeInfo source =
        scrollItem("android.widget.Button", "com.sec.android.app.soundalive");
    source.setFocused(true);
    interpreter.onAccessibilityEvent(
        event(AccessibilityEvent.TYPE_VIEW_FOCUSED, source), EVENT_ID_UNTRACKED);
    assertEquals(1, focusRequests);
    assertEquals(AccessibilityNodeInfoCompat.wrap(source), focused);
  }

  @Test
  public void selectedListButtonsInOtherAppsStillSynchronize() {
    select(scrollItem("android.widget.Button", "fixture.other.app"));
    assertEquals(1, focusRequests);
  }

  @Test
  public void sliderSelectionKeepsItsExistingFocusBehavior() {
    select(scrollItem("android.widget.SeekBar", "com.sec.android.app.soundalive"));
    assertEquals(1, focusRequests);
  }

  @Test
  public void unselectedListItemDoesNotRequestFocus() {
    AccessibilityNodeInfo source = scrollItem("android.widget.Button", "fixture.other.app");
    source.setSelected(false);
    select(source);
    assertEquals(0, focusRequests);
  }

  @Test
  public void sourceOutsideAListDoesNotRequestFocus() {
    select(node("android.widget.Button", "fixture.other.app"));
    assertEquals(0, focusRequests);
  }

  @Test
  public void missingSourceDoesNotRequestFocus() {
    select(null);
    assertEquals(0, focusRequests);
  }
}
