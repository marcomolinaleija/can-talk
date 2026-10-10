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

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.view.inputmethod.InputConnection;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.google.android.accessibility.braille.common.BrailleCommonLog;
import com.google.android.accessibility.braille.common.BrailleCommonTalkBackSpeaker;
import com.google.android.accessibility.braille.common.TalkBackSpeaker.AnnounceType;
import com.google.android.accessibility.braille.interfaces.BrailleCharacter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Manages categorized emoji browsing, grid navigation, and insertion for the Braille keyboard,
 * ported and adapted from Advanced Braille Keyboard (ABK).
 */
public class EmojiManager {
  private static final String TAG = "EmojiManager";
  private static final String PREF_NAME = "braille_emoji_preferences";
  private static final String KEY_FAVORITES = "user_favorite_emojis";

  public static final String ROW_LETTERS = "KLMNOPQRSTUVWXYZ";
  public static final int MAX_COLUMNS = 10;
  public static final int TOTAL_BUILTIN_CATEGORIES = 12;
  public static final int FAVORITES_CATEGORY_INDEX = 12;
  public static final int TOTAL_CATEGORIES = 13;
  public static final int MAX_FAVORITES = 160;

  // Direct Braille characters for categories (0..12)
  // 0: A (1), 1: B (12), 2: C (14), 3: D (145), 4: E (15), 5: F (124), 6: G (1245),
  // 7: H (125), 8: I (24), 9: J (245), 10: $ (12356), 11: * (23456), 12: # (3456)
  private static final List<BrailleCharacter> CATEGORY_DIRECT_CHARS =
      Collections.unmodifiableList(
          Arrays.asList(
              new BrailleCharacter("1"),
              new BrailleCharacter("12"),
              new BrailleCharacter("14"),
              new BrailleCharacter("145"),
              new BrailleCharacter("15"),
              new BrailleCharacter("124"),
              new BrailleCharacter("1245"),
              new BrailleCharacter("125"),
              new BrailleCharacter("24"),
              new BrailleCharacter("245"),
              new BrailleCharacter("12356"),
              new BrailleCharacter("23456"),
              new BrailleCharacter("3456")));

  // Direct Braille characters for rows (0..15: K through Z)
  private static final List<BrailleCharacter> ROW_DIRECT_CHARS =
      Collections.unmodifiableList(
          Arrays.asList(
              new BrailleCharacter("13"), // K
              new BrailleCharacter("123"), // L
              new BrailleCharacter("134"), // M
              new BrailleCharacter("1345"), // N
              new BrailleCharacter("135"), // O
              new BrailleCharacter("1234"), // P
              new BrailleCharacter("12345"), // Q
              new BrailleCharacter("1235"), // R
              new BrailleCharacter("234"), // S
              new BrailleCharacter("2345"), // T
              new BrailleCharacter("136"), // U
              new BrailleCharacter("1236"), // V
              new BrailleCharacter("2456"), // W
              new BrailleCharacter("1346"), // X
              new BrailleCharacter("13456"), // Y
              new BrailleCharacter("1356") // Z
              ));

  // Direct dropped numbers for columns (0..9: 1 through 10)
  private static final List<BrailleCharacter> COLUMN_DIRECT_CHARS =
      Collections.unmodifiableList(
          Arrays.asList(
              new BrailleCharacter("2"), // 1
              new BrailleCharacter("23"), // 2
              new BrailleCharacter("25"), // 3
              new BrailleCharacter("256"), // 4
              new BrailleCharacter("26"), // 5
              new BrailleCharacter("235"), // 6
              new BrailleCharacter("2356"), // 7
              new BrailleCharacter("236"), // 8
              new BrailleCharacter("35"), // 9
              new BrailleCharacter("356") // 10 / 0
              ));

  // Step navigation Braille characters
  private static final BrailleCharacter CHAR_NEXT_CATEGORY = new BrailleCharacter("5");
  private static final BrailleCharacter CHAR_PREV_CATEGORY = new BrailleCharacter("4");
  private static final BrailleCharacter CHAR_NEXT_ROW = new BrailleCharacter("56");
  private static final BrailleCharacter CHAR_PREV_ROW = new BrailleCharacter("45");
  private static final BrailleCharacter CHAR_NEXT_EMOJI = new BrailleCharacter("6");
  private static final BrailleCharacter CHAR_PREV_EMOJI = new BrailleCharacter("3");
  private static final BrailleCharacter CHAR_CONFIRM_EMOJI = new BrailleCharacter("456");
  private static final BrailleCharacter CHAR_TOGGLE_FAVORITE = new BrailleCharacter("36");

