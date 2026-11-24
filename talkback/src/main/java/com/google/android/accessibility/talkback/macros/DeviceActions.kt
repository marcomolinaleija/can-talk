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

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.annotation.StringRes
import com.google.android.accessibility.talkback.R
import kotlin.math.roundToInt

/**
 * What macros do to the device outside the screen: native Android controls such as flashlight, sound
 * mode, and stream volumes; media keys; and timers, alarms, calls, messages, and the camera through
 * the apps that handle them.
 */
object DeviceActions {

  enum class ControlKind {
    SWITCH,
    SLIDER,
    CHOICE,
  }

  data class DeviceControl(
    @StringRes val title: Int,
    val kind: ControlKind,
    val audioStream: Int = -1,
  )

  /** The controls, by their names in steps. */
  val CONTROLS: Map<String, DeviceControl> =
    linkedMapOf(
      "flashlight" to DeviceControl(R.string.macro_control_flashlight, ControlKind.SWITCH),
      "soundMode" to DeviceControl(R.string.macro_control_sound_mode, ControlKind.CHOICE),
      "mediaVolume" to
        DeviceControl(R.string.macro_control_media_volume, ControlKind.SLIDER, AudioManager.STREAM_MUSIC),
      "callVolume" to
        DeviceControl(
          R.string.macro_control_call_volume,
          ControlKind.SLIDER,
          AudioManager.STREAM_VOICE_CALL,
        ),
      "ringVolume" to
        DeviceControl(R.string.macro_control_ring_volume, ControlKind.SLIDER, AudioManager.STREAM_RING),
      "notificationVolume" to
        DeviceControl(
          R.string.macro_control_notification_volume,
          ControlKind.SLIDER,
          AudioManager.STREAM_NOTIFICATION,
        ),
      "alarmVolume" to
        DeviceControl(R.string.macro_control_alarm_volume, ControlKind.SLIDER, AudioManager.STREAM_ALARM),
      "accessibilityVolume" to
        DeviceControl(
          R.string.macro_control_accessibility_volume,
          ControlKind.SLIDER,
          AudioManager.STREAM_ACCESSIBILITY,
        ),
      "systemVolume" to
        DeviceControl(R.string.macro_control_system_volume, ControlKind.SLIDER, AudioManager.STREAM_SYSTEM),
    )

  /** The media keys, by their names in steps. */
  val MEDIA_KEYS: Map<String, Int> =
    linkedMapOf(
      "playPause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
      "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
      "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
      "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
      "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
      "stop" to KeyEvent.KEYCODE_MEDIA_STOP,
      "rewind" to KeyEvent.KEYCODE_MEDIA_REWIND,
      "fastForward" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
    )

  /** The names of the controls this device has. */
  fun availableControls(context: Context): List<String> =
    CONTROLS.filter { (name, _) -> isAvailable(context, name) }.keys.toList()

  /** The kind of the control [name]: switch, slider or choice, or null for no such control. */
  fun kindOf(name: String): ControlKind? = CONTROLS[name]?.kind

