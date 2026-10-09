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

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;
import com.google.android.accessibility.braille.common.BrailleCommonTalkBackSpeaker;
import com.google.android.accessibility.braille.common.BrailleUserPreferences;
import com.google.android.accessibility.braille.common.TalkBackSpeaker.AnnounceType;
import java.util.ArrayList;

/** Manages speech-to-text dictation for the Braille keyboard. */
public class VoiceInputManager {
  private static final String TAG = "VoiceInputManager";

  /** Callback interface for voice input events. */
  public interface Callback {
    /** Called when voice input recognized text. */
    void onSpeechResult(String text);

    /** Called when listening state changes. */
    void onListeningStateChanged(boolean listening);
  }

  private final Context context;
  private final Callback callback;
  private @Nullable SpeechRecognizer speechRecognizer;
  private boolean isListening = false;

  public VoiceInputManager(Context context, Callback callback) {
    this.context = context;
    this.callback = callback;
  }

  public boolean isListening() {
    return isListening;
  }

  /** Toggles voice input dictation on or off. */
  public void toggleVoiceInput() {
    if (isListening) {
      stopListening();
    } else {
      startListening();
    }
  }

  /** Starts listening for speech dictation. */
  public void startListening() {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      BrailleCommonTalkBackSpeaker.getInstance()
          .speak(
              context.getString(R.string.voice_input_not_available),
              AnnounceType.INTERRUPT);
      return;
    }

    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      requestMicPermission();
      return;
    }

    try {
      if (speechRecognizer == null) {
        speechRecognizer = createSpeechRecognizer();
        speechRecognizer.setRecognitionListener(recognitionListener);
      }
      Intent intent = createRecognizerIntent();
      isListening = true;
      callback.onListeningStateChanged(true);
      BrailleCommonTalkBackSpeaker.getInstance()
          .speak(context.getString(R.string.voice_input_listening), AnnounceType.INTERRUPT);
      speechRecognizer.startListening(intent);
    } catch (Exception e) {
      BrailleImeLog.e(TAG, "Failed to start speech recognition", e);
      cleanUp();
      BrailleCommonTalkBackSpeaker.getInstance()
          .speak(
              context.getString(R.string.voice_input_not_available),
              AnnounceType.INTERRUPT);
    }
  }

  /** Finishes listening and waits for results. */
  public void stopListening() {
    if (speechRecognizer != null && isListening) {
      try {
        speechRecognizer.stopListening();
      } catch (Exception e) {
        BrailleImeLog.e(TAG, "Error stopping speech recognizer", e);
      }
    }
  }

  /** Cancels the current dictation session without committing results. */
  public void cancel() {
    if (speechRecognizer != null && isListening) {
      try {
        speechRecognizer.cancel();
      } catch (Exception e) {
        BrailleImeLog.e(TAG, "Error cancelling speech recognizer", e);
      }
    }
    cleanUp();
  }

  /** Destroys the recognizer and cleans up resources. */
  public void destroy() {
    cleanUp();
  }

  private void cleanUp() {
    isListening = false;
    callback.onListeningStateChanged(false);
    if (speechRecognizer != null) {
      try {
        speechRecognizer.destroy();
      } catch (Exception e) {
        BrailleImeLog.e(TAG, "Error destroying speech recognizer", e);
      }
      speechRecognizer = null;
    }
  }

  @VisibleForTesting
  protected SpeechRecognizer createSpeechRecognizer() {
    return SpeechRecognizer.createSpeechRecognizer(context);
  }

  @VisibleForTesting
  protected Intent createRecognizerIntent() {
    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    intent.putExtra(
        RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
    intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
    boolean allowProfanity = BrailleUserPreferences.readVoiceAllowProfanity(context);
    intent.putExtra("android.speech.extra.PROFANITY_FILTER", !allowProfanity);
    intent.putExtra("profanity_filter", !allowProfanity);
    intent.putExtra("android.speech.extra.DICTATION_MODE", true);
    return intent;
  }

  private void requestMicPermission() {
    BrailleCommonTalkBackSpeaker.getInstance()
        .speak(
            context.getString(R.string.voice_input_permission_required),
            AnnounceType.INTERRUPT);
    try {
      Intent intent = new Intent();
      intent.setComponent(
          new ComponentName(
              context.getPackageName(),
              "com.google.android.accessibility.talkback.permission.PermissionRequestActivity"));
      intent.putExtra("permissions", new String[] {Manifest.permission.RECORD_AUDIO});
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      context.startActivity(intent);
    } catch (Exception e) {
      BrailleImeLog.e(TAG, "Unable to launch PermissionRequestActivity", e);
    }
  }

  @VisibleForTesting
  RecognitionListener getRecognitionListener() {
    return recognitionListener;
  }

  private final RecognitionListener recognitionListener =
      new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
          isListening = true;
        }

        @Override
        public void onBeginningOfSpeech() {}

        @Override
        public void onRmsChanged(float rmsdB) {}

        @Override
        public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {}

        @Override
        public void onError(int error) {
          BrailleImeLog.w(TAG, "SpeechRecognizer error: " + error);
          if (error == SpeechRecognizer.ERROR_NO_MATCH
              || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            BrailleCommonTalkBackSpeaker.getInstance()
                .speak(
                    context.getString(R.string.voice_input_no_speech),
                    AnnounceType.INTERRUPT);
          } else {
            BrailleCommonTalkBackSpeaker.getInstance()
                .speak(
                    context.getString(R.string.voice_input_not_available),
                    AnnounceType.INTERRUPT);
          }
          cleanUp();
        }

        @Override
        public void onResults(Bundle results) {
          if (results != null) {
            ArrayList<String> matches =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
              String text = matches.get(0);
              if (text != null && !text.isEmpty()) {
                callback.onSpeechResult(text);
              }
            } else {
              BrailleCommonTalkBackSpeaker.getInstance()
                  .speak(
                      context.getString(R.string.voice_input_no_speech),
                      AnnounceType.INTERRUPT);
            }
          }
          cleanUp();
        }

        @Override
        public void onPartialResults(Bundle partialResults) {}

        @Override
        public void onEvent(int eventType, Bundle params) {}
      };
}
