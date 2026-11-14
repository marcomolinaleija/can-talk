/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.actor.voicecommands

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Looper
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.output.SpeechController
import java.time.Duration
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
@Config(sdk = [29], qualifiers = "es-rMX")
class CallVoiceCommandsTest {
  private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
      if (permission == Manifest.permission.CALL_PHONE) PackageManager.PERMISSION_GRANTED
      else super.checkPermission(permission, pid, uid)
  }
  private val feedback = mutableListOf<Feedback>()
  private val commands = CallVoiceCommands(context).apply {
    setPipeline(Pipeline.FeedbackReturner { feedback.add(it); true })
  }

  private fun command(text: String, partial: Boolean = false) =
    commands.handleCommand(VoiceCommandFuzzyMatcher.normalize(text), partial)

  private fun askToCall() {
    assertTrue(command("llama al 5512345678"))
    assertEquals(1, feedback.size)
    assertTrue(feedback.last().failovers().first().speech()!!.text().toString().contains("sí"))
  }

  @Test
  fun contactChoicesUseSpanishPluralsAndKeepTheNumberedOptions() {
    val options = "1, María Pérez. 2, María López."
    val question = context.resources.getQuantityString(
      R.plurals.voice_commands_call_choose, 2, 2, "María", options)
    assertTrue(question, question.startsWith("Encontré 2 contactos"))
    assertTrue(question, question.contains(options))
    val single = context.resources.getQuantityString(
      R.plurals.voice_commands_call_choose, 1, 1, "María", "1, María Pérez.")
    assertTrue(single, single.startsWith("Encontré 1 contacto que coincide"))
  }

  @Test
  fun partialResultsCannotStartOrConfirmACall() {
    assertFalse(command("llama al 5512345678", partial = true))
    assertTrue(feedback.isEmpty())
    askToCall()
    assertTrue(command("sí", partial = true))
    assertEquals(1, feedback.size)
    assertTrue(command("no"))
    assertFalse(command("sí"))
  }

  @Test
  fun uncertainAnswersDoNotConfirmAndClearThePendingCall() {
    askToCall()
    assertFalse(command("sí pero no ahora"))
    assertFalse(command("sí"))
    assertEquals(1, feedback.size)
  }

  @Test
  fun theAnswerTimeoutStartsAfterTheQuestionFinishesSpeaking() {
    askToCall()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
    val completed = feedback.first().failovers().first().speech()!!.options()!!.mCompletedAction!!
    completed.run(SpeechController.STATUS_SPOKEN)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
    assertFalse(command("sí"))
    assertTrue(feedback.any { item -> item.failovers().any { it.voiceRecognition() != null } })
  }

  @Test
  fun cancellingBeforeSpeechEndsDoesNotReopenTheMicrophone() {
    askToCall()
    val completed = feedback.first().failovers().first().speech()!!.options()!!.mCompletedAction!!
    assertTrue(command("no gracias"))
    completed.run(SpeechController.STATUS_SPOKEN)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    assertFalse(feedback.any { item -> item.failovers().any { it.voiceRecognition() != null } })
  }

  @Test
  fun anotherCallCommandCanReplaceAPendingConfirmation() {
    askToCall()
    assertTrue(command("llama al 1234567890"))
    assertEquals(2, feedback.size)
    val question = feedback.last().failovers().first().speech()!!.text().toString()
    assertTrue(question, question.contains("1 2 3 4 5 6 7 8 9 0"))
    assertTrue(command("cancelar"))
    assertFalse(command("sí"))
  }
}
