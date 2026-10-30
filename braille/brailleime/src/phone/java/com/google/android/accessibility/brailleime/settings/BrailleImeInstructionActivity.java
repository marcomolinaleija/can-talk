/*
 * Copyright (C) 2026 Can Talk contributors
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

package com.google.android.accessibility.brailleime.settings;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.accessibility.braille.common.BrailleUserPreferences;
import com.google.android.accessibility.brailleime.BrailleIme;
import com.google.android.accessibility.brailleime.R;

/**
 * Accessible instruction and reference guide for the Braille keyboard.
 * Provides clear explanations of typing modes, sequential recalibration,
 * orientation lock, emoji mode, voice input, navigation, clipboard gestures,
 * as well as an interactive practice text area and direct action buttons.
 */
public class BrailleImeInstructionActivity extends AppCompatActivity {
  public static final String EXTRA_FOCUS_PRACTICE = "focus_practice";

  private EditText practiceEditText;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.brailleime_instruction_activity);

    ActionBar actionBar = getSupportActionBar();
    if (actionBar != null) {
      actionBar.setDisplayHomeAsUpEnabled(true);
      actionBar.setTitle(R.string.instruction_activity_title);
    }

    practiceEditText = findViewById(R.id.et_practice);

    Button recalibrateButton = findViewById(R.id.btn_recalibrate);
    recalibrateButton.setOnClickListener(
        v -> {
          BraillePointCalibrationActivity.initialize(BrailleIme.getTalkBackForBrailleIme());
          Intent intent = new Intent(this, BraillePointCalibrationActivity.class);
          startActivity(intent);
        });

    Button tutorialButton = findViewById(R.id.btn_launch_tutorial);
    tutorialButton.setOnClickListener(
        v -> {
          BrailleUserPreferences.writeLaunchTutorialNextTime(this);
          focusPracticeAndShowKeyboard();
        });

    Button clearButton = findViewById(R.id.btn_clear_practice);
    clearButton.setOnClickListener(
        v -> {
          if (practiceEditText != null) {
            practiceEditText.setText("");
          }
        });

    if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_FOCUS_PRACTICE, false)) {
      if (practiceEditText != null) {
        practiceEditText.postDelayed(this::focusPracticeAndShowKeyboard, 300);
      }
    }
  }

  private void focusPracticeAndShowKeyboard() {
    if (practiceEditText != null) {
      practiceEditText.requestFocus();
      InputMethodManager imm =
          (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
      if (imm != null) {
        imm.showSoftInput(practiceEditText, InputMethodManager.SHOW_IMPLICIT);
      }
    }
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }
}