  private fun isAvailable(context: Context, name: String): Boolean =
    when (name) {
      "flashlight" ->
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) ||
          flashCameraId(context) != null
      else -> name in CONTROLS
    }

  /**
   * The state of the control [name]: a switch's true or false, a slider's value, or a choice's
   * value and label. Null when there is no such control here, or it can't be read.
   */
  fun state(context: Context, name: String): State? {
    val control = CONTROLS[name] ?: return null
    if (!isAvailable(context, name)) return null
    return when {
      name == "flashlight" -> Flashlight.isOn(context)?.let { State.Switch(it) }
      name == "soundMode" -> {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        val options = soundModeOptions(context)
        val current = audio.ringerMode
        val label = options.firstOrNull { it.first == current }?.second ?: current.toString()
        State.Choice(current, label, options, null)
      }
      control.kind == ControlKind.SLIDER -> {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        State.Slider(
          audio.getStreamVolume(control.audioStream),
          streamMinVolume(audio, control.audioStream),
          audio.getStreamMaxVolume(control.audioStream),
        )
      }
      else -> null
    }
  }

  /** The state of a control. */
  sealed class State {
    abstract val text: String

    data class Switch(val on: Boolean) : State() {
      override val text: String
        get() = if (on) "on" else "off"
    }

    data class Slider(val value: Int, val min: Int, val max: Int) : State() {
      override val text: String
        get() = value.toString()
    }

    data class Choice(
      val value: Int,
      val label: String,
      val options: List<Pair<Int, String>>,
      val range: IntRange? = null,
    ) : State() {
      override val text: String
        get() = label
    }
  }

  /** Sets the control [name] to [value]. */
  fun set(context: Context, name: String, value: String, done: (Boolean, String?) -> Unit) {
    val control = CONTROLS[name] ?: return done(false, "Unknown control $name")
    if (!isAvailable(context, name)) return done(false, "$name isn't on this device")
    val text = value.trim()
    when {
      name == "flashlight" -> {
        val wanted =
          when (text.lowercase()) {
            "on", "true", "1" -> true
            "off", "false", "0" -> false
            "toggle" -> !(Flashlight.isOn(context) ?: false)
            else -> return done(false, "A switch is on, off or toggle")
          }
        val camId = flashCameraId(context) ?: return done(false, "No flashlight")
        val camera = context.getSystemService(CameraManager::class.java) ?: return done(false, "No camera")
        try {
          camera.setTorchMode(camId, wanted)
          Flashlight.saw(wanted)
          done(true, null)
        } catch (e: Exception) {
          done(false, e.message ?: "$name wasn't changed")
        }
      }
      name == "soundMode" -> {
        val audio = context.getSystemService(AudioManager::class.java) ?: return done(false, "No audio")
        val options = soundModeOptions(context)
        val current = audio.ringerMode
        val wanted =
          when {
            text.equals("next", ignoreCase = true) ->
              options[(options.indexOfFirst { it.first == current }.coerceAtLeast(0) + 1) % options.size].first
            text.equals("normal", ignoreCase = true) -> AudioManager.RINGER_MODE_NORMAL
            text.equals("vibrate", ignoreCase = true) -> AudioManager.RINGER_MODE_VIBRATE
            text.equals("silent", ignoreCase = true) -> AudioManager.RINGER_MODE_SILENT
            else ->
              (options.firstOrNull { it.first.toString() == text }
                ?: options.firstOrNull { it.second.equals(text, ignoreCase = true) }
                ?: options.firstOrNull { it.second.contains(text, ignoreCase = true) })
                ?.first
          } ?: return done(false, "Choose Normal, Vibrate, Silent, or next")
        try {
          audio.ringerMode = wanted
          done(true, null)
        } catch (e: SecurityException) {
          done(false, "$name needs Do Not Disturb access")
        }
      }
      control.kind == ControlKind.SLIDER -> {
        val audio = context.getSystemService(AudioManager::class.java) ?: return done(false, "No audio")
        val min = streamMinVolume(audio, control.audioStream)
        val max = audio.getStreamMaxVolume(control.audioStream)
        val cur = audio.getStreamVolume(control.audioStream)
        val target =
          when {
            text.endsWith("%") ->
              text.dropLast(1).trim().toDoubleOrNull()?.let {
                min + ((max - min) * it / 100.0).roundToInt()
              }
            text.startsWith("+") || (text.startsWith("-") && text.length > 1) ->
              text.toIntOrNull()?.let { cur + it }
            else -> text.toIntOrNull()
          } ?: return done(false, "A slider takes a number, a percent such as 50%, or +N or -N")
        try {
          audio.setStreamVolume(control.audioStream, target.coerceIn(min, max), 0)
          done(true, null)
        } catch (e: SecurityException) {
          done(false, "$name needs Do Not Disturb access")
        }
      }
      else -> done(false, "$name can't be set")
    }
  }

  private fun flashCameraId(context: Context): String? {
    val camera = context.getSystemService(CameraManager::class.java) ?: return null
    return try {
      camera.cameraIdList.firstOrNull { id ->
        camera.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun soundModeOptions(context: Context): List<Pair<Int, String>> =
    listOf(
      AudioManager.RINGER_MODE_NORMAL to context.getString(R.string.macro_sound_mode_normal),
      AudioManager.RINGER_MODE_VIBRATE to context.getString(R.string.macro_sound_mode_vibrate),
      AudioManager.RINGER_MODE_SILENT to context.getString(R.string.macro_sound_mode_silent),
    )

  private fun streamMinVolume(audio: AudioManager, stream: Int): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      audio.getStreamMinVolume(stream)
    } else {
      0
    }

  /** Presses the media key [name] for the app that plays media. Returns false for no such key. */
  fun media(context: Context, name: String): Boolean {
    val code = MEDIA_KEYS[name] ?: return false
    val audio = context.getSystemService(AudioManager::class.java) ?: return false
    val time = SystemClock.uptimeMillis()
    audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
    audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0))
    return true
  }

  /** The battery level in percent, or null when it can't be read. */
  fun batteryLevel(context: Context): Int? =
    context.getSystemService(BatteryManager::class.java)
      ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
      ?.takeIf { it in 0..100 }

  /** Starts a timer of [seconds] in the clock app, without opening it where the app allows. */
  fun setTimer(context: Context, seconds: Int, label: String?): Boolean =
    start(
      context,
      Intent(AlarmClock.ACTION_SET_TIMER)
        .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86_400))
        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        .apply { if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label) },
    )

  /** Sets an alarm at [hour]:[minute] in the clock app, without opening it where the app allows. */
  fun setAlarm(context: Context, hour: Int, minute: Int, label: String?): Boolean =
    start(
      context,
      Intent(AlarmClock.ACTION_SET_ALARM)
        .putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
        .putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        .apply { if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label) },
    )

  /** Calls [number], or opens the dialler with it when CAN Talk may not call. */
  fun call(context: Context, number: String): Boolean {
    val canCall =
      context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    return start(
      context,
      Intent(
        if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL,
        Uri.fromParts("tel", number.trim(), null),
      ),
    )
  }

  /** Opens the messaging app with a message of [text] to [number], to send. */
  fun message(context: Context, number: String, text: String?): Boolean =
    start(
      context,
      Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number.trim(), null)).apply {
        if (!text.isNullOrEmpty()) putExtra("sms_body", text)
      },
    )

  /** Opens the camera app, for a photo or, for [video], a video. */
  fun camera(context: Context, video: Boolean = false): Boolean =
    start(
      context,
      Intent(if (video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
    )

  private fun start(context: Context, intent: Intent): Boolean =
    try {
      context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      true
    } catch (e: ActivityNotFoundException) {
      false
    }
}
