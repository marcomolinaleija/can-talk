/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.brailleime.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import android.util.Size;
import com.google.android.accessibility.braille.interfaces.BrailleCharacter;
import com.google.android.accessibility.brailleime.input.Swipe;
import com.google.android.accessibility.brailleime.search.BrailleAppSearchInput.Action;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BrailleAppSearchInputTest {
  private BrailleAppSearchInput input;
  private final List<Action> actions = new ArrayList<>();
  private final List<String> queries = new ArrayList<>();

  @Before public void setup() {
    input = new BrailleAppSearchInput(RuntimeEnvironment.getApplication(), new Size(1080, 1920),
        new BrailleAppSearchInput.Listener() {
          @Override public void onQueryChanged(String query) { queries.add(query); }
          @Override public void onAction(Action action) { actions.add(action); }
          @Override public void onAnnouncement(String text) { }
        }, new BrailleSearchQuery(word -> {
          StringBuilder text = new StringBuilder();
          word.toList().forEach(cell -> text.append(cell.isEmpty() ? " " : "a"));
          return text.toString();
        }));
  }

  @Test public void twoFingersRightOpensButTheTypingSendGestureDoesNot() {
    input.onSwipeProduced(new Swipe(Swipe.Direction.UP, 2));
    assertTrue(actions.isEmpty());
    input.onSwipeProduced(new Swipe(Swipe.Direction.RIGHT, 2));
    assertEquals(List.of(Action.OPEN), actions);
  }

  @Test public void verticalSwipesSelectAndTwoFingersLeftOrDownClose() {
    input.onSwipeProduced(new Swipe(Swipe.Direction.UP, 1));
    input.onSwipeProduced(new Swipe(Swipe.Direction.DOWN, 1));
    input.onSwipeProduced(new Swipe(Swipe.Direction.LEFT, 2));
    input.onSwipeProduced(new Swipe(Swipe.Direction.DOWN, 2));
    assertEquals(List.of(Action.PREVIOUS, Action.NEXT, Action.CLOSE, Action.CLOSE), actions);
  }

  @Test public void typingSpaceAndDeletionOnlyChangeTheSearchQuery() {
    input.onBrailleProduced(new BrailleCharacter("1"));
    input.onSwipeProduced(new Swipe(Swipe.Direction.RIGHT, 1));
    input.onSwipeProduced(new Swipe(Swipe.Direction.LEFT, 1));
    assertEquals(List.of("a", "a ", "a"), queries);
  }

  @Test public void lateTouchesAfterClosureCannotTypeOrLaunchAnApp() {
    input.close();
    input.onBrailleProduced(new BrailleCharacter("1"));
    input.onSwipeProduced(new Swipe(Swipe.Direction.RIGHT, 2));
    assertTrue(actions.isEmpty());
    assertTrue(queries.isEmpty());
  }
}
