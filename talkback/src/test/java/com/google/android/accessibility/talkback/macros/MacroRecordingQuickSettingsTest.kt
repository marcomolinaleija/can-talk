/*
 * Copyright 2026 CAN Talk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.macros

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MacroRecordingQuickSettingsTest {

  private val trailing = setOf("OVERVIEW", "NOTIFICATIONS", "ALL_APPS")

  @Test
  fun quickSettingsTilesAndDialog_preservedWhenEndedByCommand() {
    val actions =
      listOf(
        MacroRecording.Command(100L, MacroRecording.SYSTEM_UI, "QUICK_SETTINGS"),
        MacroRecording.Press(
          time = 500L,
          pkg = MacroRecording.SYSTEM_UI,
          label = "^Mobile Hotspot",
          regex = true,
        ),
        MacroRecording.Press(
          time = 1_000L,
          pkg = MacroRecording.SYSTEM_UI,
          label = "^Aceptar$",
          regex = true,
        ),
      )

    val steps =
      MacroRecording.steps(
        actions = actions,
        startTime = 0L,
        startPkg = MacroRecording.SYSTEM_UI,
        startApp = "APP:com.android.settings/.Settings",
        trailing = trailing,
        endedByCommand = true,
      )

    assertEquals(
      listOf(
        Macros.RunCommand("QUICK_SETTINGS"),
        Macros.Tap("^Mobile Hotspot", Macros.DEFAULT_TAP_TIMEOUT_MS, regex = true),
        Macros.Tap("^Aceptar$", Macros.DEFAULT_TAP_TIMEOUT_MS, regex = true),
      ),
      steps,
    )
  }

  @Test
  fun quickSettingsPreserved_whileOnlyTrailingStopNotificationAndShadeStripped() {
    val actions =
      listOf(
        MacroRecording.Command(100L, null, "QUICK_SETTINGS"),
        MacroRecording.Press(
          time = 400L,
          pkg = MacroRecording.SYSTEM_UI,
          label = "^Mobile Hotspot",
          regex = true,
        ),
        MacroRecording.Press(
          time = 900L,
          pkg = MacroRecording.SYSTEM_UI,
          label = "^Aceptar$",
          regex = true,
        ),
        MacroRecording.Command(1_500L, MacroRecording.SYSTEM_UI, "NOTIFICATIONS"),
        MacroRecording.Press(
          time = 2_000L,
          pkg = MacroRecording.SYSTEM_UI,
          label = "^Grabando pasos",
          regex = true,
          isStopNotification = true,
        ),
      )

    val steps =
      MacroRecording.steps(
        actions = actions,
        startTime = 0L,
        trailing = trailing,
        endedByCommand = false,
      )

    assertEquals(
      listOf(
        Macros.RunCommand("QUICK_SETTINGS"),
        Macros.Tap("^Mobile Hotspot", Macros.DEFAULT_TAP_TIMEOUT_MS, regex = true),
        Macros.Tap("^Aceptar$", Macros.DEFAULT_TAP_TIMEOUT_MS, regex = true),
      ),
      steps,
    )
  }

  @Test
  fun trailingLauncherAndOverview_stillStrippedWhenStoppingWithoutCommand() {
    val actions =
      listOf(
        MacroRecording.Press(
          time = 200L,
          pkg = "com.example.app",
          label = "^Enviar$",
          regex = true,
        ),
        MacroRecording.Command(500L, "com.example.app", "OVERVIEW"),
        MacroRecording.Press(
          time = 900L,
          pkg = "com.microsoft.launcher",
          label = "^CAN Talk$",
          regex = true,
          inLauncher = true,
        ),
      )

    val steps =
      MacroRecording.steps(
        actions = actions,
        startTime = 0L,
        trailing = trailing,
        endedByCommand = false,
      )

    assertEquals(
      listOf(Macros.Tap("^Enviar$", Macros.DEFAULT_TAP_TIMEOUT_MS, regex = true)),
      steps,
    )
  }
}
