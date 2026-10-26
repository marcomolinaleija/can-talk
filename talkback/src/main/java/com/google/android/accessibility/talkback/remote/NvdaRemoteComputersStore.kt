/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

internal data class NvdaRemoteEndpoint(val host: String, val port: Int) {
  companion object {
    fun parse(address: String, port: String): NvdaRemoteEndpoint? = runCatching {
      val value = address.trim()
      val authority = if (!value.startsWith("[") && value.count { it == ':' } > 1) "[$value]" else value
      val uri = URI("nvda://$authority")
      require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty())
      val host = requireNotNull(uri.host).removeSurrounding("[", "]")
      val number = if (uri.port != -1) uri.port else port.trim().toInt()
      require(host.isNotBlank() && host.length <= 253 && number in 1..65535)
      NvdaRemoteEndpoint(host, number)
    }.getOrNull()
  }
}

internal data class NvdaRemoteComputer(
  val id: String,
  val name: String,
  val host: String,
  val port: Int,
  val nvdaModifier: Int,
  val password: String?,
  val lastUsed: Long = 0,
) {
  // Never let an accidental diagnostic expose a saved password.
  override fun toString() = "NvdaRemoteComputer"
}

/** Device-bound, atomic storage. No profiles or passwords enter preferences or backup exports. */
internal class NvdaRemoteComputersStore(context: Context) {
  private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "nvda-remote/computers.enc"))

  fun list(): List<NvdaRemoteComputer> = synchronized(LOCK) {
    read().sortedWith(compareByDescending<NvdaRemoteComputer> { it.lastUsed }.thenBy { it.name.lowercase() })
  }

  fun save(computer: NvdaRemoteComputer): Unit = synchronized(LOCK) {
    require(computer.id.isNotBlank() && computer.name.isNotBlank() && computer.name.length <= 80)
    require(computer.nvdaModifier in listOf(0x2D, 0x14))
    require(NvdaRemoteEndpoint.parse(computer.host, computer.port.toString()) != null)
    require(computer.password == null || (computer.password.isNotBlank() && computer.password.length <= 1024))
    val items = read().toMutableList()
    if (items.any { it.id != computer.id && it.name.equals(computer.name, ignoreCase = true) }) throw DuplicateName()
    val index = items.indexOfFirst { it.id == computer.id }
    if (index >= 0) items[index] = computer.copy(lastUsed = maxOf(computer.lastUsed, items[index].lastUsed))
    else { require(items.size < 100); items.add(computer) }
    write(items)
  }

  fun delete(id: String): Unit = synchronized(LOCK) { write(read().filterNot { it.id == id }) }

  fun markUsed(id: String): Unit = synchronized(LOCK) {
    val items = read()
    if (items.any { it.id == id }) write(items.map { if (it.id == id) it.copy(lastUsed = System.currentTimeMillis()) else it })
  }

  private fun read(): List<NvdaRemoteComputer> {
    if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
    val bytes = file.openRead().use { input ->
      val output = java.io.ByteArrayOutputStream()
      val buffer = ByteArray(8192)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= MAX_BYTES)
        output.write(buffer, 0, count)
      }
      output.toByteArray()
    }
    require(bytes.size in 29..MAX_BYTES && bytes[0].toInt() == 1)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
    cipher.updateAAD(AAD)
    val plain = try { cipher.doFinal(bytes, 13, bytes.size - 13) } finally { bytes.fill(0) }
    val root = try { JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
    require(root.getInt("version") == 1)
    val items = root.getJSONArray("computers")
    require(items.length() <= 100)
    val result = (0 until items.length()).map { index ->
      val item = items.getJSONObject(index)
      NvdaRemoteComputer(item.getString("id"), item.getString("name"), item.getString("host"),
        item.getInt("port"), item.getInt("nvdaModifier"),
        if (item.isNull("password")) null else item.getString("password"), item.getLong("lastUsed"))
    }
    require(result.map { it.id }.distinct().size == result.size)
    return result
  }

  private fun write(items: List<NvdaRemoteComputer>) {
    val array = JSONArray()
    for (item in items) array.put(JSONObject().put("id", item.id).put("name", item.name)
      .put("host", item.host).put("port", item.port).put("nvdaModifier", item.nvdaModifier)
      .put("password", item.password ?: JSONObject.NULL).put("lastUsed", item.lastUsed))
    val plain = JSONObject().put("version", 1).put("computers", array).toString().toByteArray(Charsets.UTF_8)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key(true))
    cipher.updateAAD(AAD)
    val encrypted = try { require(plain.size + 29 <= MAX_BYTES); cipher.doFinal(plain) } finally { plain.fill(0) }
    val directory = file.baseFile.parentFile!!
    check(directory.isDirectory || directory.mkdirs())
    var output: java.io.FileOutputStream? = null
    try {
      output = file.startWrite()
      output.write(1)
      output.write(cipher.iv)
      output.write(encrypted)
      file.finishWrite(output)
      output = null
    } catch (failure: Exception) {
      if (output != null) file.failWrite(output)
      throw failure
    } finally { encrypted.fill(0) }
  }

  private fun key(create: Boolean): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
    check(create)
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256).build())
    return generator.generateKey()
  }

  class DuplicateName : Exception()
  companion object {
    internal val WORKER = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val LOCK = Any()
    private const val MAX_BYTES = 256 * 1024
    private const val ALIAS = "can_talk_nvda_computers_v1"
    private val AAD = "CAN_TALK_NVDA_COMPUTERS:1".toByteArray(Charsets.UTF_8)
  }
}
