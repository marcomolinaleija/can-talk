/*
 * Copyright 2023 Google Inc.
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

import static com.google.android.accessibility.braille.common.BrailleImeAction.ADD_NEWLINE;
import static com.google.android.accessibility.braille.common.BrailleImeAction.ADD_SPACE_OR_NEXT_ITEM;
import static com.google.android.accessibility.braille.common.BrailleImeAction.BEGINNING_OF_PAGE;
import static com.google.android.accessibility.braille.common.BrailleImeAction.COPY;
import static com.google.android.accessibility.braille.common.BrailleImeAction.CUT;
import static com.google.android.accessibility.braille.common.BrailleImeAction.DELETE_CHARACTER_OR_PREVIOUS_ITEM;
import static com.google.android.accessibility.braille.common.BrailleImeAction.DELETE_WORD;
import static com.google.android.accessibility.braille.common.BrailleImeAction.END_OF_PAGE;
import static com.google.android.accessibility.braille.common.BrailleImeAction.HELP_AND_OTHER_ACTIONS;
import static com.google.android.accessibility.braille.common.BrailleImeAction.HIDE_KEYBOARD;
import static com.google.android.accessibility.braille.common.BrailleImeAction.MOVE_CURSOR_BACKWARD;
import static com.google.android.accessibility.braille.common.BrailleImeAction.MOVE_CURSOR_FORWARD;
import static com.google.android.accessibility.braille.common.BrailleImeAction.NEXT_CHARACTER;
import static com.google.android.accessibility.braille.common.BrailleImeAction.NEXT_GRANULARITY;
import static com.google.android.accessibility.braille.common.BrailleImeAction.NEXT_LINE;
import static com.google.android.accessibility.braille.common.BrailleImeAction.NEXT_WORD;
import static com.google.android.accessibility.braille.common.BrailleImeAction.PASTE;
import static com.google.android.accessibility.braille.common.BrailleImeAction.PREVIOUS_CHARACTER;
import static com.google.android.accessibility.braille.common.BrailleImeAction.PREVIOUS_GRANULARITY;
import static com.google.android.accessibility.braille.common.BrailleImeAction.PREVIOUS_LINE;
import static com.google.android.accessibility.braille.common.BrailleImeAction.PREVIOUS_WORD;
import static com.google.android.accessibility.braille.common.BrailleImeAction.SELECT_ALL;
import static com.google.android.accessibility.braille.common.BrailleImeAction.SELECT_CURRENT_TO_END;
import static com.google.android.accessibility.braille.common.BrailleImeAction.SELECT_CURRENT_TO_START;
import static com.google.android.accessibility.braille.common.BrailleImeAction.SELECT_NEXT_CHARACTER;
import static com.google.android.accessibility.braille.common.BrailleImeAction.SELECT_NEXT_LINE;
import static com.google.android.accessibility.braille.common.BrailleImeAction.SELECT_NEXT_WORD;
import static com.google.android.accessibility.braille.common.BrailleImeAction.SELECT_PREVIOUS_CHARACTER;
import static com.google.android.accessibility.braille.common.BrailleImeAction.SELECT_PREVIOUS_LINE;
import static com.google.android.accessibility.braille.common.BrailleImeAction.SELECT_PREVIOUS_WORD;
import static com.google.android.accessibility.braille.common.BrailleImeAction.SUBMIT_TEXT;
import static com.google.android.accessibility.braille.common.BrailleImeAction.SWITCH_KEYBOARD;
import static com.google.android.accessibility.braille.common.BrailleImeAction.TOGGLE_ORIENTATION_LOCK;
import static com.google.android.accessibility.braille.common.BrailleImeAction.TOGGLE_EMOJI_MODE;
import static com.google.android.accessibility.braille.common.BrailleImeAction.VOICE_INPUT;

import android.content.Context;
import androidx.annotation.Nullable;
import com.google.android.accessibility.braille.common.BrailleImeAction;
import com.google.android.accessibility.braille.common.BrailleUserPreferences;
import com.google.android.accessibility.braille.interfaces.BrailleCharacter;
import com.google.android.accessibility.brailleime.input.DotHoldSwipe;
import com.google.android.accessibility.brailleime.input.Gesture;
import com.google.android.accessibility.brailleime.input.Swipe;
import com.google.android.accessibility.brailleime.input.Swipe.Direction;
import com.google.android.accessibility.brailleime.input.UnassignedGesture;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/** Collection of braille gestures and actions. */
public final class BrailleImeGestureAction {
  private static final String TAG = "BrailleImeGestureAction";
  private static final int MAX_SWIPE_TOUCH_POINT = 3;
  // We assume users are right-handed by default, meaning they would hold the dots with their left
  // fingers and swipe with their right.
  private static final ImmutableMap<BrailleImeAction, Gesture> DEFAULT_ACTION_GESTURE =
      ImmutableMap.<BrailleImeAction, Gesture>builder()
          .put(MOVE_CURSOR_BACKWARD, new Swipe(Direction.UP, /* touchCount= */ 1))
          .put(MOVE_CURSOR_FORWARD, new Swipe(Direction.DOWN, /* touchCount= */ 1))
          .put(ADD_SPACE_OR_NEXT_ITEM, new Swipe(Direction.RIGHT, /* touchCount= */ 1))
          .put(DELETE_CHARACTER_OR_PREVIOUS_ITEM, new Swipe(Direction.LEFT, /* touchCount= */ 1))
          .put(SUBMIT_TEXT, new Swipe(Direction.UP, /* touchCount= */ 2))
          .put(HIDE_KEYBOARD, new Swipe(Direction.DOWN, /* touchCount= */ 2))
          .put(ADD_NEWLINE, new Swipe(Direction.RIGHT, /* touchCount= */ 2))
          .put(DELETE_WORD, new Swipe(Direction.LEFT, /* touchCount= */ 2))
          .put(HELP_AND_OTHER_ACTIONS, new Swipe(Direction.UP, /* touchCount= */ 3))
          .put(SWITCH_KEYBOARD, new Swipe(Direction.DOWN, /* touchCount= */ 3))
          .put(NEXT_GRANULARITY, new Swipe(Direction.RIGHT, /* touchCount= */ 3))
          .put(PREVIOUS_GRANULARITY, new Swipe(Direction.LEFT, /* touchCount= */ 3))
          .put(
              PREVIOUS_LINE,
              new DotHoldSwipe(
                  new Swipe(Direction.UP, /* touchCount= */ 1), new BrailleCharacter(4)))
          .put(
              NEXT_LINE,
              new DotHoldSwipe(
                  new Swipe(Direction.DOWN, /* touchCount= */ 1), new BrailleCharacter(4)))
          .put(
              SELECT_PREVIOUS_LINE,
              new DotHoldSwipe(
                  new Swipe(Direction.UP, /* touchCount= */ 2), new BrailleCharacter(4)))
          .put(
              SELECT_NEXT_LINE,
              new DotHoldSwipe(
                  new Swipe(Direction.DOWN, /* touchCount= */ 2), new BrailleCharacter(4)))
          .put(
              CUT,
              new DotHoldSwipe(
                  new Swipe(Direction.UP, /* touchCount= */ 3), new BrailleCharacter(4)))
          .put(
              COPY,
              new DotHoldSwipe(
                  new Swipe(Direction.DOWN, /* touchCount= */ 3), new BrailleCharacter(4)))
          .put(
              PASTE,
              new DotHoldSwipe(
                  new Swipe(Direction.RIGHT, /* touchCount= */ 3), new BrailleCharacter(4)))
          .put(
              SELECT_ALL,
              new DotHoldSwipe(
                  new Swipe(Direction.LEFT, /* touchCount= */ 3), new BrailleCharacter(4)))
          .put(
              SELECT_CURRENT_TO_START,
              new DotHoldSwipe(
                  new Swipe(Direction.UP, /* touchCount= */ 2), new BrailleCharacter(4, 5)))
          .put(
              SELECT_CURRENT_TO_END,
              new DotHoldSwipe(
                  new Swipe(Direction.DOWN, /* touchCount= */ 2), new BrailleCharacter(4, 5)))
          .put(
              PREVIOUS_WORD,
              new DotHoldSwipe(
                  new Swipe(Direction.UP, /* touchCount= */ 1), new BrailleCharacter(5)))
          .put(
              NEXT_WORD,
              new DotHoldSwipe(
                  new Swipe(Direction.DOWN, /* touchCount= */ 1), new BrailleCharacter(5)))
          .put(
              SELECT_PREVIOUS_WORD,
              new DotHoldSwipe(
                  new Swipe(Direction.UP, /* touchCount= */ 2), new BrailleCharacter(5)))
          .put(
              SELECT_NEXT_WORD,
              new DotHoldSwipe(
                  new Swipe(Direction.DOWN, /* touchCount= */ 2), new BrailleCharacter(5)))
          .put(
              PREVIOUS_CHARACTER,
              new DotHoldSwipe(
                  new Swipe(Direction.UP, /* touchCount= */ 1), new BrailleCharacter(6)))
          .put(
              NEXT_CHARACTER,
              new DotHoldSwipe(
                  new Swipe(Direction.DOWN, /* touchCount= */ 1), new BrailleCharacter(6)))
          .put(
              SELECT_PREVIOUS_CHARACTER,
              new DotHoldSwipe(
                  new Swipe(Direction.UP, /* touchCount= */ 2), new BrailleCharacter(6)))
          .put(
              SELECT_NEXT_CHARACTER,
              new DotHoldSwipe(
                  new Swipe(Direction.DOWN, /* touchCount= */ 2), new BrailleCharacter(6)))
          .put(
              BEGINNING_OF_PAGE,
              new DotHoldSwipe(
                  new Swipe(Direction.UP, /* touchCount= */ 1), new BrailleCharacter(4, 5)))
          .put(
              END_OF_PAGE,
              new DotHoldSwipe(
                  new Swipe(Direction.DOWN, /* touchCount= */ 1), new BrailleCharacter(4, 5)))
          .put(
              TOGGLE_ORIENTATION_LOCK,
              new DotHoldSwipe(
                  new Swipe(Direction.UP, /* touchCount= */ 1), new BrailleCharacter(4, 5, 6)))
          .put(
              VOICE_INPUT,
              new DotHoldSwipe(
                  new Swipe(Direction.DOWN, /* touchCount= */ 1), new BrailleCharacter(4, 5, 6)))
          .put(
              TOGGLE_EMOJI_MODE,
              new DotHoldSwipe(
                  new Swipe(Direction.RIGHT, /* touchCount= */ 1), new BrailleCharacter(4, 5, 6)))
          .buildOrThrow();

