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
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;
import com.google.android.accessibility.braille.common.BrailleImeAction;
import com.google.android.accessibility.braille.common.BrailleUserPreferences;
import com.google.android.accessibility.braille.common.ImeConnection;
import com.google.android.accessibility.braille.common.TalkBackSpeaker;
import com.google.android.accessibility.braille.common.translate.BrailleTranslateUtils;
import com.google.android.accessibility.braille.common.translate.EditBuffer;
import com.google.android.accessibility.braille.common.translate.EditBufferSpanish;
import com.google.android.accessibility.braille.common.translate.EditBufferSpanish2;
import com.google.android.accessibility.braille.interfaces.BrailleCharacter;
import com.google.android.accessibility.braille.interfaces.BrailleWord;
import com.google.android.accessibility.braille.interfaces.TalkBackForBrailleIme;
import com.google.android.accessibility.braille.translate.BrailleTranslator;
import com.google.android.accessibility.braille.translate.TranslationResult;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Regression tests for successive sends while the braille keyboard stays open. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class BrailleImeSubmitTest {
  @Test
  public void uncontractedMessagesDoNotCarryOverThePreviousWord() {
    assertIndependentMessages(false);
  }

  @Test
  public void contractedMessagesCommitPendingBrailleBeforeSending() {
    assertIndependentMessages(true);
  }

  private void assertIndependentMessages(boolean contracted) {
    Fixture fixture = new Fixture(contracted);
    fixture.actor.performTyping(new BrailleCharacter("1"));
    assertTrue(fixture.actor.performAction(BrailleImeAction.SUBMIT_TEXT));
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(Arrays.asList("a"), fixture.sent);
    fixture.actor.performTyping(new BrailleCharacter("12"));
    assertEquals(contracted ? "" : "b", fixture.editor.getText().toString());
    assertTrue(fixture.actor.performAction(BrailleImeAction.SUBMIT_TEXT));
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(Arrays.asList("a", "b"), fixture.sent);
    assertEquals("", fixture.editor.getText().toString());
    assertEquals(0, fixture.hideCount);
  }

  @Test
  public void rejectedSendDoesNotDeleteTheDraft() {
    Fixture fixture = new Fixture(false);
    fixture.acceptSend = false;
    fixture.actor.performTyping(new BrailleCharacter("1"));
    assertTrue(fixture.actor.performAction(BrailleImeAction.SUBMIT_TEXT));
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(fixture.sent.isEmpty());
    assertEquals("a", fixture.editor.getText().toString());
    fixture.actor.performTyping(new BrailleCharacter("12"));
    assertEquals("ab", fixture.editor.getText().toString());
  }

  @Test
  public void autoCapitalizeInitialLetterAndCancelOnDelete() {
    Fixture fixture = new Fixture(false, true);
    fixture.actor.performTyping(new BrailleCharacter("1"));
    assertEquals("A", fixture.editor.getText().toString());

    assertTrue(fixture.actor.performAction(BrailleImeAction.DELETE_CHARACTER_OR_PREVIOUS_ITEM));
    assertEquals("", fixture.editor.getText().toString());

    fixture.actor.performTyping(new BrailleCharacter("1"));
    assertEquals("a", fixture.editor.getText().toString());
  }

  @Test
  public void autoCapitalizeAfterPeriodAndSpace() {
    Fixture fixture = new Fixture(false, true);
    fixture.actor.performTyping(new BrailleCharacter("1"));
    assertTrue(fixture.actor.performAction(BrailleImeAction.DELETE_CHARACTER_OR_PREVIOUS_ITEM));
    fixture.actor.performTyping(new BrailleCharacter("1"));
    assertEquals("a", fixture.editor.getText().toString());

    fixture.actor.performTyping(new BrailleCharacter("256"));
    fixture.actor.performAction(BrailleImeAction.ADD_SPACE_OR_NEXT_ITEM);
    assertEquals("a. ", fixture.editor.getText().toString());

    fixture.actor.performTyping(new BrailleCharacter("12"));
    assertEquals("a. B", fixture.editor.getText().toString());
  }

  @Test
  public void suggestionNavigationAndConfirmationDoNotEditOrSendTheDraft() {
    Fixture fixture = new Fixture(false);
    fixture.editor.setText("@");
    fixture.suggestions = true;
    assertTrue(fixture.actor.performAction(BrailleImeAction.MOVE_CURSOR_FORWARD));
    assertTrue(fixture.actor.performAction(BrailleImeAction.MOVE_CURSOR_BACKWARD));
    assertEquals(Arrays.asList(true, false), fixture.suggestionMoves);
    assertTrue(fixture.actor.performAction(BrailleImeAction.ADD_NEWLINE));
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, fixture.confirmationCount);
    assertEquals("@", fixture.editor.getText().toString());
    assertTrue(fixture.sent.isEmpty());
    assertEquals(0, fixture.hideCount);
  }

  @Test
  public void typingLeavesSuggestionsAndAppendsToTheDraft() {
    Fixture fixture = new Fixture(false);
    fixture.editor.setText("@");
    fixture.editor.setSelection(1);
    fixture.suggestions = true;
    fixture.actor.performTyping(new BrailleCharacter("1"));
    assertTrue(!fixture.suggestions);
    assertEquals("@a", fixture.editor.getText().toString());
    assertEquals(0, fixture.confirmationCount);
    assertTrue(fixture.sent.isEmpty());
  }

  @Test
  public void submitKeepsItsUsualActionAndLeavesSuggestions() {
    Fixture fixture = new Fixture(false);
    fixture.editor.setText("Draft");
    fixture.suggestions = true;
    assertTrue(fixture.actor.performAction(BrailleImeAction.SUBMIT_TEXT));
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(!fixture.suggestions);
    assertEquals(Arrays.asList("Draft"), fixture.sent);
    assertEquals(0, fixture.confirmationCount);
  }

  @Test
  public void enteringControlsClearsComposingWordBeforeExternalMentionInsertion() {
    Fixture fixture = new Fixture(false);
    fixture.editor.setText("@");
    fixture.editor.setSelection(1);
    fixture.actor.performTyping(new BrailleCharacter("1"));
    assertEquals("@a", fixture.editor.getText().toString());
    fixture.actor.performAction(BrailleImeAction.NEXT_GRANULARITY);
    shadowOf(Looper.getMainLooper()).idle();
    // Simulate the editor's own mention insertion after its row was activated.
    fixture.editor.setText("@Ana ");
    fixture.editor.setSelection(fixture.editor.length());
    fixture.actor.performTyping(new BrailleCharacter("12"));
    assertEquals("@Ana b", fixture.editor.getText().toString());
    assertTrue(fixture.sent.isEmpty());
  }

  private static final class Fixture implements BrailleImeActor.Callback {
    final EditText editor;
    final ImeConnection imeConnection;
    final EditBuffer buffer;
    final BrailleImeActor actor;
    final List<String> sent = new ArrayList<>();
    boolean acceptSend = true;
    int hideCount;
    boolean suggestions;
    int confirmationCount;
    final List<Boolean> suggestionMoves = new ArrayList<>();

    Fixture(boolean contracted) {
      this(contracted, false);
    }

    Fixture(boolean contracted, boolean autoCapitalize) {
      Context context = RuntimeEnvironment.getApplication();
      BrailleUserPreferences.writeAutoCapitalize(context, autoCapitalize);
      editor = new EditText(context);
      editor.setInputType(InputType.TYPE_CLASS_TEXT);
      EditorInfo info = new EditorInfo();
      InputConnection connection =
          new InputConnectionWrapper(editor.onCreateInputConnection(info), false) {
            @Override
            public boolean performEditorAction(int action) {
              if (!acceptSend) {
                return false;
              }
              sent.add(editor.getText().toString());
              editor.setText("");
              return true;
            }
          };
      info.imeOptions = EditorInfo.IME_ACTION_SEND;
      imeConnection = new ImeConnection(connection, info, ImeConnection.AnnounceType.SILENCE);
      TalkBackSpeaker speaker = (text, delay, options) -> {};
      BrailleTranslator translator = new LetterTranslator();
      buffer =
          contracted
              ? new EditBufferSpanish2(context, translator, speaker)
              : new EditBufferSpanish(context, translator, speaker);
      TalkBackForBrailleIme talkBack =
          (TalkBackForBrailleIme)
              Proxy.newProxyInstance(
                  TalkBackForBrailleIme.class.getClassLoader(),
                  new Class<?>[] {TalkBackForBrailleIme.class},
                  (proxy, method, args) -> {
                    switch (method.getName()) {
                      case "isCurrentGranularitySuggestions": return suggestions;
                      case "navigateSuggestion": suggestionMoves.add((Boolean) args[0]); return true;
                      case "confirmSuggestion": confirmationCount++; suggestions = false; return true;
                      case "resetGranularity": suggestions = false; return null;
                      default: return method.getReturnType() == boolean.class ? false : null;
                    }
                  });
      actor = new BrailleImeActor(context, this, talkBack, null);
    }

    @Override
    public void performEditorAction() {
      imeConnection.inputConnection.performEditorAction(EditorInfo.IME_ACTION_SEND);
    }

    @Override
    public EditBuffer getEditBuffer() {
      return buffer;
    }

    @Override
    public ImeConnection getImeConnection() {
      return imeConnection;
    }

    @Override
    public boolean isConnectionValid() {
      return true;
    }

    @Override
    public void hideBrailleKeyboard() {
      hideCount++;
    }

    @Override
    public void switchToNextInputMethod() {}

    @Override
    public void showContextMenu() {}

    @Override
    public void toggleOrientationLock() {}

    @Override
    public void startVoiceInput() {}

    @Override
    public void toggleEmojiMode() {}
  }

  /** Deterministic letters keep the test independent of the native translation library. */
  private static final class LetterTranslator implements BrailleTranslator {
    @Override
    public String translateToPrint(BrailleWord word) {
      StringBuilder result = new StringBuilder();
      boolean upper = false;
      for (int i = 0; i < word.size(); i++) {
        BrailleCharacter ch = word.get(i);
        if (ch.equals(BrailleTranslateUtils.DOTS46)) {
          upper = true;
          continue;
        }
        if (ch.equals(new BrailleCharacter("256"))) {
          result.append(".");
          continue;
        }
        String letter = ch.equals(new BrailleCharacter("1")) ? "a" : "b";
        result.append(upper ? letter.toUpperCase() : letter);
        upper = false;
      }
      return result.toString();
    }

    @Override
    public String translateToPrintPartial(BrailleWord word) {
      return translateToPrint(word);
    }

    @Override
    public TranslationResult translate(CharSequence text, int cursorPosition) {
      throw new UnsupportedOperationException();
    }
  }
}
