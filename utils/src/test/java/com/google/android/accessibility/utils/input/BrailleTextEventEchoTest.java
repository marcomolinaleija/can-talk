/*
 * Copyright 2026 CAN Talk contributors
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

import static com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED;
import static com.google.android.accessibility.utils.input.TextEventFilter.PREF_ECHO_CHARACTERS_AND_WORDS;
import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.google.android.accessibility.utils.monitor.InputModeTracker;
import com.google.android.accessibility.utils.output.ActorStateProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Regression tests for Braille keyboard text and word echo through TextEventFilter/Interpreter. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BrailleTextEventEchoTest {
  private static final String PACKAGE_NAME = "com.whatsapp";

  private Context context;
  private TextEventFilter filter;
  private TextEventInterpreter interpreter;
  private List<TextEventInterpretation> received;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    TextEventHistory history = new TextEventHistory();
    TextCursorTracker cursorTracker = new TextCursorTracker();
    filter = new TextEventFilter(context, cursorTracker, history);
    interpreter =
        new TextEventInterpreter(
            context,
            cursorTracker,
            new InputModeTracker(),
            history,
            new ActorStateProvider(),
            /* voiceActionDelegate= */ null,
            /* voiceDictationDelegate= */ null,
            filter,
            /* cacheAndDropFrequentEvents= */ true,
            /* readLineWhenMoveToAdjacentLineByUpDownKey= */ false);
    received = new ArrayList<>();
    interpreter.addListener(
        item -> {
          if (item.interpretation != null) {
            received.add(item.interpretation);
          }
        });
  }

  @Test
  public void rapidBrailleTextChangesEmitEveryCharacterAndWord() {
    filter.setBrailleKeyboardEchoReader(() -> OptionalInt.of(PREF_ECHO_CHARACTERS_AND_WORDS));

    sendTextChange("", "h", 0, 0, 1, 1000L, /* source= */ null);
    sendTextChange("h", "ho", 1, 0, 1, 1030L, /* source= */ null);
    sendTextChange("ho", "hol", 2, 0, 1, 1060L, /* source= */ null);
    sendTextChange("hol", "hola", 3, 0, 1, 1090L, /* source= */ null);
    sendTextChange("hola", "hola ", 4, 0, 1, 1120L, /* source= */ null);

    assertEquals(5, received.size());
    List<String> added = new ArrayList<>();
    for (TextEventInterpretation interpretation : received) {
      assertEquals(TextEventInterpretation.TEXT_ADD, interpretation.getEvent());
      added.add(interpretation.getAddedText().toString());
    }
    assertEquals(Arrays.asList("h", "o", "l", "a", " "), added);
    assertEquals("hola ", received.get(4).getInitialWord().toString());
  }

  @Test
  public void rapidBrailleTextChangesSuppressAutomaticSelectionEvents() {
    filter.setBrailleKeyboardEchoReader(() -> OptionalInt.of(PREF_ECHO_CHARACTERS_AND_WORDS));
    AccessibilityNodeInfo source = editableNode(/* visibleToUser= */ true);

    sendTextChange("", "h", 0, 0, 1, 1000L, source);
    sendTextChange("h", "ho", 1, 0, 1, 1030L, source);
    sendSelectionChange("ho", 1, 1, 1040L, source);
    sendSelectionChange("ho", 2, 2, 1050L, source);

    assertEquals(2, received.size());
    assertEquals("h", received.get(0).getAddedText().toString());
    assertEquals("o", received.get(1).getAddedText().toString());
  }

  @Test
  public void nonBrailleRapidTextChangesStillDropWithinDelay() {
    filter.setBrailleKeyboardEchoReader(OptionalInt::empty);

    sendTextChange("", "h", 0, 0, 1, 1000L, /* source= */ null);
    sendTextChange("h", "ho", 1, 0, 1, 1030L, /* source= */ null);

    assertEquals(1, received.size());
    assertEquals("h", received.get(0).getAddedText().toString());
  }

  @Test
  public void hiddenSourceNodeIsKeptOnlyWhenBrailleKeyboardIsActive() {
    AccessibilityNodeInfo hiddenSource = editableNode(/* visibleToUser= */ false);

    filter.setBrailleKeyboardEchoReader(OptionalInt::empty);
    sendTextChange("", "a", 0, 0, 1, 1000L, hiddenSource);
    assertEquals(0, received.size());

    filter.setBrailleKeyboardEchoReader(() -> OptionalInt.of(PREF_ECHO_CHARACTERS_AND_WORDS));
    sendTextChange("", "a", 0, 0, 1, 1200L, hiddenSource);
    assertEquals(1, received.size());
    assertEquals("a", received.get(0).getAddedText().toString());
  }

  private AccessibilityNodeInfo editableNode(boolean visibleToUser) {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(context));
    node.setPackageName(PACKAGE_NAME);
    node.setEditable(true);
    node.setFocused(true);
    node.setVisibleToUser(visibleToUser);
    return node;
  }

  private void sendTextChange(
      String beforeText,
      String afterText,
      int fromIndex,
      int removedCount,
      int addedCount,
      long eventTime,
      AccessibilityNodeInfo source) {
    AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);
    event.setPackageName(PACKAGE_NAME);
    event.setBeforeText(beforeText);
    event.getText().add(afterText);
    event.setFromIndex(fromIndex);
    event.setRemovedCount(removedCount);
    event.setAddedCount(addedCount);
    event.setEventTime(eventTime);
    if (source != null) {
      shadowOf(event).setSourceNode(source);
    }
    interpreter.interpret(event, EVENT_ID_UNTRACKED);
  }

  private void sendSelectionChange(
      String text, int fromIndex, int toIndex, long eventTime, AccessibilityNodeInfo source) {
    AccessibilityEvent event =
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED);
    event.setPackageName(PACKAGE_NAME);
    event.getText().add(text);
    event.setItemCount(text.length());
    event.setFromIndex(fromIndex);
    event.setToIndex(toIndex);
    event.setEventTime(eventTime);
    if (source != null) {
      shadowOf(event).setSourceNode(source);
    }
    interpreter.interpret(event, EVENT_ID_UNTRACKED);
  }
}
