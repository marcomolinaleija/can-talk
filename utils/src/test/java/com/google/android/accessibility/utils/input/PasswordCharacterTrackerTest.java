/*
 * Copyright (C) 2024 Google Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class PasswordCharacterTrackerTest {

  private PasswordCharacterTracker tracker;

  @Before
  public void setUp() {
    tracker = PasswordCharacterTracker.getInstance();
    tracker.clear();
  }

  @Test
  public void recordAndConsumeTypedChar_returnsCharAndClears() {
    tracker.recordTypedChar('a');
    assertEquals("a", tracker.consumeTypedChar());
    assertNull(tracker.consumeTypedChar());
  }

  @Test
  public void recordTypedCharSequence_singleChar_recordedSuccessfully() {
    tracker.recordTypedChar("Z");
    assertEquals("Z", tracker.consumeTypedChar());
  }

  @Test
  public void recordTypedCharSequence_multiCharOrEmpty_notRecorded() {
    tracker.recordTypedChar("Shift");
    assertNull(tracker.consumeTypedChar());

    tracker.recordTypedChar("");
    assertNull(tracker.consumeTypedChar());

    tracker.recordTypedChar((CharSequence) null);
    assertNull(tracker.consumeTypedChar());
  }

  @Test
  public void brailleHandled_recordsAndConsumesOnce() {
    assertFalse(tracker.consumeBrailleHandled());
    tracker.recordBrailleHandled();
    assertTrue(tracker.consumeBrailleHandled());
    assertFalse(tracker.consumeBrailleHandled());
  }

  @Test
  public void clear_resetsAllState() {
    tracker.recordTypedChar('x');
    tracker.recordBrailleHandled();
    tracker.clear();
    assertNull(tracker.consumeTypedChar());
    assertFalse(tracker.consumeBrailleHandled());
  }
}
