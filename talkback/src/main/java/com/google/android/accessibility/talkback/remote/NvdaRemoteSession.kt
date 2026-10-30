/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.remote

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.net.ssl.X509ExtendedTrustManager
import org.json.JSONObject

/** Independent implementation of NVDA Remote v2: TLS with newline-delimited UTF-8 JSON.
 * Temporary modifiers are released per command; physically held modifiers span commands.
 * Diagnostics contain only connection stages, peer counts and failure classes.
 * Session keys, speech and clipboard text are never logged or saved.
 */
class NvdaRemoteSession(
  private val host: String,
  private val port: Int,
  private val channel: String,
  private val trustedFingerprint: String? = null,
  private val listener: Listener,
) {
  interface Listener {
    fun onState(ready: Boolean)
    fun onMessage(message: JSONObject)
    fun onFailure(failure: Failure, fingerprint: String?)
  }
  enum class Failure { CONNECTION, CERTIFICATE, DISCONNECTED, PROTOCOL, MULTIPLE_COMPUTERS }

  private val closed = AtomicBoolean(false)
  private val outbound = ArrayBlockingQueue<List<JSONObject>>(64)
  private val inputLock = Any()
  private val heldKeys = linkedSetOf<Int>() // State of commands accepted into the writer queue.
  private val followers = mutableSetOf<Int>() // Owned by the reader thread.
  private var joined = false
  @Volatile private var socket: Socket? = null
  @Volatile private var writer: Thread? = null
  @Volatile var ready = false
    private set
  @Volatile private var fingerprint: String? = null

  fun start() {
    Thread({ connectAndRead() }, "CanTalk-NvdaReader").apply { isDaemon = true; start() }
  }

  private fun connectAndRead() {
    try {
      Log.i("CanTalkNvdaRemote", "Connecting as controller")
      val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      factory.init(null as java.security.KeyStore?)
      val defaultTrust = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
      val extendedTrust = defaultTrust as? X509ExtendedTrustManager
      val recordingTrust = object : X509ExtendedTrustManager() {
        override fun getAcceptedIssuers() = defaultTrust.acceptedIssuers
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
          defaultTrust.checkClientTrusted(chain, authType)
        private fun checkPin(chain: Array<X509Certificate>): Boolean {
          val leaf = chain.firstOrNull() ?: throw CertificateException("Missing certificate")
          fingerprint = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
            .joinToString(":") { "%02X".format(it.toInt() and 255) }
          if (trustedFingerprint != null) {
            if (fingerprint != trustedFingerprint) throw CertificateException("Certificate changed")
          }
          return trustedFingerprint != null
        }
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
          if (!checkPin(chain)) defaultTrust.checkServerTrusted(chain, authType)
        }
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {
          if (!checkPin(chain)) {
            if (extendedTrust != null) extendedTrust.checkServerTrusted(chain, authType, socket)
            else defaultTrust.checkServerTrusted(chain, authType)
          }
        }
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {
          if (!checkPin(chain)) {
            if (extendedTrust != null) extendedTrust.checkServerTrusted(chain, authType, engine)
            else defaultTrust.checkServerTrusted(chain, authType)
          }
        }
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) =
          defaultTrust.checkClientTrusted(chain, authType)
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
          defaultTrust.checkClientTrusted(chain, authType)
      }
      val plain = Socket()
      socket = plain
      if (closed.get()) {
        plain.close()
        return
      }
      plain.tcpNoDelay = true
      plain.keepAlive = true
      plain.connect(InetSocketAddress(host, port), 10000)
      val tls = SSLContext.getInstance("TLS")
      tls.init(null, arrayOf(recordingTrust), null)
      // Layer the socket with the actual hostname for SNI and certificate name checks.
      val connection = tls.socketFactory.createSocket(plain, host, port, true) as SSLSocket
      socket = connection
      if (closed.get()) {
        connection.close()
        return
      }
      connection.soTimeout = 15000
      // Explicitly trusted certificates are pinned for this connection only.
      if (trustedFingerprint == null) {
        connection.sslParameters = connection.sslParameters.apply {
          endpointIdentificationAlgorithm = "HTTPS"
        }
      }
      connection.startHandshake()
      Log.i("CanTalkNvdaRemote", "TLS verified; requesting session")
      // Keep the deadline until the server acknowledges joining the channel.
      if (closed.get()) return
      val output = connection.getOutputStream()
      fun writeBatch(batch: List<JSONObject>) {
        output.write(batch.joinToString("", transform = { it.toString() + "\n" }).toByteArray(Charsets.UTF_8))
        output.flush()
      }
      writeBatch(listOf(
        JSONObject().put("type", "protocol_version").put("version", 2),
        JSONObject().put("type", "join").put("channel", channel).put("connection_type", "master"),
      ))
      writer = Thread({
        val pressedKeys = linkedSetOf<Int>() // Only touched by this writer, after successful writes.
        try {
          while (true) {
            val batch = outbound.take()
            if (batch.isEmpty()) break // Graceful shutdown marker.
            writeBatch(batch)
            for (message in batch) if (message.optString("type") == "key") {
              val key = message.getInt("vk_code")
              if (message.getBoolean("pressed")) pressedKeys.add(key) else pressedKeys.remove(key)
            }
          }
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
        } catch (_: Exception) {
          // The reader reports a single failure when this writer closes the socket.
        } finally {
          // Release what was actually written, including a hold whose queued release was discarded.
          try { if (pressedKeys.isNotEmpty()) writeBatch(pressedKeys.reversed().map { keyEvent(it, false) }) }
          catch (_: Exception) { /* An already broken connection cannot deliver cleanup. */ }
          try { connection.close() } catch (_: Exception) {}
        }
      }, "CanTalk-NvdaWriter").apply { isDaemon = true }
      if (closed.get()) return
      writer!!.start()
      if (closed.get()) outbound.offer(emptyList())
      val input = BufferedInputStream(connection.getInputStream())
      val frame = ByteArrayOutputStream()
      while (!closed.get()) {
        val byte = input.read()
        if (byte == -1) throw EOFException()
        if (byte == 10) {
          if (frame.size() > 0) receive(JSONObject(frame.toString("UTF-8")))
          frame.reset()
        } else {
          if (frame.size() >= 262144) throw IllegalArgumentException("Message too large")
          frame.write(byte)
        }
      }
    } catch (failure: SSLHandshakeException) {
      Log.w("CanTalkNvdaRemote", "TLS failure: ${failure.javaClass.simpleName}")
      if (!closed.get()) listener.onFailure(Failure.CERTIFICATE, fingerprint)
    } catch (_: org.json.JSONException) {
      Log.w("CanTalkNvdaRemote", "Invalid protocol JSON")
      if (!closed.get()) listener.onFailure(Failure.PROTOCOL, null)
    } catch (_: IllegalArgumentException) {
      Log.w("CanTalkNvdaRemote", "Invalid protocol frame")
      if (!closed.get()) listener.onFailure(Failure.PROTOCOL, null)
    } catch (failure: Exception) {
      if (!closed.get()) Log.w("CanTalkNvdaRemote", "Connection failure: ${failure.javaClass.simpleName}; joined=$joined")
      if (!closed.get()) listener.onFailure(if (ready) Failure.DISCONNECTED else Failure.CONNECTION, null)
    } finally { close() }
  }

  private fun receive(message: JSONObject) {
    if (closed.get()) return
    when (message.optString("type")) {
      "channel_joined" -> {
        joined = true
        socket?.soTimeout = 0
        followers.clear()
        val clients = message.optJSONArray("clients")
        if (clients != null) for (index in 0 until clients.length()) {
          val client = clients.optJSONObject(index) ?: continue
          if (client.optString("connection_type") == "slave") followers.add(client.getInt("id"))
        }
        updatePeers()
      }
      "client_joined" -> {
        val client = message.optJSONObject("client") ?: return
        if (client.optString("connection_type") == "slave") followers.add(client.getInt("id"))
        updatePeers()
      }
      "client_left" -> {
        val client = message.opt("client")
        val id = if (client is JSONObject) client.optInt("id", -1) else (client as? Number)?.toInt() ?: message.optInt("user_id", -1)
        followers.remove(id)
        updatePeers()
      }
      "error", "version_mismatch" -> {
        Log.w("CanTalkNvdaRemote", "Server rejected session")
        listener.onFailure(Failure.PROTOCOL, null)
        close()
      }
      "speak", "cancel", "pause_speech", "tone", "set_clipboard_text" -> {
        // Only accept output from the one controlled PC, never from another controller.
        if (ready && message.optInt("origin", -1) in followers) listener.onMessage(message)
      }
      // NVDA's server sends ping as a keepalive; it does not require a reply.
    }
  }

  private fun updatePeers() {
    Log.i("CanTalkNvdaRemote", "Session acknowledged=$joined; controlled computers=${followers.size}")
    if (followers.size > 1) {
      listener.onFailure(Failure.MULTIPLE_COMPUTERS, null)
      close()
      return
    }
    ready = followers.size == 1
    if (!ready) synchronized(inputLock) {
      outbound.clear()
      heldKeys.clear()
    }
    listener.onState(ready)
  }

  fun press(key: Int, vararg modifiers: Int): Boolean = synchronized(inputLock) {
    enqueue(keyMessages(key, modifiers))
  }

  /** Synchronizes physical holds; an empty set releases them while preserving queue order. */
  fun setHeldModifiers(modifiers: IntArray): Boolean = synchronized(inputLock) {
    val requested = modifiers.toSet()
    if (requested.any { it !in setOf(0x10, 0x11, 0x12, 0x14, 0x2D, 0x5B) }) return@synchronized false
    val batch = heldKeys.filter { it !in requested }.reversed().map { keyEvent(it, false) } +
      requested.filter { it !in heldKeys }.map { keyEvent(it, true) }
    if (!enqueue(batch, release = requested.isEmpty())) return@synchronized false
    heldKeys.clear()
    heldKeys.addAll(requested)
    true
  }

  /** Sends raw dots to NVDA's braille input table, without touching either clipboard. */
  fun braille(dots: Int, space: Boolean = false): Boolean {
    if (dots !in 0..255 || (dots == 0 && !space)) return false
    return enqueue(listOf(brailleMessage("braille_dots").put("dots", dots).put("space", space)))
  }

  fun brailleCommand(command: String): Boolean {
    if (command !in setOf("braille_eraseLastCell", "braille_enter", "braille_translate")) return false
    return enqueue(listOf(brailleMessage(command)))
  }

  fun pressAfterBraille(key: Int, vararg modifiers: Int): Boolean = synchronized(inputLock) {
    enqueue(listOf(brailleMessage("braille_translate")) + keyMessages(key, modifiers))
  }

  private fun brailleMessage(command: String) = JSONObject().put("type", "braille_input")
    .put("source", "cantalk").put("id", command).put("dots", 0).put("space", false)
    .put("scriptPath", org.json.JSONArray(listOf("globalCommands", "GlobalCommands", command)))

  /** Sends the text to the Windows clipboard, followed by Ctrl+V in the same ordered batch. */
  fun paste(text: String): Boolean {
    if (text.isEmpty() || text.length > 8192) return false
    return synchronized(inputLock) {
      enqueue(listOf(JSONObject().put("type", "set_clipboard_text").put("text", text)) +
        keyMessages(0x56, intArrayOf(0x11)))
    }
  }

  private fun keyEvent(code: Int, pressed: Boolean) = JSONObject().put("type", "key")
      .put("vk_code", code).put("scan_code", 0)
      .put("extended", code in setOf(0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E))
      .put("pressed", pressed)

  private fun keyMessages(key: Int, modifiers: IntArray): List<JSONObject> {
    val temporary = modifiers.distinct().filter { it !in heldKeys && it != key }
    return temporary.map { keyEvent(it, true) } + keyEvent(key, true) +
      (if (key in heldKeys) emptyList() else listOf(keyEvent(key, false))) +
      temporary.reversed().map { keyEvent(it, false) }
  }

  private fun enqueue(batch: List<JSONObject>, release: Boolean = false): Boolean = synchronized(inputLock) {
    // Reserve one queue slot for releases so a burst of input cannot leave a modifier down.
    ready && !closed.get() && (batch.isEmpty() ||
      ((release || outbound.remainingCapacity() > 1) && outbound.offer(batch)))
  }

  fun close() {
    if (!closed.compareAndSet(false, true)) return
    ready = false
    synchronized(inputLock) {
      heldKeys.clear()
      outbound.clear()
      outbound.offer(emptyList())
    }
    val writing = writer
    val connection = socket
    if (writing?.isAlive == true) {
      // Do not block the Android main thread; bound graceful cleanup if the network is stuck.
      Thread({
        try { writing.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        try { connection?.close() } catch (_: Exception) {}
        writing.interrupt()
      }, "CanTalk-NvdaClose").apply { isDaemon = true; start() }
    } else try { connection?.close() } catch (_: Exception) {}
  }
}
