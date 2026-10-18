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
import java.util.Arrays;
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
}
