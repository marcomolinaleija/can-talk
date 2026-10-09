/*
 * Copyright 2019 Google Inc.
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

package com.google.android.accessibility.brailleime.input;

import android.content.res.Resources;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.google.android.accessibility.braille.interfaces.BrailleCharacter;
import com.google.android.accessibility.brailleime.R;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Describes a user-initiated swipe on the touch screen.
 *
 * <p>A swipe has both a {@link Direction} and a touch count, which is the number of fingers that
 * were touching the screen while the swipe was occurring. It may also optionally specify a
 * {@code swipedDot} if the swipe originated from a particular braille dot.
 */
public class Swipe implements Gesture {
  private static final Pattern REGEX_PATTERN =
      Pattern.compile("swipe_(?:dot(?<dot>\\d+)_)?(?<direction>[A-Za-z]+)_(?<touchpoints>\\d+)");

  /** The direction in which the swipe occurred. */
  public enum Direction {
    UP(R.string.gesture_direction_up),
    DOWN(R.string.gesture_direction_down),
    LEFT(R.string.gesture_direction_left),
    RIGHT(R.string.gesture_direction_right);

    Direction(@StringRes int descriptionRes) {
      this.descriptionRes = descriptionRes;
    }

    @StringRes private final int descriptionRes;

    /** Gets the description of the {@link Direction}. */
    public String getDescription(Resources resources) {
      return resources.getString(descriptionRes);
    }
  }

  private final Direction direction;
  private final int touchCount;
  private final int swipedDot;

  public Swipe(Direction direction, int touchCount) {
    this(direction, touchCount, 0);
  }

  public Swipe(Direction direction, int touchCount, int swipedDot) {
    this.direction = direction;
    this.touchCount = touchCount;
    this.swipedDot = swipedDot;
  }

  public Swipe(Swipe swipe) {
    this.direction = swipe.direction;
    this.touchCount = swipe.touchCount;
    this.swipedDot = swipe.swipedDot;
  }

  public Swipe(String id) {
    Matcher matcher = REGEX_PATTERN.matcher(id);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Invalid id");
    }
    this.direction = Direction.valueOf(matcher.group("direction").toUpperCase(Locale.ENGLISH));
    this.touchCount = Integer.parseInt(matcher.group("touchpoints"));
    String dotStr = matcher.group("dot");
    this.swipedDot = dotStr != null ? Integer.parseInt(dotStr) : 0;
  }

  static Swipe createFromRotation90(Swipe swipe) {
    return new Swipe(rotate90Degrees(swipe.direction), swipe.touchCount, swipe.swipedDot);
  }

  static Swipe createFromUpSideDown(Swipe swipe) {
    return new Swipe(turnDirectionUpsideDown(swipe.direction), swipe.touchCount, swipe.swipedDot);
  }

  static Swipe createFromMirror(Swipe swipe) {
    return new Swipe(turnDirectionMirror(swipe.direction), swipe.touchCount, swipe.swipedDot);
  }

  public Direction getDirection() {
    return direction;
  }

  public int getTouchCount() {
    return touchCount;
  }

  public boolean hasSwipedDot() {
    return swipedDot > 0;
  }

  public int getSwipedDot() {
    return swipedDot;
  }

  public Swipe withoutDot() {
    return new Swipe(direction, touchCount, 0);
  }

  @Override
  public Swipe getSwipe() {
    return new Swipe(this);
  }

  @Override
  public BrailleCharacter getHeldDots() {
    return new BrailleCharacter();
  }

  @Override
  public Gesture mirrorDots() {
    if (hasSwipedDot()) {
      return new Swipe(direction, touchCount, mirrorDot(swipedDot));
    }
    return new Swipe(this);
  }

  @Override
  public String getDescription(Resources resources) {
    if (hasSwipedDot()) {
      if (touchCount > 1) {
        return resources.getString(
            R.string.gesture_swipe_dot_multiple_fingers,
            swipedDot,
            direction.getDescription(resources),
            NumberFormat.getNumberInstance(Locale.getDefault()).format(touchCount));
      }
      return resources.getString(
          R.string.gesture_swipe_dot, swipedDot, direction.getDescription(resources));
    }
    if (touchCount > 1) {
      return resources.getString(
          R.string.gesture_swipe_multiple_fingers,
          direction.getDescription(resources),
          NumberFormat.getNumberInstance(Locale.getDefault()).format(touchCount));
    }
    return resources.getString(
        R.string.gesture_swipe_one_finger, direction.getDescription(resources));
  }

  @Override
  public String getId() {
    return hasSwipedDot()
        ? "swipe_dot" + swipedDot + "_" + direction.toString().toLowerCase(Locale.ENGLISH) + "_" + touchCount
        : "swipe_" + direction.toString().toLowerCase(Locale.ENGLISH) + "_" + touchCount;
  }

  private static int mirrorDot(int dot) {
    return switch (dot) {
      case 1 -> 4;
      case 2 -> 5;
      case 3 -> 6;
      case 4 -> 1;
      case 5 -> 2;
      case 6 -> 3;
      case 7 -> 8;
      case 8 -> 7;
      default -> dot;
    };
  }

  private static Direction rotate90Degrees(Direction oldDirection) {
    if (oldDirection == Direction.UP) {
      return Direction.RIGHT;
    } else if (oldDirection == Direction.DOWN) {
      return Direction.LEFT;
    } else if (oldDirection == Direction.LEFT) {
      return Direction.UP;
    } else if (oldDirection == Direction.RIGHT) {
      return Direction.DOWN;
    }
    throw new IllegalArgumentException("unknown direction " + oldDirection);
  }

  private static Direction turnDirectionUpsideDown(Direction oldDirection) {
    if (oldDirection == Direction.UP) {
      return Direction.DOWN;
    } else if (oldDirection == Direction.DOWN) {
      return Direction.UP;
    }
    return oldDirection;
  }

  private static Direction turnDirectionMirror(Direction oldDirection) {
    if (oldDirection == Direction.LEFT) {
      return Direction.RIGHT;
    } else if (oldDirection == Direction.RIGHT) {
      return Direction.LEFT;
    }
    return oldDirection;
  }

  @Override
  public String toString() {
    return hasSwipedDot()
        ? "Swipe{dot=" + swipedDot + ", direction=" + direction + ", touchCount=" + touchCount + "}"
        : "Swipe{direction=" + direction + ", touchCount=" + touchCount + "}";
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (!(obj instanceof Swipe that)) {
      return false;
    }
    return direction.equals(that.direction)
        && touchCount == that.touchCount
        && swipedDot == that.swipedDot;
  }

  @Override
  public int hashCode() {
    /*
     * Hashing function taken from an example in "Effective Java" page 38/39. The number 13 is
     * arbitrary, but choosing non-zero number to start decreases the number of collisions. 37
     * is used as it's an odd prime. If multiplication overflowed and the 37 was an even number,
     * it would be equivalent to bit shifting. The fact that 37 is prime is standard practice.
     */
    int result = 13;
    result = 37 * result + (direction == null ? 0 : direction.hashCode());
    result = 37 * result + touchCount;
    result = 37 * result + swipedDot;
    return result;
  }
}
