/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Private durable undo journal. Device-bound encryption is for recovery, NOT portable exports. */
internal object BackupRecovery {
  private const val ALIAS = "can_talk_backup_recovery_v1"
  private val aad = "CAN_TALK_BACKUP_RECOVERY:1".toByteArray(Charsets.UTF_8)

  private fun file(context: Context): AtomicFile =
    AtomicFile(File(context.applicationContext.noBackupFilesDir, "backup-recovery/undo.enc"))

  fun pending(context: Context): Boolean {
    val base = file(context).baseFile
    return base.isFile || File(base.path + ".bak").isFile
  }

  fun write(context: Context, journal: JSONObject) {
    if (pending(context)) throw BackupException(BackupFailure.ROLLBACK_FAILED)
    val file = file(context)
    if (!file.baseFile.parentFile!!.isDirectory && !file.baseFile.parentFile!!.mkdirs())
      throw BackupException(BackupFailure.IO)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key())
    cipher.updateAAD(aad)
    val plain = journal.toString().toByteArray(Charsets.UTF_8)
    val encrypted = try {
      if (plain.size > BackupJson.MAX_BYTES) throw BackupException(BackupFailure.TOO_LARGE)
      cipher.doFinal(plain)
    } finally { plain.fill(0) }
    var stream: java.io.FileOutputStream? = null
    try {
      stream = file.startWrite()
      stream.write(1)
      stream.write(cipher.iv)
      stream.write(encrypted)
      file.finishWrite(stream)
      stream = null
    } catch (_: Exception) {
      if (stream != null) file.failWrite(stream)
      throw BackupException(BackupFailure.IO)
    } finally { encrypted.fill(0) }
  }

  fun read(context: Context): JSONObject {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val bytes = file(context).openRead().use { stream ->
      val output = java.io.ByteArrayOutputStream()
      val buffer = ByteArray(8192)
      while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        if (output.size() + count > BackupJson.MAX_BYTES + 29) throw BackupException(BackupFailure.TOO_LARGE)
        output.write(buffer, 0, count)
      }
      output.toByteArray()
    }
    if (bytes.size < 29 || bytes[0].toInt() != 1) throw BackupException(BackupFailure.INVALID_FORMAT)
    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
    cipher.updateAAD(aad)
    val plain = try { cipher.doFinal(bytes, 13, bytes.size - 13) } finally { bytes.fill(0) }
    return try { BackupJson.parse(BackupJson.decode(plain)) } finally { plain.fill(0) }
  }

  fun clear(context: Context) {
    file(context).delete()
    if (pending(context)) throw BackupException(BackupFailure.IO)
  }

  private fun key(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256).build())
    return generator.generateKey()
  }
}