  private static final ImmutableMap<BrailleImeAction, ImmutableList<Gesture>>
      DEFAULT_ACTION_GESTURES = createDefaultActionGestureMap();

  /**
   * Returns the default gesture of the given action. If no match exists, return {@link
   * UnassignedGesture}.
   */
  public static List<Gesture> getDefaultGesture(BrailleImeAction action) {
    for (Entry<BrailleImeAction, ImmutableList<Gesture>> entry :
        DEFAULT_ACTION_GESTURES.entrySet()) {
      if (entry.getKey().equals(action)) {
        return new ArrayList<>(entry.getValue());
      }
    }
    return Arrays.asList(new UnassignedGesture());
  }

  /** Returns the list of default actions of the given gesture. */
  public static List<BrailleImeAction> getDefaultAction(Gesture gesture) {
    List<BrailleImeAction> brailleImeActionList = new ArrayList<>();
    for (Entry<BrailleImeAction, ImmutableList<Gesture>> entry :
        DEFAULT_ACTION_GESTURES.entrySet()) {
      if (entry.getValue().contains(gesture)) {
        brailleImeActionList.add(entry.getKey());
      }
    }
    return brailleImeActionList;
  }

  /** Gets the list of {@link BrailleImeAction} with the same root. */
  public static List<BrailleImeAction> getActionWithSameRoot(BrailleImeAction brailleImeAction) {
    List<BrailleImeAction> list = new ArrayList<>();
    if (brailleImeAction.getRootAction() != brailleImeAction) {
      list.add(brailleImeAction.getRootAction());
    }
    for (BrailleImeAction action : BrailleImeAction.values()) {
      if (action != brailleImeAction
          && action != brailleImeAction.getRootAction()
          && action.getRootAction() == brailleImeAction.getRootAction()) {
        list.add(action);
      }
    }
    return list;
  }

