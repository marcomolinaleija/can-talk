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
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import com.google.android.accessibility.braille.common.BrailleImeAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Tests for {@link VoiceInputManager}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VoiceInputManagerTest {

  private Context context;
  private AtomicReference<String> recognizedText;
  private AtomicBoolean listeningState;
  private VoiceInputManager voiceInputManager;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    recognizedText = new AtomicReference<>(null);
    listeningState = new AtomicBoolean(false);

    voiceInputManager =
        new VoiceInputManager(
            context,
            new VoiceInputManager.Callback() {
              @Override
              public void onSpeechResult(String text) {
                recognizedText.set(text);
              }

              @Override
              public void onListeningStateChanged(boolean listening) {
                listeningState.set(listening);
              }
            }) {
          @Override
          protected SpeechRecognizer createSpeechRecognizer() {
            return SpeechRecognizer.createSpeechRecognizer(context);
          }
        };
  }

  @Test
  public void isListening_initiallyFalse() {
    assertFalse(voiceInputManager.isListening());
  }

  @Test
  public void recognitionListener_onResults_deliversTextToCallback() {
    RecognitionListener listener = voiceInputManager.getRecognitionListener();
    Bundle bundle = new Bundle();
    bundle.putStringArrayList(
        SpeechRecognizer.RESULTS_RECOGNITION,
        new ArrayList<>(Collections.singletonList("hola mundo")));

    listener.onResults(bundle);

    assertEquals("hola mundo", recognizedText.get());
    assertFalse(voiceInputManager.isListening());
  }

  @Test
  public void recognitionListener_onError_cleansUpListeningState() {
    RecognitionListener listener = voiceInputManager.getRecognitionListener();
    listener.onError(SpeechRecognizer.ERROR_NO_MATCH);

    assertFalse(voiceInputManager.isListening());
    assertFalse(listeningState.get());
  }

  @Test
  public void cancel_resetsListeningState() {
    voiceInputManager.cancel();
    assertFalse(voiceInputManager.isListening());
    assertFalse(listeningState.get());
  }

  @Test
  public void destroy_resetsListeningState() {
    voiceInputManager.destroy();
    assertFalse(voiceInputManager.isListening());
  }

  @Test
  public void supportedCommand_includesVoiceInput() {
    boolean found =
        SupportedCommand.getSupportedCommands(context).stream()
            .anyMatch(cmd -> cmd.getBrailleImeAction() == BrailleImeAction.VOICE_INPUT);
    assertTrue(found);
  }

  @Test
  public void brailleImeGestureAction_mapsDefaultGestureForVoiceInput() {
    assertTrue(
        BrailleImeGestureAction.getAction(
                context,
                new com.google.android.accessibility.brailleime.input.DotHoldSwipe(
                    new com.google.android.accessibility.brailleime.input.Swipe(
                        com.google.android.accessibility.brailleime.input.Swipe.Direction.DOWN,
                        1),
                    new com.google.android.accessibility.braille.interfaces.BrailleCharacter(4, 5, 6)))
            .contains(BrailleImeAction.VOICE_INPUT));
  }
}
