/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.brailleime.search;

import com.google.android.accessibility.braille.interfaces.BrailleCharacter;
import com.google.android.accessibility.braille.interfaces.BrailleWord;
import java.util.function.Function;

/** A braille query independent of any editor. Re-translate whole cells after each deletion. */
public final class BrailleSearchQuery {
  private static final int MAX_CELLS = 100;
  private final BrailleWord cells = new BrailleWord();
  private final Function<BrailleWord, String> translate;

  public BrailleSearchQuery(Function<BrailleWord, String> translate) {
    this.translate = translate;
  }

  public String append(BrailleCharacter cell) {
    if (cells.size() < MAX_CELLS) cells.append(cell);
    return text();
  }

  public String delete() {
    if (!cells.isEmpty()) cells.remove(cells.size() - 1);
    return text();
  }

  public String clear() {
    cells.clear();
    return "";
  }

  public String text() {
    return cells.isEmpty() ? "" : translate.apply(new BrailleWord(cells));
  }
}