  /** Whether two assignments can conflict in the same editing context. */
  public static boolean isSameGestureContext(BrailleImeAction first, BrailleImeAction second) {
    boolean firstCorrection = first != first.getRootAction();
    boolean secondCorrection = second != second.getRootAction();
    if (firstCorrection == secondCorrection) {
      return true;
    }
    // Keyboard/clipboard commands are available in both contexts. Typing and correction
    // counterparts may share a default gesture without sharing their customization.
    return !hasCorrectionCounterpart(firstCorrection ? second : first);
  }

  public static boolean hasCorrectionCounterpart(BrailleImeAction action) {
    return Arrays.stream(BrailleImeAction.values())
        .anyMatch(candidate -> candidate != action && candidate.getRootAction() == action);
  }

  /**
   * Returns the gesture of the given action. If no match exists, return {@link UnassignedGesture}.
   */
  public static List<Gesture> getGesture(Context context, BrailleImeAction action) {
    ImmutableMap<BrailleImeAction, ImmutableList<Gesture>> map = getActionGestureMap(context);
    if (map.containsKey(action)) {
      return new ArrayList<>(map.get(action));
    }
    return Arrays.asList(new UnassignedGesture());
  }

  private static ImmutableList<Gesture> getGesture(
      Map<BrailleImeAction, List<String>> actioGesturenMap, BrailleImeAction action) {
    ImmutableList.Builder<Gesture> gesturesBuilder = ImmutableList.builder();
    for (String gestureId : actioGesturenMap.get(action)) {
      Gesture gesture = getGestureById(gestureId);
      if (gesture != null) {
        gesturesBuilder.add(gesture);
      }
    }
    return gesturesBuilder.build();
  }

