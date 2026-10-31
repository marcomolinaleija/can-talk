/*
 * Copyright 2026 Backtalk contributors
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

package com.google.android.accessibility.utils.widget

import android.content.Context
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.inputmethod.EditorInfo
import androidx.appcompat.R
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30, 34])
class KeyboardSafeEditTextTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  private fun useKeyboard(packageName: String) {
    Settings.Secure.putString(
      context.contentResolver,
      Settings.Secure.DEFAULT_INPUT_METHOD,
      "$packageName/.Keyboard",
    )
  }

  private fun connectionIsWrapped(): Boolean {
    context.setTheme(R.style.Theme_AppCompat)
    val field = KeyboardSafeEditText(context)
    val connection = field.onCreateInputConnection(EditorInfo())!!
    return Proxy.isProxyClass(connection.javaClass)
  }

  @Test
  fun anotherAppsKeyboardGoesThroughKeyboardCalls() {
    useKeyboard("com.example.keyboard")
    assertFalse(KeyboardSafeEditText.isOwnKeyboard(context))
    assertTrue(connectionIsWrapped())
  }

  @Test
  fun theAppsOwnKeyboardGetsTheFieldsConnection() {
    useKeyboard(context.packageName)
    assertTrue(KeyboardSafeEditText.isOwnKeyboard(context))
    assertFalse(connectionIsWrapped())
  }

  @Test
  fun samsungStylePasswordInputQueuesTypingAndDeletingWithoutBlocking() {
    useKeyboard("com.samsung.android.honeyboard")
    context.setTheme(R.style.Theme_AppCompat)
    val field = KeyboardSafeEditText(context).apply {
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    val editorInfo = EditorInfo()
    val connection = field.onCreateInputConnection(editorInfo)!!
    val keyboard = Executors.newSingleThreadExecutor()
    try {
      keyboard.submit {
        connection.commitText("ab", 1)
        connection.deleteSurroundingText(1, 0)
      }.get(1, TimeUnit.SECONDS)
      assertEquals("", field.text.toString())
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals("a", field.text.toString())
      assertTrue(field.transformationMethod is PasswordTransformationMethod)
      assertTrue(editorInfo.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
    } finally {
      keyboard.shutdownNow()
      shadowOf(Looper.getMainLooper()).idle()
    }
  }
}
