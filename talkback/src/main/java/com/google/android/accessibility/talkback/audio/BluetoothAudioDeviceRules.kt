/*
 * Copyright 2026 can-talk contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.google.android.accessibility.talkback.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.google.android.accessibility.material.preference.AccessibilitySuiteListPreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.audio.AudioDeviceRouter.AudioTarget
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Only the user's explicit classification determines a device's role. */
object BluetoothAudioDeviceRules {
  const val PREF_KEY = "pref_bluetooth_audio_device_rules"
  const val CATEGORY_KEY = "pref_bluetooth_audio_devices"

  enum class Role(val value: String) {
    UNCHANGED("unchanged"), HEADPHONES("headphones"), SPEAKER("speaker");

    companion object {
      fun parse(value: String?): Role = entries.find { it.value == value } ?: UNCHANGED
    }
  }

  data class Device(val identity: String, val name: String)
  data class Rule(val device: Device, val role: Role)

  // Group A2DP/SCO/LE profiles by physical address, never by Android's temporary device ID.
  // On systems that do not expose an address, the device name is the available fallback.
  fun identity(address: String?, name: String?): String? {
    val normalizedAddress = address?.trim()?.uppercase(Locale.ROOT)
    if (!normalizedAddress.isNullOrEmpty() &&
        normalizedAddress != "02:00:00:00:00:00" && normalizedAddress != "00:00:00:00:00:00") {
      return "address:$normalizedAddress"
    }
    val normalizedName = name?.trim()
    if (normalizedName.isNullOrEmpty() ||
        normalizedName.equals("unknown", true) || normalizedName.equals("null", true)) return null
    return "name:$normalizedName"
  }

  fun isBluetooth(device: AudioDeviceInfo): Boolean = device.isSink && when (device.type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_HEARING_AID -> true
    else -> false
  }

  fun describe(device: AudioDeviceInfo): Device? {
    val name = device.productName?.toString()?.trim().orEmpty()
    val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address else null
    val identity = identity(address, name) ?: return null
    return Device(identity, name.ifEmpty { address.orEmpty() })
  }

  fun decode(value: String?): List<Rule> {
    if (value.isNullOrEmpty()) return emptyList()
    return try {
      val array = JSONArray(value)
      (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("identity")
        val name = item.optString("name")
        if (id.isEmpty() || name.isEmpty()) null
        else Rule(Device(id, name), Role.parse(item.optString("role")))
      }.distinctBy { it.device.identity }
    } catch (e: JSONException) {
      emptyList()
    }
  }

  fun encode(rules: List<Rule>): String = JSONArray().apply {
    rules.forEach { rule ->
      put(JSONObject().put("identity", rule.device.identity)
        .put("name", rule.device.name).put("role", rule.role.value))
    }
  }.toString()

  /** Ambiguous simultaneous routes retain the existing output preference. */
  fun resolveTarget(baseline: AudioTarget, activeIdentities: List<String>, rules: List<Rule>): AudioTarget {
    val active = activeIdentities.distinct().singleOrNull() ?: return baseline
    return when (rules.find { it.device.identity == active }?.role) {
      Role.SPEAKER -> AudioTarget.PhoneSpeaker
      Role.HEADPHONES -> AudioTarget.SystemDefault
      else -> baseline
    }
  }

  @JvmStatic
  fun updatePreferences(category: PreferenceCategory, context: Context) {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val saved = decode(prefs.getString(PREF_KEY, null))
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val connected = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
      .filter { isBluetooth(it) }.mapNotNull { describe(it) }.distinctBy { it.identity }
    val devices = (connected + saved.map { it.device }).distinctBy { it.identity }
      .sortedBy { it.name.lowercase(Locale.ROOT) }
    category.removeAll()
    category.addPreference(Preference(context).apply {
      setTitle(R.string.summary_bluetooth_audio_devices)
      isSelectable = false
    })
    if (devices.isEmpty()) {
      category.addPreference(Preference(context).apply {
        setTitle(R.string.bluetooth_audio_devices_empty)
        isSelectable = false
      })
    }
    devices.forEach { device ->
      category.addPreference(AccessibilitySuiteListPreference(context).apply {
        key = "bluetooth_audio_role:${device.identity}"
        isPersistent = false // Use the same device-protected settings file as the reader.
        title = if (connected.any { it.identity == device.identity }) device.name
          else context.getString(R.string.bluetooth_audio_device_disconnected, device.name)
        dialogTitle = title
        entries = arrayOf(context.getString(R.string.bluetooth_audio_role_unchanged),
          context.getString(R.string.bluetooth_audio_role_headphones),
          context.getString(R.string.bluetooth_audio_role_speaker))
        entryValues = Role.entries.map { it.value }.toTypedArray()
        value = (saved.find { it.device.identity == device.identity }?.role ?: Role.UNCHANGED).value
        summary = entry
        setOnPreferenceChangeListener { _, newValue ->
          val role = Role.parse(newValue as? String)
          val currentRules = decode(prefs.getString(PREF_KEY, null))
          prefs.edit().putString(PREF_KEY, encode(currentRules.filter {
            it.device.identity != device.identity
          } + Rule(device, role))).apply()
          value = role.value
          summary = entry
          true
        }
      })
    }
  }
}