  /** Returns the gesture of the given action or null if no match exists. */
  public static List<BrailleImeAction> getAction(Context context, Gesture gesture) {
    ImmutableMap<BrailleImeAction, ImmutableList<Gesture>> map = getActionGestureMap(context);
    List<BrailleImeAction> brailleImeActionList = new ArrayList<>();
    for (Entry<BrailleImeAction, ImmutableList<Gesture>> entry : map.entrySet()) {
      if (entry.getValue().contains(gesture)) {
        brailleImeActionList.add(entry.getKey());
      }
    }
    return brailleImeActionList;
  }

  /** Returns true if the gesture is valid. */
  public static boolean isValid(Gesture gesture) {
    if (gesture instanceof Swipe swipe
        && swipe.getTouchCount() >= 1 && swipe.getTouchCount() <= MAX_SWIPE_TOUCH_POINT) {
      if (swipe.hasSwipedDot()) {
        return swipe.getTouchCount() == 1
            && swipe.getSwipedDot() >= 1 && swipe.getSwipedDot() <= 8;
      }
      return true;
    }
    return gesture instanceof DotHoldSwipe dotHoldSwipe
        && isValid(dotHoldSwipe.getSwipe())
        && dotHoldSwipe.getHeldDots().getOnCount() >= 1
        && dotHoldSwipe.getHeldDots().getOnCount() <= MAX_SWIPE_TOUCH_POINT;
  }

  /** Returns the action gesture map. */
  private static ImmutableMap<BrailleImeAction, ImmutableList<Gesture>> getActionGestureMap(
      Context context) {
    if (!FeatureFlagReader.useGestureCustomization(context)) {
      return DEFAULT_ACTION_GESTURES;
    }
    Map<BrailleImeAction, List<String>> customizedActioGesturenMap =
        normalizeCustomGestures(BrailleUserPreferences.readGestureActionMap(context));
    Map<BrailleImeAction, ImmutableList<Gesture>> currentlyUsingActionGestureMap = new LinkedHashMap<>();
    for (BrailleImeAction defaultAction : DEFAULT_ACTION_GESTURES.keySet()) {
      ImmutableList<Gesture> list;
      if (customizedActioGesturenMap.containsKey(defaultAction)) {
        list = getGesture(customizedActioGesturenMap, defaultAction);
      } else {
        list =
            withoutGesturesAssignedElsewhere(
                DEFAULT_ACTION_GESTURES.get(defaultAction),
                defaultAction,
                customizedActioGesturenMap);
      }
      currentlyUsingActionGestureMap.put(defaultAction, list);
    }
    return ImmutableMap.copyOf(currentlyUsingActionGestureMap);
  }

