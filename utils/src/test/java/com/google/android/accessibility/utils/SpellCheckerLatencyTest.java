/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.utils;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.SuggestionSpan;
import android.view.textservice.*;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 30, 34}, shadows = {
    SpellCheckerLatencyTest.Services.class, SpellCheckerLatencyTest.Session.class})
public class SpellCheckerLatencyTest {
  @Implements(TextServicesManager.class)
  public static class Services {
    static final List<Session> sessions = new ArrayList<>();

    @Implementation
    protected SpellCheckerSession newSpellCheckerSession(Bundle extras, Locale locale,
        SpellCheckerSession.SpellCheckerSessionListener listener, boolean referToSettings) {
      SpellCheckerSession session = Shadow.newInstanceOf(SpellCheckerSession.class);
      Session state = Shadow.extract(session);
      state.listener = listener;
      sessions.add(state);
      return session;
    }
  }

  @Implements(SpellCheckerSession.class)
  public static class Session {
    SpellCheckerSession.SpellCheckerSessionListener listener;
    boolean closed;
    String submitted;
    @Implementation protected void getSentenceSuggestions(TextInfo[] text, int limit) {
      submitted = text[0].getText(); // Deliberately never answers until the test supplies a result.
    }
    @Implementation protected void close() { closed = true; }
    void answer() { listener.onGetSentenceSuggestions(new SentenceSuggestionsInfo[0]); }
  }

  private final Context context = RuntimeEnvironment.getApplication();

  @Before public void setup() {
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
    SpellChecker.setEnabled(true);
    SpellChecker.updateCachedResult(null);
    SpellChecker.checkInBackground(context, "", text -> {});
    Services.sessions.clear();
  }

  @Test public void unresponsiveCheckerDoesNotWaitAndClosesAtTimeout() {
    List<CharSequence> replies = new ArrayList<>();
    long start = System.nanoTime();
    SpellChecker.checkInBackground(context, "typing", replies::add);
    assertTrue("Typing must not wait for the 500 ms synchronous timeout",
        System.nanoTime() - start < 400_000_000L);
    assertEquals(1, Services.sessions.size());
    assertTrue(replies.isEmpty());
    Session session = Services.sessions.get(0);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
    assertTrue(session.closed);
    session.answer();
    assertTrue("Late results must not produce a hint", replies.isEmpty());
  }

  @Test public void newCheckCancelsPreviousAndOnlyLatestResultIsDelivered() {
    List<CharSequence> replies = new ArrayList<>();
    SpellChecker.checkInBackground(context, "first", replies::add);
    Session first = Services.sessions.get(0);
    SpellChecker.checkInBackground(context, "second", replies::add);
    assertTrue(first.closed);
    first.answer();
    assertTrue(replies.isEmpty());
    Session second = Services.sessions.get(1);
    second.answer();
    assertTrue(second.closed);
    assertEquals("second", replies.get(0).toString());
    assertEquals(1, replies.size());
  }

  @Test public void cachedResultAlsoCancelsOlderCheck() {
    List<CharSequence> replies = new ArrayList<>();
    SpellChecker.checkInBackground(context, "pending", replies::add);
    SpellChecker.updateCachedResult(new SpannableString("cached"));
    SpellChecker.checkInBackground(context, "cached", replies::add);
    assertTrue(Services.sessions.get(0).closed);
    Services.sessions.get(0).answer();
    assertEquals(1, replies.size());
    assertEquals("cached", replies.get(0).toString());
  }

  @Test public void combinedNoSuggestionsAndEveryPasswordTypeSkipSpellCheck() {
    int[] types = {
      InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
      InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
      InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
      InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
      InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD,
      InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
          | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    };
    for (int type : types) {
      assertTrue(AccessibilityNodeInfoUtils.hasNoSuggestionsNeed(type));
      AccessibilityNodeInfoCompat node = nodeWithTypo();
      node.setInputType(type);
      assertTrue(AccessibilityNodeInfoUtils.getSpellingSuggestions(context, node).isEmpty());
      assertTrue(AccessibilityNodeInfoUtils.getSpellingSuggestions(context, node, 4).isEmpty());
      assertEquals(0, AccessibilityNodeInfoUtils.getTypoCount(context, node));
      assertEquals("helo", SpellChecker.getTextWithSuggestionSpans(context, node).toString());
    }
    assertTrue(Services.sessions.isEmpty());
  }

  @Test public void passwordFlagAndSelectedTextSkipEverySuggestionPath() {
    AccessibilityNodeInfoCompat node = nodeWithTypo();
    node.setPassword(true);
    assertTrue(AccessibilityNodeInfoUtils.getSpellingSuggestions(context, node, 4).isEmpty());
    assertEquals(0, AccessibilityNodeInfoUtils.getTypoCount(context, node));
    node.setPassword(false);
    node.setTextSelection(0, 4);
    assertTrue(AccessibilityNodeInfoUtils.getSpellingSuggestions(context, node, 4).isEmpty());
    assertTrue(Services.sessions.isEmpty());
  }

  @Test public void ordinaryTextKeepsExistingSpellingSuggestionsWithoutARequest() {
    AccessibilityNodeInfoCompat node = nodeWithTypo();
    assertFalse(AccessibilityNodeInfoUtils.hasNoSuggestionsNeed(node.getInputType()));
    assertEquals(1,
        AccessibilityNodeInfoUtils.getSpellingSuggestions(context, node, 4, false).size());
    assertEquals(1, AccessibilityNodeInfoUtils.getSpellingSuggestions(node.getText(), 4).size());
    assertTrue(Services.sessions.isEmpty());
  }

  private AccessibilityNodeInfoCompat nodeWithTypo() {
    AccessibilityNodeInfoCompat node = AccessibilityNodeInfoCompat.obtain();
    node.setInputType(InputType.TYPE_CLASS_TEXT);
    SpannableString text = new SpannableString("helo");
    text.setSpan(new SuggestionSpan(context, new String[] {"hello"}, SuggestionSpan.FLAG_MISSPELLED),
        0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    node.setText(text);
    node.setTextSelection(4, 4);
    return node;
  }
}
