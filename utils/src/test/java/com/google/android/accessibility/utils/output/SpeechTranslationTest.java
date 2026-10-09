/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.utils.output;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import com.google.android.accessibility.utils.Performance.EventId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** Exercises the actual queue with a controllable transformer and a non-speaking TTS boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SpeechTranslationTest {
  private SpeechControllerImpl speech;
  private FakeTts tts;
  private Transformer transformer;

  private static class FakeTts extends FailoverTextToSpeech {
    FailoverTtsListener listener;
    final List<String> spoken = new ArrayList<>();
    String utterance;
    Locale locale;
    Map<String, String> params;
    FakeTts(Context context) { super(context, false); }
    @Override public boolean isReady() { return true; }
    @Override public void addListener(FailoverTtsListener value) { listener = value; }
    @Override public void speak(CharSequence text, Locale locale, float pitch, float rate,
        Map<String, String> params, Map<String, Integer> flags, int stream, float volume,
        boolean preventSleep, boolean flush, EventId event) {
      spoken.add(text == null ? "" : text.toString());
      this.locale = locale;
      this.params = new HashMap<>(params);
      utterance = params.get(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID);
      listener.onUtteranceStarted(utterance);
    }
    void finish() { listener.onUtteranceCompleted(utterance, true); }
    @Override public void stopFromTalkBack() {}
  }

  private static class Transformer implements SpeechTextTransformer {
    final List<Callback> callbacks = new ArrayList<>();
    int cancelled;
    boolean closed;
    boolean enabled = true;
    @Override public void capture(FeedbackItem item) { item.setTranslationPackage("example.app"); }
    @Override public boolean shouldTransform(FeedbackItem item) { return enabled; }
    @Override public Runnable transform(FeedbackItem item, Callback callback) {
      callbacks.add(callback);
      return () -> cancelled++;
    }
    @Override public void close() { closed = true; }
    void complete(int index, String text) {
      callbacks.get(index).ready(text == null ? null : new Result(text, Locale.forLanguageTag("es")));
      Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
  }

  @Before public void setup() {
    Context context = RuntimeEnvironment.getApplication();
    tts = new FakeTts(context);
    SpeechController.Delegate delegate = new SpeechController.Delegate() {
      public boolean isAudioPlaybackActive() { return false; }
      public boolean isMicrophoneActiveAndHeadphoneOff() { return false; }
      public boolean isSsbActive() { return false; }
      public boolean isPhoneCallActive() { return false; }
      public void onSpeakingForcedFeedback() {}
    };
    speech = new SpeechControllerImpl(context, delegate, null, tts, false);
    transformer = new Transformer();
    speech.setTextTransformer(transformer);
  }
  @After public void cleanup() { if (speech != null) speech.shutdown(); }
  private void say(String text, int queue) {
    speech.speak(text, null, SpeechController.SpeakOptions.create().setQueueMode(queue));
  }

  @Test public void waitsForResultAndKeepsOriginalHistoryAndLocale() {
    say("hello", SpeechController.QUEUE_MODE_QUEUE);
    assertTrue(tts.spoken.isEmpty());
    assertNull(speech.getLastUtterance());
    transformer.complete(0, "hola");
    assertEquals(List.of("hola"), tts.spoken);
    assertEquals("hello", speech.getLastUtterance().getTranslationSource());
    assertTrue(speech.getLastUtterance().isTranslationApplied());
    assertEquals("es", tts.locale.getLanguage());
    assertEquals("es", tts.params.get(SpeechTextTransformer.FORCE_LOCALE));
  }
  @Test public void fallbackSpeaksOriginal() {
    say("hello", SpeechController.QUEUE_MODE_QUEUE);
    transformer.complete(0, null);
    assertEquals(List.of("hello"), tts.spoken);
    assertFalse(speech.getLastUtterance().isTranslationApplied());
  }
  @Test public void interruptRejectsOldResultAndRetainsNewResult() {
    say("old", SpeechController.QUEUE_MODE_QUEUE);
    say("new", SpeechController.QUEUE_MODE_INTERRUPT);
    assertEquals(1, transformer.cancelled);
    transformer.complete(0, "obsolete");
    assertTrue(tts.spoken.isEmpty());
    transformer.complete(1, "nuevo");
    assertEquals(List.of("nuevo"), tts.spoken);
  }
  @Test public void queuedItemsKeepOrderAndCompletionCallbacks() {
    List<Integer> completions = new ArrayList<>();
    speech.speak("first", null, SpeechController.SpeakOptions.create()
        .setQueueMode(SpeechController.QUEUE_MODE_QUEUE).setCompletedAction(completions::add));
    say("second", SpeechController.QUEUE_MODE_QUEUE);
    assertEquals(1, transformer.callbacks.size());
    transformer.complete(0, "primero");
    tts.finish();
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertEquals(List.of(SpeechController.STATUS_SPOKEN), completions);
    assertEquals(2, transformer.callbacks.size());
    transformer.complete(1, "segundo");
    assertEquals(List.of("primero", "segundo"), tts.spoken);
  }
  @Test public void menuSnapshotRetainsOriginalDespiteAnnouncements() {
    say("hello", SpeechController.QUEUE_MODE_QUEUE);
    transformer.complete(0, "hola");
    speech.saveLastUtterance();
    say("menu", SpeechController.QUEUE_MODE_INTERRUPT);
    transformer.complete(1, "menú");
    assertEquals("hello", speech.getSavedUtterance().getTranslationSource());
  }
  @Test public void muteCancelsPendingTranslation() {
    say("hello", SpeechController.QUEUE_MODE_QUEUE);
    speech.setMute(true);
    transformer.complete(0, "late");
    assertEquals(1, transformer.cancelled);
    assertTrue(tts.spoken.isEmpty());
  }
  @Test public void silenceCancelsPendingTranslation() {
    say("hello", SpeechController.QUEUE_MODE_QUEUE);
    speech.setSilenceSpeech(true);
    transformer.complete(0, "late");
    assertEquals(1, transformer.cancelled);
    assertTrue(tts.spoken.isEmpty());
  }
  @Test public void screenOffGuardDoesNotInterruptOrdinarySpeech() {
    transformer.enabled = false;
    say("hello", SpeechController.QUEUE_MODE_QUEUE);
    speech.interruptPendingTextTransformation();
    assertEquals("hello", speech.getLastUtterance().getTranslationSource());
    assertTrue(speech.isSpeaking());
  }
  @Test public void shutdownCancelsAndIgnoresLateResults() {
    say("hello", SpeechController.QUEUE_MODE_QUEUE);
    speech.shutdown();
    transformer.complete(0, "late");
    assertEquals(1, transformer.cancelled);
    assertTrue(transformer.closed);
    assertTrue(tts.spoken.isEmpty());
    speech = null;
  }
}
