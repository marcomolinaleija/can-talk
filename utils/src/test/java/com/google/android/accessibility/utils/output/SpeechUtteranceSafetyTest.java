/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.utils.output;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Regressions fixed by upstream #313, exercised with low-latency audio disabled. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 34})
public class SpeechUtteranceSafetyTest {
  private Context context;
  private FailoverTextToSpeech speaker;
  private SpeechCacheManager cache;
  private HandlerThread worker;

  @Before public void setUp() {
    context = RuntimeEnvironment.getApplication();
    SharedPreferencesUtils.getSharedPreferences(context).edit().clear()
        .putBoolean(FailoverTextToSpeech.PREF_LOW_LATENCY_AUDIO_KEY, false).commit();
    speaker = new FailoverTextToSpeech(context);
  }

  @After public void tearDown() throws Exception {
    speaker.shutdown();
    if (cache != null) cache.shutDown();
    if (worker != null) worker.join(1000);
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static Object invoke(Object target, String name, Class<?> type, Object value)
      throws Exception {
    Method method = target.getClass().getDeclaredMethod(name, type);
    method.setAccessible(true);
    return method.invoke(target, value);
  }

  @Test public void nullSpeechIdDoesNotEnterRecentHistory() throws Exception {
    invoke(speaker, "addRecentUtteranceId", String.class, null);
    invoke(speaker, "addRecentUtteranceId", String.class, "talkback_1");
    assertEquals(List.of("talkback_1"), speaker.getRecentUtteranceIds());
  }

  @Test public void engineStartWithNoIdIsIgnored() throws Exception {
    Object callback = field(speaker, "utteranceProgressCallback");
    Method onStart = callback.getClass().getMethod("onStart", String.class);
    onStart.setAccessible(true);
    onStart.invoke(callback, new Object[] {null});
    shadowOf(Looper.getMainLooper()).idle();
  }

  @Test public void missingControllerIdIsInvalidInsteadOfThrowing() throws Exception {
    Method parse = SpeechControllerImpl.class.getDeclaredMethod("parseUtteranceId", String.class);
    parse.setAccessible(true);
    assertEquals(-1, parse.invoke(null, new Object[] {null}));
    assertEquals(-1, parse.invoke(null, "another_app_1"));
    assertEquals(7, parse.invoke(null, "talkback_7"));
  }

  private SpeechCacheManager cache() {
    worker = new HandlerThread("SpeechSafetyTest");
    worker.start();
    cache = new SpeechCacheManager(context, worker.getLooper());
    return cache;
  }

  @Test public void cacheDoneWithoutIdIsIgnored() { assertFalse(cache().handleOnDone(null)); }
  @Test public void cacheErrorWithoutIdIsIgnored() { assertFalse(cache().handleOnError(null)); }
  @Test public void cacheStopWithoutIdIsIgnored() { assertFalse(cache().handleOnStop(null, true)); }

  private static class RecordingTts extends TextToSpeech {
    final List<String> ids = new ArrayList<>();
    RecordingTts(Context context) { super(context, null); }
    @Override public int speak(CharSequence text, int mode, Bundle bundle, String id) {
      ids.add(id);
      return SUCCESS;
    }
  }

  @SuppressWarnings("unchecked")
  private void finishCachedSpeech(int state) throws Exception {
    RecordingTts tts = new RecordingTts(context);
    Field engine = FailoverTextToSpeech.class.getDeclaredField("tts");
    engine.setAccessible(true);
    engine.set(speaker, tts);
    List<FailoverTextToSpeech.SpeakRequest> queued =
        (List<FailoverTextToSpeech.SpeakRequest>) field(speaker, "suspendQueue");
    queued.add(new FailoverTextToSpeech.SpeakRequest(Locale.US, "Queued announcement",
        TextToSpeech.QUEUE_ADD, new Bundle(), "talkback_7"));
    SpeechCachePlayer.PlaybackStateListener callback =
        (SpeechCachePlayer.PlaybackStateListener) field(speaker, "playbackStateListener");
    callback.onStateChanged("cached_previous", state);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(List.of("talkback_7"), tts.ids);
    assertTrue(queued.isEmpty());
  }

  @Test public void queuedSpeechKeepsItsOwnIdWhenCachedSpeechEnds() throws Exception {
    finishCachedSpeech(SpeechCachePlayer.STATE_COMPLETED);
  }

  @Test public void queuedSpeechStillRunsWhenCachedSpeechFails() throws Exception {
    finishCachedSpeech(SpeechCachePlayer.STATE_ERROR);
  }
}