  private static Map<BrailleImeAction, List<String>> normalizeCustomGestures(
      Map<BrailleImeAction, List<String>> saved) {
    Map<BrailleImeAction, List<String>> normalized = new LinkedHashMap<>();
    Map<String, List<BrailleImeAction>> owners = new HashMap<>();
    for (Gesture gesture : DEFAULT_ACTION_GESTURES.get(HIDE_KEYBOARD)) {
      owners.put(gesture.getId(), new ArrayList<>(Arrays.asList(HIDE_KEYBOARD)));
    }
    // Preserve each explicit action independently; never inherit a typing customization
    // into spelling correction. Only resolve duplicates that can run in the same context.
    for (BrailleImeAction action : BrailleImeAction.values()) {
      if (action == HIDE_KEYBOARD) {
        continue;
      }
      List<String> ids = saved.get(action);
      if (ids == null || ids.isEmpty()) {
        continue;
      }
      List<String> accepted = new ArrayList<>();
      Gesture primaryGesture = null;
      boolean hasValidGesture = false;
      for (String id : ids) {
        Gesture gesture = getGestureById(id);
        if (gesture == null) {
          continue;
        }
        if (!(gesture instanceof UnassignedGesture)) {
          if (primaryGesture == null) {
            primaryGesture = gesture;
          } else if (!primaryGesture.equals(gesture) && primaryGesture.mirrorDots().equals(gesture)) {
            // The removed dialog saved [performed gesture, generated mirror]. Keep only the
            // performed gesture from those legacy pairs without rewriting the stored preferences.
            continue;
          }
        }
        id = gesture.getId();
        hasValidGesture = true;
        if (gesture instanceof UnassignedGesture) {
          accepted.add(id);
        } else if (!owners.containsKey(id)
            || owners.get(id).stream().noneMatch(owner -> isSameGestureContext(owner, action))) {
          owners.computeIfAbsent(id, unused -> new ArrayList<>()).add(action);
          if (!accepted.contains(id)) {
            accepted.add(id);
          }
        }
      }
      if (hasValidGesture) {
        // A conflicting imported mapping must not fall back to another user's assignment.
        normalized.put(action, accepted.isEmpty()
            ? Arrays.asList(new UnassignedGesture().getId()) : accepted);
      }
    }
    return normalized;
  }

  /**
   * Leaves out the default gestures that the user has assigned to another action. Assigning a
   * gesture takes it from any action that had it, but a default added later, as by an update, could
   * otherwise clash with a gesture the user already assigned, and then which action runs would be
   * left to chance.
   */
  private static ImmutableList<Gesture> withoutGesturesAssignedElsewhere(
      ImmutableList<Gesture> defaultGestures,
      BrailleImeAction action,
      Map<BrailleImeAction, List<String>> customizedActionGestureMap) {
    ImmutableList.Builder<Gesture> gesturesBuilder = ImmutableList.builder();
    for (Gesture gesture : defaultGestures) {
      if (!isAssignedElsewhere(gesture, action, customizedActionGestureMap)) {
        gesturesBuilder.add(gesture);
      }
    }
    ImmutableList<Gesture> gestures = gesturesBuilder.build();
    return gestures.isEmpty() ? ImmutableList.of(new UnassignedGesture()) : gestures;
  }

  private static boolean isAssignedElsewhere(
      Gesture gesture,
      BrailleImeAction action,
      Map<BrailleImeAction, List<String>> customizedActionGestureMap) {
    if (gesture instanceof UnassignedGesture) {
      return false;
    }
    for (Entry<BrailleImeAction, List<String>> entry : customizedActionGestureMap.entrySet()) {
      if (entry.getKey() != action && isSameGestureContext(entry.getKey(), action)
          && entry.getValue().contains(gesture.getId())) {
        return true;
      }
    }
    return false;
  }

  @Nullable
  private static Gesture getGestureById(String id) {
    if (id == null) {
      return null;
    }
    Gesture unassigned = new UnassignedGesture();
    if (unassigned.getId().equals(id)) {
      return unassigned;
    }
    try {
      Gesture gesture = id.startsWith("hold_dot") ? new DotHoldSwipe(id) : new Swipe(id);
      return isValid(gesture) ? gesture : null;
    } catch (IllegalArgumentException exception) {
      BrailleImeLog.w(TAG, "Ignoring invalid saved gesture");
      return null;
    }
  }

  private static ImmutableMap<BrailleImeAction, ImmutableList<Gesture>>
      createDefaultActionGestureMap() {
    ImmutableMap.Builder<BrailleImeAction, ImmutableList<Gesture>> builder = ImmutableMap.builder();
    for (BrailleImeAction action : BrailleImeAction.values()) {
      ImmutableList<Gesture> gestures;
      if (DEFAULT_ACTION_GESTURE.containsKey(action)) {
        gestures = getDefaultGestures(action);
      } else if (!action.equals(action.getRootAction())) {
        gestures = getDefaultGestures(action.getRootAction());
      } else {
        gestures = ImmutableList.of(new UnassignedGesture());
      }
      builder.put(action, gestures);
    }
    return builder.buildOrThrow();
  }

  private static ImmutableList<Gesture> getDefaultGestures(BrailleImeAction action) {
    return ImmutableList.of(DEFAULT_ACTION_GESTURE.get(action));
  }

  private BrailleImeGestureAction() {}
}
