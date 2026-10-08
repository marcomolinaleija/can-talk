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

import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.util.Size;
import com.google.android.accessibility.brailleime.input.DotHoldSwipe;
import com.google.android.accessibility.brailleime.input.Swipe;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Tests for {@link CustomGestureView}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CustomGestureViewTest {

  private Context context;
  private CustomGestureView customGestureView;

  private final CustomGestureView.CustomGestureCallback callback =
      new CustomGestureView.CustomGestureCallback() {
        @Override
        public boolean onInvalidGesture() {
          return false;
        }

        @Override
        public boolean onSwipeProduced(Swipe swipe) {
          return false;
        }

        @Override
        public boolean onDotHoldAndDotSwipe(DotHoldSwipe dotHoldSwipe) {
          return false;
        }

        @Override
        public boolean onHoldProduced(int pointersHeldCount) {
          return false;
        }

        @Override
        public void onDetectionChanged(boolean isTabletop) {}
      };

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    customGestureView = new CustomGestureView(context, callback, new Size(1080, 1920));
  }

  @Test
  public void showHint_normalTitle_doesNotThrowUnsupportedColorPalette() {
    SpannableStringBuilder sb = new SpannableStringBuilder("Perform gesture\nSwipe up");
    customGestureView.showHint(sb, "Perform gesture", "Swipe up", /* warning= */ false);
    assertNotNull(customGestureView);
  }

  @Test
  public void showHint_warningTitle_doesNotThrowUnsupportedColorPalette() {
    SpannableStringBuilder sb = new SpannableStringBuilder("Already assigned\nSwipe up");
    customGestureView.showHint(sb, "Already assigned", "Swipe up", /* warning= */ true);
    assertNotNull(customGestureView);
  }
}
