/*
 * Copyright 2026 can-talk contributors
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

import android.content.res.Resources;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Checks Android's regional fallback to Latin American Spanish, including the phone's locale. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BrailleImeSpanishResourcesTest {
  @Test
  @Config(qualifiers = "es-rMX")
  public void mexicanSpanishUsesLatinAmericanResources() {
    assertLatinAmericanResources();
  }

  @Test
  @Config(qualifiers = "es-rUS")
  public void unitedStatesSpanishUsesLatinAmericanResources() {
    assertLatinAmericanResources();
  }

  private static void assertLatinAmericanResources() {
    Resources resources = RuntimeEnvironment.getApplication().getResources();
    assertEquals("Eco del teclado", resources.getString(R.string.title_pref_braille_keyboard_echo));
    assertEquals(
        "Orientación bloqueada. Puerto de carga hacia abajo",
        resources.getString(
            R.string.orientation_locked_port_announcement,
            resources.getString(R.string.port_down_announcement)));
    assertEquals(
        "Teclado braille de can-talk", resources.getString(R.string.braille_ime_service_name));
  }
}
