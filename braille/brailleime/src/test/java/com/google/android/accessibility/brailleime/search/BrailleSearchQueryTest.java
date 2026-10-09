/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.brailleime.search;

import static org.junit.Assert.assertEquals;
import com.google.android.accessibility.braille.interfaces.BrailleCharacter;
import org.junit.Test;

public class BrailleSearchQueryTest {
  @Test public void deletingAContractionRetranslatesTheRemainingCells() {
    BrailleSearchQuery query = new BrailleSearchQuery(word -> word.size() == 1 ? "a" : "about");
    assertEquals("a", query.append(new BrailleCharacter("1")));
    assertEquals("about", query.append(new BrailleCharacter("12")));
    assertEquals("a", query.delete());
    assertEquals("", query.delete());
    assertEquals("", query.delete());
  }

  @Test public void spacesAreCellsAndCanBeDeletedWithoutLosingPreviousLetters() {
    BrailleSearchQuery query = new BrailleSearchQuery(word -> {
      StringBuilder text = new StringBuilder();
      word.toList().forEach(cell -> text.append(cell.isEmpty() ? " " : "a"));
      return text.toString();
    });
    assertEquals("a", query.append(new BrailleCharacter("1")));
    assertEquals("a ", query.append(new BrailleCharacter()));
    assertEquals("a a", query.append(new BrailleCharacter("1")));
    assertEquals("a ", query.delete());
    assertEquals("a", query.delete());
  }

  @Test public void emptyAndClearedQueriesDoNotTranslateAnEmptyWord() {
    BrailleSearchQuery query = new BrailleSearchQuery(word -> {
      if (word.isEmpty()) throw new AssertionError("Empty words must not expand to a contraction");
      return "YouTube";
    });
    assertEquals("", query.text());
    assertEquals("YouTube", query.append(new BrailleCharacter("1")));
    assertEquals("", query.clear());
    assertEquals("YouTube", query.append(new BrailleCharacter("1")));
  }

  @Test public void excessiveInputIsBoundedAndStillAllowsDeletion() {
    BrailleSearchQuery query = new BrailleSearchQuery(word -> String.valueOf(word.size()));
    for (int i = 0; i < 200; i++) query.append(new BrailleCharacter("1"));
    assertEquals("100", query.text());
    assertEquals("99", query.delete());
  }
}