  private final Context context;
  private final List<List<String>> categories = new ArrayList<>(TOTAL_CATEGORIES);
  private final List<String> favorites = new ArrayList<>();
  private boolean isEmojiMode = false;
  private int currentCategory = 0;
  private int currentRow = 0;
  private int currentSelected = 0;

  public EmojiManager(@NonNull Context context) {
    this.context = context;
    initCategories();
  }

  private void initCategories() {
    categories.clear();
    for (int i = 0; i < TOTAL_BUILTIN_CATEGORIES; i++) {
      categories.add(loadCategoryFromAssets(i));
    }
    loadFavorites();
    categories.add(favorites);
  }

  private List<String> loadCategoryFromAssets(int categoryIndex) {
    List<String> list = new ArrayList<>();
    String assetPath = "emoji/category_" + categoryIndex + ".txt";
    try (InputStream is = context.getAssets().open(assetPath);
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (!trimmed.isEmpty()) {
          list.add(trimmed);
        }
      }
    } catch (IOException e) {
      BrailleCommonLog.w(TAG, "Could not open emoji asset " + assetPath + ": " + e.getMessage());
    }
    return list;
  }

  public void reloadAfterBackup() {
    exitEmojiModeSilently();
    loadFavorites();
  }

  private void loadFavorites() {
    favorites.clear();
    SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    String raw = prefs.getString(KEY_FAVORITES, "");
    if (!TextUtils.isEmpty(raw)) {
      String[] parts = raw.split("\n");
      for (String p : parts) {
        String trimmed = p.trim();
        if (!trimmed.isEmpty()) {
          favorites.add(trimmed);
        }
      }
    }
  }

  private void saveFavorites() {
    SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    StringBuilder sb = new StringBuilder();
    for (String fav : favorites) {
      if (sb.length() > 0) {
        sb.append("\n");
      }
      sb.append(fav);
    }
    prefs.edit().putString(KEY_FAVORITES, sb.toString()).apply();
  }

  public boolean isEmojiModeActive() {
    return isEmojiMode;
  }

  public void toggleEmojiMode(@Nullable InputConnection inputConnection) {
    if (isEmojiMode) {
      exitEmojiMode();
    } else {
      enterEmojiMode(inputConnection);
    }
  }

  public void enterEmojiMode(@Nullable InputConnection inputConnection) {
    isEmojiMode = true;
    currentCategory = 0;
    currentRow = 0;
    currentSelected = 0;
    String catName = getCategoryLabel(currentCategory);
    char rowLetter = getRowLetter(currentRow);
    String rowPreview = getRowPreview(currentCategory, currentRow);
    String announcement =
        context.getString(R.string.emoji_mode_switched_on, catName, rowLetter, rowPreview);
    speak(announcement);
  }

  public void exitEmojiMode() {
    if (!isEmojiMode) {
      return;
    }
    isEmojiMode = false;
    speak(context.getString(R.string.emoji_mode_switched_off));
  }

  public void exitEmojiModeSilently() {
    isEmojiMode = false;
  }

  /**
   * Processes a BrailleCharacter when emoji mode is active.
   *
   * @return true if the character was consumed by emoji mode, false otherwise.
   */
  public boolean onBrailleProduced(
      @NonNull BrailleCharacter brailleChar, @Nullable InputConnection inputConnection) {
    if (!isEmojiMode) {
      return false;
    }

    // 1. Step navigation
    if (brailleChar.equals(CHAR_NEXT_CATEGORY)) {
      nextCategory();
      return true;
    }
    if (brailleChar.equals(CHAR_PREV_CATEGORY)) {
      previousCategory();
      return true;
    }
    if (brailleChar.equals(CHAR_NEXT_ROW)) {
      nextRow();
      return true;
    }
    if (brailleChar.equals(CHAR_PREV_ROW)) {
      previousRow();
      return true;
    }
    if (brailleChar.equals(CHAR_NEXT_EMOJI)) {
      nextEmoji();
      return true;
    }
    if (brailleChar.equals(CHAR_PREV_EMOJI)) {
      previousEmoji();
      return true;
    }
    if (brailleChar.equals(CHAR_CONFIRM_EMOJI)) {
      commitSelectedEmoji(inputConnection);
      return true;
    }
    if (brailleChar.equals(CHAR_TOGGLE_FAVORITE)) {
      toggleFavorite();
      return true;
    }

    // 2. Direct category selection (A-J, $, *, #)
    int catIndex = CATEGORY_DIRECT_CHARS.indexOf(brailleChar);
    if (catIndex >= 0 && catIndex < TOTAL_CATEGORIES) {
      selectCategory(catIndex);
      return true;
    }

    // 3. Direct row selection (K-Z)
    int rowIndex = ROW_DIRECT_CHARS.indexOf(brailleChar);
    if (rowIndex >= 0 && rowIndex < ROW_LETTERS.length()) {
      selectRow(rowIndex);
      return true;
    }

    // 4. Direct column selection & insertion (1..10)
    int colIndex = COLUMN_DIRECT_CHARS.indexOf(brailleChar);
    if (colIndex >= 0 && colIndex < MAX_COLUMNS) {
      selectAndCommitColumn(colIndex, inputConnection);
      return true;
    }

    return true; // Consume any other unhandled Braille input while in Emoji mode
  }

  public void nextCategory() {
    currentCategory = (currentCategory + 1) % TOTAL_CATEGORIES;
    currentRow = 0;
    currentSelected = 0;
    announceCategory();
  }

  public void previousCategory() {
    currentCategory = (currentCategory - 1 + TOTAL_CATEGORIES) % TOTAL_CATEGORIES;
    currentRow = 0;
    currentSelected = 0;
    announceCategory();
  }

  public void selectCategory(int categoryIndex) {
    if (categoryIndex < 0 || categoryIndex >= TOTAL_CATEGORIES) {
      return;
    }
    currentCategory = categoryIndex;
    currentRow = 0;
    currentSelected = 0;
    announceCategory();
  }

  public void nextRow() {
    currentRow = (currentRow + 1) % ROW_LETTERS.length();
    currentSelected = 0;
    announceRow();
  }

  public void previousRow() {
    currentRow = (currentRow - 1 + ROW_LETTERS.length()) % ROW_LETTERS.length();
    currentSelected = 0;
    announceRow();
  }

  public void selectRow(int rowIndex) {
    if (rowIndex < 0 || rowIndex >= ROW_LETTERS.length()) {
      return;
    }
    currentRow = rowIndex;
    currentSelected = 0;
    announceRow();
  }

  public void nextEmoji() {
    List<String> curCatList = getCurrentCategoryList();
    if (curCatList.isEmpty()) {
      announceFocusedEmoji();
      return;
    }
    currentSelected++;
    if (currentSelected >= MAX_COLUMNS
        || (currentRow * MAX_COLUMNS + currentSelected) >= curCatList.size()) {
      // Advance to next row
      currentRow = (currentRow + 1) % ROW_LETTERS.length();
      currentSelected = 0;
      if (currentRow * MAX_COLUMNS >= curCatList.size()) {
        // Wrap to first row
        currentRow = 0;
      }
    }
    announceFocusedEmoji();
  }

  public void previousEmoji() {
    List<String> curCatList = getCurrentCategoryList();
    if (curCatList.isEmpty()) {
      announceFocusedEmoji();
      return;
    }
    currentSelected--;
    if (currentSelected < 0) {
      currentRow = (currentRow - 1 + ROW_LETTERS.length()) % ROW_LETTERS.length();
      int countInRow = getCountInRow(curCatList, currentRow);
      currentSelected = Math.max(0, countInRow - 1);
    }
    announceFocusedEmoji();
  }

  public void selectColumn(int colIndex) {
    List<String> curCatList = getCurrentCategoryList();
    int countInRow = getCountInRow(curCatList, currentRow);
    if (colIndex < countInRow) {
      currentSelected = colIndex;
      announceFocusedEmoji();
    } else {
      currentSelected = Math.max(0, countInRow - 1);
      announceFocusedEmoji();
    }
  }

  public void selectAndCommitColumn(int colIndex, @Nullable InputConnection inputConnection) {
    List<String> curCatList = getCurrentCategoryList();
    int countInRow = getCountInRow(curCatList, currentRow);
    if (countInRow == 0) {
      return;
    }
    if (colIndex < countInRow) {
      currentSelected = colIndex;
    } else {
      currentSelected = Math.max(0, countInRow - 1);
    }
    if (inputConnection != null) {
      commitSelectedEmoji(inputConnection);
    } else {
      announceFocusedEmoji();
    }
  }

  public void commitSelectedEmoji(@Nullable InputConnection inputConnection) {
    String emoji = getSelectedEmoji();
    if (emoji != null && inputConnection != null) {
      inputConnection.commitText(emoji, 1);
      speak(context.getString(R.string.emoji_inserted, emoji));
    }
  }

  public void toggleFavorite() {
    String emoji = getSelectedEmoji();
    if (emoji == null) {
      return;
    }
    if (favorites.contains(emoji)) {
      favorites.remove(emoji);
      saveFavorites();
      speak(context.getString(R.string.emoji_removed_from_favorites, emoji));
    } else {
      if (favorites.size() < MAX_FAVORITES) {
        favorites.add(emoji);
        saveFavorites();
        speak(context.getString(R.string.emoji_added_to_favorites, emoji));
      }
    }
  }

  @Nullable
  public String getSelectedEmoji() {
    List<String> curCatList = getCurrentCategoryList();
    int index = currentRow * MAX_COLUMNS + currentSelected;
    if (index >= 0 && index < curCatList.size()) {
      return curCatList.get(index);
    }
    return null;
  }

  private List<String> getCurrentCategoryList() {
    if (currentCategory >= 0 && currentCategory < categories.size()) {
      return categories.get(currentCategory);
    }
    return Collections.emptyList();
  }

  private int getCountInRow(List<String> list, int row) {
    int start = row * MAX_COLUMNS;
    if (start >= list.size()) {
      return 0;
    }
    return Math.min(MAX_COLUMNS, list.size() - start);
  }

  private char getRowLetter(int row) {
    if (row >= 0 && row < ROW_LETTERS.length()) {
      return ROW_LETTERS.charAt(row);
    }
    return 'K';
  }

  private String getRowPreview(int categoryIndex, int row) {
    if (categoryIndex < 0 || categoryIndex >= categories.size()) {
      return "";
    }
    List<String> list = categories.get(categoryIndex);
    if (list.isEmpty()) {
      return context.getString(R.string.emoji_favorites_empty);
    }
    int start = row * MAX_COLUMNS;
    if (start >= list.size()) {
      return "";
    }
    int end = Math.min(start + MAX_COLUMNS, list.size());
    StringBuilder sb = new StringBuilder();
    for (int i = start; i < end; i++) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(list.get(i));
    }
    return sb.toString();
  }

  private void announceCategory() {
    String catName = getCategoryLabel(currentCategory);
    char rowLetter = getRowLetter(currentRow);
    String rowPreview = getRowPreview(currentCategory, currentRow);
    String announcement =
        context.getString(R.string.emoji_category_switched, catName, rowLetter, rowPreview);
    speak(announcement);
  }

  private void announceRow() {
    char rowLetter = getRowLetter(currentRow);
    String rowPreview = getRowPreview(currentCategory, currentRow);
    String announcement = context.getString(R.string.emoji_row_switched, rowLetter, rowPreview);
    speak(announcement);
  }

  private void announceFocusedEmoji() {
    String emoji = getSelectedEmoji();
    if (emoji == null) {
      if (currentCategory == FAVORITES_CATEGORY_INDEX && favorites.isEmpty()) {
        speak(context.getString(R.string.emoji_favorites_empty));
      } else {
        announceRow();
      }
      return;
    }
    char rowLetter = getRowLetter(currentRow);
    int pos = currentSelected + 1;
    String announcement = context.getString(R.string.emoji_item_focused, emoji, rowLetter, pos);
    speak(announcement);
  }

  public String getCategoryLabel(int index) {
    return switch (index) {
      case 0 -> context.getString(R.string.emoji_category_smileys);
      case 1 -> context.getString(R.string.emoji_category_animals);
      case 2 -> context.getString(R.string.emoji_category_food);
      case 3 -> context.getString(R.string.emoji_category_activities);
      case 4 -> context.getString(R.string.emoji_category_travel);
      case 5 -> context.getString(R.string.emoji_category_objects);
      case 6 -> context.getString(R.string.emoji_category_symbols);
      case 7 -> context.getString(R.string.emoji_category_people_1);
      case 8 -> context.getString(R.string.emoji_category_people_2);
      case 9 -> context.getString(R.string.emoji_category_other);
      case 10 -> context.getString(R.string.emoji_category_flags_1);
      case 11 -> context.getString(R.string.emoji_category_flags_2);
      case 12 -> context.getString(R.string.emoji_category_favorites);
      default -> "";
    };
  }

  private void speak(String text) {
    if (!TextUtils.isEmpty(text)) {
      BrailleCommonTalkBackSpeaker.getInstance().speak(text, AnnounceType.INTERRUPT);
    }
  }

  // Testing getters and helpers
  @VisibleForTesting
  public int getCurrentCategory() {
    return currentCategory;
  }

  @VisibleForTesting
  public int getCurrentRow() {
    return currentRow;
  }

  @VisibleForTesting
  public int getCurrentSelected() {
    return currentSelected;
  }

  @VisibleForTesting
  public List<String> getFavorites() {
    return Collections.unmodifiableList(favorites);
  }

  @VisibleForTesting
  public void testing_setCategoryList(int index, List<String> emojis) {
    while (categories.size() <= index) {
      categories.add(new ArrayList<>());
    }
    categories.set(index, emojis);
  }
}
