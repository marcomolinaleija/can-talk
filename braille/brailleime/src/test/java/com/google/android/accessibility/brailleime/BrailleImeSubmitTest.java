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
import com.google.android.accessibility.braille.common.ImeConnection;
import com.google.android.accessibility.braille.common.TalkBackSpeaker;
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

  private static final class Fixture implements BrailleImeActor.Callback {
    final EditText editor;
    final ImeConnection imeConnection;
    final EditBuffer buffer;
    final BrailleImeActor actor;
    final List<String> sent = new ArrayList<>();
    boolean acceptSend = true;
    int hideCount;

    Fixture(boolean contracted) {
      Context context = RuntimeEnvironment.getApplication();
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
                  (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
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
  }

  /** Deterministic letters keep the test independent of the native translation library. */
  private static final class LetterTranslator implements BrailleTranslator {
    @Override
    public String translateToPrint(BrailleWord word) {
      StringBuilder result = new StringBuilder();
      for (int i = 0; i < word.size(); i++) {
        result.append(word.get(i).equals(new BrailleCharacter("1")) ? "a" : "b");
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
