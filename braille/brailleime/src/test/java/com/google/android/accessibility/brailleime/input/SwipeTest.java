/*
 * Copyright 2026 Can Talk contributors
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import com.google.android.accessibility.brailleime.input.Swipe.Direction;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Tests for {@link Swipe} with single-dot gestures support. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SwipeTest {

  private Context context;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
  }

  @Test
  public void genericSwipe_propertiesAndId() {
    Swipe swipe = new Swipe(Direction.RIGHT, 1);
    assertFalse(swipe.hasSwipedDot());
    assertEquals(0, swipe.getSwipedDot());
    assertEquals("swipe_right_1", swipe.getId());
    assertEquals(Direction.RIGHT, swipe.getDirection());
    assertEquals(1, swipe.getTouchCount());
  }

  @Test
  public void dotSwipe_propertiesAndId() {
    Swipe swipe4 = new Swipe(Direction.RIGHT, 1, 4);
    assertTrue(swipe4.hasSwipedDot());
    assertEquals(4, swipe4.getSwipedDot());
    assertEquals("swipe_dot4_right_1", swipe4.getId());

    Swipe swipe6 = new Swipe(Direction.RIGHT, 1, 6);
    assertTrue(swipe6.hasSwipedDot());
    assertEquals(6, swipe6.getSwipedDot());
    assertEquals("swipe_dot6_right_1", swipe6.getId());

    assertNotEquals(swipe4, swipe6);
    assertNotEquals(swipe4.getId(), swipe6.getId());
    assertNotEquals(swipe4.hashCode(), swipe6.hashCode());
  }

  @Test
  public void parseId_genericSwipe() {
    Swipe swipe = new Swipe("swipe_left_2");
    assertFalse(swipe.hasSwipedDot());
    assertEquals(Direction.LEFT, swipe.getDirection());
    assertEquals(2, swipe.getTouchCount());
    assertEquals("swipe_left_2", swipe.getId());
  }

  @Test
  public void parseId_dotSwipe() {
    Swipe swipe = new Swipe("swipe_dot3_right_1");
    assertTrue(swipe.hasSwipedDot());
    assertEquals(3, swipe.getSwipedDot());
    assertEquals(Direction.RIGHT, swipe.getDirection());
    assertEquals(1, swipe.getTouchCount());
    assertEquals("swipe_dot3_right_1", swipe.getId());
  }

  @Test
  public void withoutDot_returnsGenericSwipe() {
    Swipe dotSwipe = new Swipe(Direction.RIGHT, 1, 4);
    Swipe generic = dotSwipe.withoutDot();
    assertFalse(generic.hasSwipedDot());
    assertEquals(0, generic.getSwipedDot());
    assertEquals(Direction.RIGHT, generic.getDirection());
    assertEquals("swipe_right_1", generic.getId());
  }

  @Test
  public void mirrorDots_mirrorsBrailleDot() {
    Swipe swipe1 = new Swipe(Direction.RIGHT, 1, 1);
    assertEquals(new Swipe(Direction.RIGHT, 1, 4), swipe1.mirrorDots());

    Swipe swipe2 = new Swipe(Direction.RIGHT, 1, 2);
    assertEquals(new Swipe(Direction.RIGHT, 1, 5), swipe2.mirrorDots());

    Swipe swipe3 = new Swipe(Direction.RIGHT, 1, 3);
    assertEquals(new Swipe(Direction.RIGHT, 1, 6), swipe3.mirrorDots());

    Swipe swipe4 = new Swipe(Direction.RIGHT, 1, 4);
    assertEquals(new Swipe(Direction.RIGHT, 1, 1), swipe4.mirrorDots());

    Swipe generic = new Swipe(Direction.RIGHT, 1);
    assertEquals(generic, generic.mirrorDots());
  }

  @Test
  public void reorientations_preserveSwipedDot() {
    Swipe swipe = new Swipe(Direction.UP, 1, 4);

    Swipe rot90 = Swipe.createFromRotation90(swipe);
    assertEquals(Direction.RIGHT, rot90.getDirection());
    assertEquals(4, rot90.getSwipedDot());

    Swipe upsideDown = Swipe.createFromUpSideDown(swipe);
    assertEquals(Direction.DOWN, upsideDown.getDirection());
    assertEquals(4, upsideDown.getSwipedDot());

    Swipe mirror = Swipe.createFromMirror(new Swipe(Direction.LEFT, 1, 4));
    assertEquals(Direction.RIGHT, mirror.getDirection());
    assertEquals(4, mirror.getSwipedDot());
  }

  @Test
  public void description_includesDotNumber() {
    Swipe swipe4 = new Swipe(Direction.RIGHT, 1, 4);
    String desc = swipe4.getDescription(context.getResources());
    assertTrue(desc.contains("4"));
  }
}
