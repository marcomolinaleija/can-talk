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

package com.google.android.accessibility.brailleime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.UserManager;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputConnection;
import com.google.android.accessibility.braille.interfaces.BrailleCharacter;
import com.google.android.accessibility.brailleime.input.Swipe;
import com.google.android.accessibility.brailleime.search.BrailleSearchQuery;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Tests for {@link EmojiManager}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EmojiManagerTest {

  private Context context;
  private EmojiManager emojiManager;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    emojiManager = new EmojiManager(context);
    // Populate test category 0 with known test items
    emojiManager.testing_setCategoryList(
        0, Arrays.asList("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "🙃"));
  }

  @Test
  public void testInitialStateInactive() {
    assertFalse(emojiManager.isEmojiModeActive());
  }

  @Test
  public void testEnterAndExitEmojiMode() {
    emojiManager.enterEmojiMode(null);
    assertTrue(emojiManager.isEmojiModeActive());
    assertEquals(0, emojiManager.getCurrentCategory());
    assertEquals(0, emojiManager.getCurrentRow());
    assertEquals(0, emojiManager.getCurrentSelected());
    assertEquals("😀", emojiManager.getSelectedEmoji());

    emojiManager.exitEmojiMode();
    assertFalse(emojiManager.isEmojiModeActive());
  }

  @Test
  public void testToggleEmojiMode() {
    emojiManager.toggleEmojiMode(null);
    assertTrue(emojiManager.isEmojiModeActive());

    emojiManager.toggleEmojiMode(null);
    assertFalse(emojiManager.isEmojiModeActive());
  }

  @Test
  public void testCategoryStepNavigation() {
    emojiManager.enterEmojiMode(null);

    // Dot 5: Next category
    emojiManager.onBrailleProduced(new BrailleCharacter("5"), null);
    assertEquals(1, emojiManager.getCurrentCategory());

    // Dot 4: Previous category
    emojiManager.onBrailleProduced(new BrailleCharacter("4"), null);
    assertEquals(0, emojiManager.getCurrentCategory());

    // Dot 4 from 0: Wraps to last category (Favorites = 12)
    emojiManager.onBrailleProduced(new BrailleCharacter("4"), null);
    assertEquals(EmojiManager.FAVORITES_CATEGORY_INDEX, emojiManager.getCurrentCategory());
  }

  @Test
  public void testRowStepNavigation() {
    emojiManager.enterEmojiMode(null);

    // Dots 5, 6: Next row
    emojiManager.onBrailleProduced(new BrailleCharacter("56"), null);
    assertEquals(1, emojiManager.getCurrentRow());

    // Dots 4, 5: Previous row
    emojiManager.onBrailleProduced(new BrailleCharacter("45"), null);
    assertEquals(0, emojiManager.getCurrentRow());

    // Dots 4, 5 from 0: Wraps to last row (15 = Z)
    emojiManager.onBrailleProduced(new BrailleCharacter("45"), null);
    assertEquals(15, emojiManager.getCurrentRow());
  }

  @Test
  public void testEmojiStepNavigation() {
    emojiManager.enterEmojiMode(null);
    assertEquals("😀", emojiManager.getSelectedEmoji());

    // Dot 6: Next emoji
    emojiManager.onBrailleProduced(new BrailleCharacter("6"), null);
    assertEquals(1, emojiManager.getCurrentSelected());
    assertEquals("😃", emojiManager.getSelectedEmoji());

    // Dot 3: Previous emoji
    emojiManager.onBrailleProduced(new BrailleCharacter("3"), null);
    assertEquals(0, emojiManager.getCurrentSelected());
    assertEquals("😀", emojiManager.getSelectedEmoji());
  }

  @Test
  public void testDirectCategoryBrailleJumps() {
    emojiManager.enterEmojiMode(null);

    // 'A' (dot 1) -> Category 0
    emojiManager.onBrailleProduced(new BrailleCharacter("1"), null);
    assertEquals(0, emojiManager.getCurrentCategory());

    // 'B' (dots 1, 2) -> Category 1
    emojiManager.onBrailleProduced(new BrailleCharacter("12"), null);
    assertEquals(1, emojiManager.getCurrentCategory());

    // 'C' (dots 1, 4) -> Category 2
    emojiManager.onBrailleProduced(new BrailleCharacter("14"), null);
    assertEquals(2, emojiManager.getCurrentCategory());

    // '#' (dots 3, 4, 5, 6) -> Favorites (Category 12)
    emojiManager.onBrailleProduced(new BrailleCharacter("3456"), null);
    assertEquals(EmojiManager.FAVORITES_CATEGORY_INDEX, emojiManager.getCurrentCategory());
  }

  @Test
  public void testDirectRowBrailleJumps() {
    emojiManager.enterEmojiMode(null);

    // 'K' (dots 1, 3) -> Row 0
    emojiManager.onBrailleProduced(new BrailleCharacter("13"), null);
    assertEquals(0, emojiManager.getCurrentRow());

    // 'L' (dots 1, 2, 3) -> Row 1
    emojiManager.onBrailleProduced(new BrailleCharacter("123"), null);
    assertEquals(1, emojiManager.getCurrentRow());

    // 'M' (dots 1, 3, 4) -> Row 2
    emojiManager.onBrailleProduced(new BrailleCharacter("134"), null);
    assertEquals(2, emojiManager.getCurrentRow());
  }

  @Test
  public void testDirectColumnBrailleJumps() {
    emojiManager.enterEmojiMode(null);

    // Dropped 2 (dots 2, 3) -> Column 1
    emojiManager.onBrailleProduced(new BrailleCharacter("23"), null);
    assertEquals(1, emojiManager.getCurrentSelected());
    assertEquals("😃", emojiManager.getSelectedEmoji());

    // Dropped 1 (dot 2) -> Column 0
    emojiManager.onBrailleProduced(new BrailleCharacter("2"), null);
    assertEquals(0, emojiManager.getCurrentSelected());
    assertEquals("😀", emojiManager.getSelectedEmoji());
  }

  @Test
  public void testDirectColumnBrailleDirectInsertion() {
    emojiManager.enterEmojiMode(null);
    class FakeInputConnection extends BaseInputConnection {
      String committedText;
      int committedPosition;

      FakeInputConnection() {
        super(new View(context), false);
      }

      @Override
      public boolean commitText(CharSequence text, int newCursorPosition) {
        committedText = text != null ? text.toString() : null;
        committedPosition = newCursorPosition;
        return true;
      }
    }

    FakeInputConnection fakeInputConnection = new FakeInputConnection();

    // Dropped 2 (dots 2, 3) -> Column 1 directly commits "😃"
    emojiManager.onBrailleProduced(new BrailleCharacter("23"), fakeInputConnection);
    assertEquals(1, emojiManager.getCurrentSelected());
    assertEquals("😃", fakeInputConnection.committedText);

    // Dropped 7 (dots 2, 3, 5, 6) -> Column 6 directly commits "😂"
    emojiManager.onBrailleProduced(new BrailleCharacter("2356"), fakeInputConnection);
    assertEquals(6, emojiManager.getCurrentSelected());
    assertEquals("😂", fakeInputConnection.committedText);
  }

  @Test
  public void testCommitSelectedEmoji() {
    emojiManager.enterEmojiMode(null);
    class FakeInputConnection extends BaseInputConnection {
      String committedText;
      int committedPosition;

      FakeInputConnection() {
        super(new View(context), false);
      }

      @Override
      public boolean commitText(CharSequence text, int newCursorPosition) {
        committedText = text != null ? text.toString() : null;
        committedPosition = newCursorPosition;
        return true;
      }
    }

    FakeInputConnection fakeInputConnection = new FakeInputConnection();

    // Dots 4, 5, 6: Confirm and commit emoji
    emojiManager.onBrailleProduced(new BrailleCharacter("456"), fakeInputConnection);
    assertEquals("😀", fakeInputConnection.committedText);
    assertEquals(1, fakeInputConnection.committedPosition);
  }

  @Test
  public void testToggleFavorites() {
    emojiManager.enterEmojiMode(null);
    assertEquals("😀", emojiManager.getSelectedEmoji());

    // Dots 3, 6: Add to favorites
    emojiManager.onBrailleProduced(new BrailleCharacter("36"), null);
    assertTrue(emojiManager.getFavorites().contains("😀"));

    // Toggle again removes from favorites
    emojiManager.onBrailleProduced(new BrailleCharacter("36"), null);
    assertFalse(emojiManager.getFavorites().contains("😀"));
  }

  @Test
  public void testDirectBootLockedDoesNotAccessCredentialPreferencesAndLoadsAfterUnlock() {
    context
        .getSharedPreferences("braille_emoji_preferences", Context.MODE_PRIVATE)
        .edit()
        .putString("user_favorite_emojis", "🔥\n⭐")
        .commit();

    UserManager userManager = context.getSystemService(UserManager.class);
    shadowOf(userManager).setUserUnlocked(false);

    Context directBootGuardContext =
        new ContextWrapper(context) {
          @Override
          public SharedPreferences getSharedPreferences(String name, int mode) {
            if (!userManager.isUserUnlocked()) {
              throw new IllegalStateException(
                  "SharedPreferences in credential encrypted storage are not available until after"
                      + " user is unlocked");
            }
            return super.getSharedPreferences(name, mode);
          }
        };

    EmojiManager lockedManager = new EmojiManager(directBootGuardContext);
    assertTrue(lockedManager.getFavorites().isEmpty());

    shadowOf(userManager).setUserUnlocked(true);
    assertEquals(Arrays.asList("🔥", "⭐"), lockedManager.getFavorites());
  }

  @Test
  public void testEnterAndExitSearchModeWithTwoFingersLeft() {
    emojiManager.enterEmojiMode(null);
    assertFalse(emojiManager.isSearchModeActive());

    // Swipe 2 fingers left enters search mode
    boolean handled =
        emojiManager.onSwipeProduced(new Swipe(Swipe.Direction.LEFT, 2), null);
    assertTrue(handled);
    assertTrue(emojiManager.isSearchModeActive());

    // Swipe 2 fingers left again exits search mode back to emoji browsing
    handled = emojiManager.onSwipeProduced(new Swipe(Swipe.Direction.LEFT, 2), null);
    assertTrue(handled);
    assertFalse(emojiManager.isSearchModeActive());
    assertTrue(emojiManager.isEmojiModeActive());
  }

  @Test
  public void testSearchModeTypingBrailleAndFiltering() {
    emojiManager.enterEmojiMode(null);
    emojiManager.testing_setSearchIndex(
        Arrays.asList(
            new EmojiManager.EmojiItem("😀", "cara sonriendo"),
            new EmojiManager.EmojiItem("🐱", "cara de gato"),
            new EmojiManager.EmojiItem("🚗", "coche")));

    // Enter search mode
    emojiManager.onSwipeProduced(new Swipe(Swipe.Direction.LEFT, 2), null);
    assertTrue(emojiManager.isSearchModeActive());

    // Set mock translator for BrailleSearchQuery
    emojiManager.testing_setSearchQuery(
        new BrailleSearchQuery(
            word -> {
              if (word.size() == 1) return "cara";
              if (word.size() == 2) return "cara sonriendo";
              return "";
            }));

    // Type 1 Braille cell -> query is "cara"
    emojiManager.onBrailleProduced(new BrailleCharacter("1"), null);
    assertEquals("cara", emojiManager.getSearchQueryText());
    List<EmojiManager.EmojiItem> matches = emojiManager.getSearchMatches();
    assertEquals(2, matches.size());
    assertEquals("cara de gato", matches.get(0).name);
    assertEquals("cara sonriendo", matches.get(1).name);

    // Type second Braille cell -> query is "cara sonriendo"
    emojiManager.onBrailleProduced(new BrailleCharacter("2"), null);
    assertEquals("cara sonriendo", emojiManager.getSearchQueryText());
    matches = emojiManager.getSearchMatches();
    assertEquals(1, matches.size());
    assertEquals("cara sonriendo", matches.get(0).name);
    assertEquals("😀", matches.get(0).emoji);
  }

  @Test
  public void testSearchNavigationWithOneFingerDownAndUp() {
    emojiManager.enterEmojiMode(null);
    emojiManager.testing_setSearchIndex(
        Arrays.asList(
            new EmojiManager.EmojiItem("1️⃣", "uno"),
            new EmojiManager.EmojiItem("2️⃣", "dos"),
            new EmojiManager.EmojiItem("3️⃣", "tres")));

    emojiManager.onSwipeProduced(new Swipe(Swipe.Direction.LEFT, 2), null);
    emojiManager.testing_setSearchQuery(new BrailleSearchQuery(word -> "o")); // matches "uno", "dos"

    emojiManager.onBrailleProduced(new BrailleCharacter("1"), null);
    assertEquals(2, emojiManager.getSearchMatches().size());
    assertEquals(0, emojiManager.getSearchSelectionIndex());
    assertEquals("dos", emojiManager.getSelectedSearchItem().name);

    // 1 finger DOWN -> moves to next
    emojiManager.onSwipeProduced(new Swipe(Swipe.Direction.DOWN, 1), null);
    assertEquals(1, emojiManager.getSearchSelectionIndex());
    assertEquals("uno", emojiManager.getSelectedSearchItem().name);

    // 1 finger DOWN again -> wraps to 0
    emojiManager.onSwipeProduced(new Swipe(Swipe.Direction.DOWN, 1), null);
    assertEquals(0, emojiManager.getSearchSelectionIndex());
    assertEquals("dos", emojiManager.getSelectedSearchItem().name);

    // 1 finger UP -> wraps back to 1
    emojiManager.onSwipeProduced(new Swipe(Swipe.Direction.UP, 1), null);
    assertEquals(1, emojiManager.getSearchSelectionIndex());
    assertEquals("uno", emojiManager.getSelectedSearchItem().name);
  }

  @Test
  public void testSearchDeleteAndSpaceSwipes() {
    emojiManager.enterEmojiMode(null);
    emojiManager.testing_setSearchIndex(
        Arrays.asList(new EmojiManager.EmojiItem("😀", "sonrisa")));

    emojiManager.onSwipeProduced(new Swipe(Swipe.Direction.LEFT, 2), null);
    emojiManager.testing_setSearchQuery(
        new BrailleSearchQuery(word -> word.isEmpty() ? "" : "sonrisa"));

    // Type a cell
    emojiManager.onBrailleProduced(new BrailleCharacter("1"), null);
    assertEquals("sonrisa", emojiManager.getSearchQueryText());

    // 1 finger LEFT (backspace)
    emojiManager.onSwipeProduced(new Swipe(Swipe.Direction.LEFT, 1), null);
    assertEquals("", emojiManager.getSearchQueryText());
    assertTrue(emojiManager.getSearchMatches().isEmpty());
  }

  @Test
  public void testSearchCommitWithTwoFingersRight() {
    AtomicReference<CharSequence> committedText = new AtomicReference<>("");
    View dummyView = new View(context);
    InputConnection fakeInputConnection =
        new BaseInputConnection(dummyView, false) {
          @Override
          public boolean commitText(CharSequence text, int newCursorPosition) {
            committedText.set(text);
            return true;
          }
        };

    emojiManager.enterEmojiMode(fakeInputConnection);
    emojiManager.testing_setSearchIndex(
        Arrays.asList(new EmojiManager.EmojiItem("🔥", "fuego")));

    emojiManager.onSwipeProduced(new Swipe(Swipe.Direction.LEFT, 2), fakeInputConnection);
    emojiManager.testing_setSearchQuery(new BrailleSearchQuery(word -> "fuego"));
    emojiManager.onBrailleProduced(new BrailleCharacter("1"), fakeInputConnection);

    assertEquals(1, emojiManager.getSearchMatches().size());
    assertEquals("🔥", emojiManager.getSelectedSearchItem().emoji);

    // Swipe 2 fingers RIGHT -> commits selected emoji and exits emoji mode
    boolean handled =
        emojiManager.onSwipeProduced(new Swipe(Swipe.Direction.RIGHT, 2), fakeInputConnection);
    assertTrue(handled);
    assertEquals("🔥", committedText.get().toString());
    assertFalse(emojiManager.isEmojiModeActive());
    assertFalse(emojiManager.isSearchModeActive());
  }

  @Test
  public void testNormalizeHelper() {
    assertEquals("¡hola, corazon mio!", EmojiManager.normalize("¡Hola,  Corazón   MÍO!"));
    assertEquals("", EmojiManager.normalize(null));
  }
}
