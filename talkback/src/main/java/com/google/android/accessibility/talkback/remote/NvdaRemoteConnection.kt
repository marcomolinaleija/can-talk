/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.remote

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.R
import org.json.JSONObject

/** Process-wide session owned by the reader, independent of any Remote activity or window. */
internal object NvdaRemoteConnection {
  enum class LockBehavior(val label: Int) {
    DISCONNECT(R.string.nvda_remote_lock_disconnect), MUTE(R.string.nvda_remote_lock_mute),
    KEEP(R.string.nvda_remote_lock_keep),
  }
  class Request(val host: String, val port: Int, val password: String, val fingerprint: String?) {
    override fun toString() = "NvdaRemoteConnection.Request"
  }
  class Failure(val reason: NvdaRemoteSession.Failure, val fingerprint: String?, val retryComputer: NvdaRemoteComputer?)
  interface Listener {
    fun onChanged(announce: Boolean)
    fun onClipboard(text: String)
    fun onFailure(failure: Failure)
    fun onProfileResult(message: String)
  }

  private var context: Context? = null
  private val main = Handler(Looper.getMainLooper())
  private val worker = NvdaRemoteComputersStore.WORKER
  private val listeners = linkedSetOf<Listener>() // Main thread only; stopped activities detach.
  private var receiverRegistered = false
  var session: NvdaRemoteSession? = null
    private set
  var request: Request? = null
    private set
  var failure: Failure? = null
    private set
  var profile: NvdaRemoteComputer? = null
    private set
  var profileRevision = 0L
    private set
  var pendingComputer: NvdaRemoteComputer? = null
  var modifier = 0x2D
  var speechMuted = false
  var status = R.string.nvda_remote_disconnected
    private set
  var lastSpeech: CharSequence? = null
    private set
  var screenLocked = false
    private set
  var lockBehavior = LockBehavior.DISCONNECT
    set(value) {
      field = value
      if (screenLocked) applyLockPolicy()
    }
  private val screenReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action == Intent.ACTION_SCREEN_OFF) updateScreenLock(true) else refreshScreenLock()
    }
  }

  fun initialize(context: Context) { this.context = context.applicationContext }
  fun addListener(listener: Listener) { listeners.add(listener) }
  fun removeListener(listener: Listener) { listeners.remove(listener) }

  fun connect(request: Request, profile: NvdaRemoteComputer?) {
    check(session == null)
    val app = checkNotNull(context)
    this.request = request
    this.profile = profile
    failure = null
    lastSpeech = null
    status = R.string.nvda_remote_connecting
    var recorded = false
    var lastReady: Boolean? = null
    lateinit var created: NvdaRemoteSession
    fun onMain(action: () -> Unit) { main.post { if (session === created) action() } }
    created = NvdaRemoteSession(request.host, request.port, request.password, request.fingerprint,
      object : NvdaRemoteSession.Listener {
        override fun onState(ready: Boolean) = onMain {
          status = when {
            ready -> R.string.nvda_remote_ready
            request.password != request.password.trim() -> R.string.nvda_remote_waiting_spaces
            else -> R.string.nvda_remote_waiting
          }
          if (ready && !recorded) {
            recorded = true
            val candidate = pendingComputer
            pendingComputer = null
            if (candidate != null) saveComputer(candidate)
            else profile?.id?.let { id ->
              worker.execute { runCatching { NvdaRemoteComputersStore(app).markUsed(id) } }
            }
          }
          val announce = lastReady != ready
          lastReady = ready
          changed(announce)
        }
        override fun onMessage(message: JSONObject) = onMain {
          refreshScreenLock()
          if (session === created) receive(message)
        }
        override fun onFailure(reason: NvdaRemoteSession.Failure, fingerprint: String?) = onMain {
          val result = Failure(reason, fingerprint, pendingComputer)
          closeTransport()
          failure = result
          status = when (reason) {
            NvdaRemoteSession.Failure.CERTIFICATE -> R.string.nvda_remote_certificate_error
            NvdaRemoteSession.Failure.CONNECTION -> R.string.nvda_remote_connection_error
            NvdaRemoteSession.Failure.DISCONNECTED -> R.string.nvda_remote_connection_lost
            NvdaRemoteSession.Failure.PROTOCOL -> R.string.nvda_remote_protocol_error
            NvdaRemoteSession.Failure.MULTIPLE_COMPUTERS -> R.string.nvda_remote_multiple_computers
          }
          changed(true)
          listeners.toList().forEach { it.onFailure(result) }
        }
      })
    session = created
    ContextCompat.registerReceiver(app, screenReceiver, IntentFilter().apply {
      addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
    }, ContextCompat.RECEIVER_NOT_EXPORTED)
    receiverRegistered = true
    refreshScreenLock()
    if (session !== created) return
    changed(true)
    created.start()
  }

  private fun receive(message: JSONObject) {
    val muted = speechMuted || (screenLocked && lockBehavior == LockBehavior.MUTE)
    when (message.optString("type")) {
      "speak" -> {
        val sequence = message.optJSONArray("sequence") ?: return
        val text = NvdaRemoteSpeech.text(sequence)
        lastSpeech = text
        if (!muted) NvdaRemoteSpeech.speak(text, message.optInt("priority") == 2)
        changed(false)
      }
      "cancel" -> if (!muted) NvdaRemoteSpeech.cancel()
      "pause_speech" -> if (!muted) NvdaRemoteSpeech.pause(message.optBoolean("switch"))
      "set_clipboard_text" -> if (!screenLocked) {
        val value = message.optString("text").take(8192)
        listeners.toList().forEach { it.onClipboard(value) }
      }
    }
  }

  fun saveComputer(computer: NvdaRemoteComputer) {
    val app = checkNotNull(context)
    val source = session ?: return
    val updated = computer.copy(lastUsed = System.currentTimeMillis())
    worker.execute {
      val result = runCatching { NvdaRemoteComputersStore(app).save(updated) }
      main.post {
        if (session !== source) return@post
        val message = result.fold({
          profile = updated
          profileRevision++
          app.getString(R.string.nvda_computer_saved, updated.name)
        }, { app.getString(R.string.nvda_computer_error) })
        changed(false)
        listeners.toList().forEach { it.onProfileResult(message) }
      }
    }
  }

  fun updateProfile(computer: NvdaRemoteComputer) {
    if (pendingComputer?.id == computer.id) pendingComputer = null
    if (profile?.id == computer.id) { profile = computer; profileRevision++; changed(false) }
  }
  fun deleteProfile(id: String) {
    if (pendingComputer?.id == id) pendingComputer = null
    if (profile?.id == id) { profile = null; profileRevision++; changed(false) }
  }

  fun refreshScreenLock() {
    val app = context ?: return
    updateScreenLock(app.getSystemService(PowerManager::class.java)?.isInteractive == false ||
      app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true)
  }
  private fun updateScreenLock(locked: Boolean) {
    val transitioned = screenLocked != locked
    screenLocked = locked
    if (locked && (transitioned || lockBehavior == LockBehavior.DISCONNECT)) applyLockPolicy()
    if (transitioned) changed(false)
  }
  private fun applyLockPolicy() {
    val current = session ?: return
    current.setHeldModifiers(intArrayOf())
    when (lockBehavior) {
      LockBehavior.DISCONNECT -> disconnect(false)
      LockBehavior.MUTE -> NvdaRemoteSpeech.cancel()
      LockBehavior.KEEP -> Unit
    }
  }

  fun disconnect(announce: Boolean = true) {
    closeTransport()
    request = null
    failure = null
    profile = null
    status = R.string.nvda_remote_disconnected
    changed(announce)
  }
  private fun closeTransport() {
    val old = session
    session = null
    pendingComputer = null
    speechMuted = false
    old?.close()
    if (old != null) NvdaRemoteSpeech.cancel()
    if (receiverRegistered) {
      context?.unregisterReceiver(screenReceiver)
      receiverRegistered = false
    }
  }
  @JvmStatic fun shutdown() { disconnect(false) }
  private fun changed(announce: Boolean) { listeners.toList().forEach { it.onChanged(announce) } }
}
