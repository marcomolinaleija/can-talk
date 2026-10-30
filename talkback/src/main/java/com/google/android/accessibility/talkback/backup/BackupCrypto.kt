/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.backup

import android.util.Base64
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** Password-based portable encryption; no device-bound keystore or built-in app secret. */
internal object BackupCrypto {
  const val FORMAT = "CAN_TALK_ENCRYPTED_BACKUP"
  private const val ITERATIONS = 600_000
  private val aad = "$FORMAT:1:PBKDF2-HMAC-SHA256:$ITERATIONS:AES-256-GCM".toByteArray(Charsets.UTF_8)

  fun encrypted(root: JSONObject): Boolean = root.opt("format") == FORMAT

  fun encrypt(json: String, password: CharArray): String {
    if (password.size !in 8..256) throw BackupException(BackupFailure.PASSWORD_REQUIRED)
    val random = SecureRandom()
    val salt = ByteArray(16).also(random::nextBytes)
    val nonce = ByteArray(12).also(random::nextBytes)
    val bytes = json.toByteArray(Charsets.UTF_8)
    val encrypted = try { crypt(Cipher.ENCRYPT_MODE, bytes, password, salt, nonce) }
      finally { bytes.fill(0) }
    return JSONObject().put("format", FORMAT).put("version", 1)
      .put("kdf", "PBKDF2-HMAC-SHA256").put("iterations", ITERATIONS)
      .put("cipher", "AES-256-GCM").put("salt", encode(salt)).put("nonce", encode(nonce))
      .put("data", encode(encrypted)).toString().also {
        encrypted.fill(0)
        if (it.length > BackupJson.MAX_BYTES) throw BackupException(BackupFailure.TOO_LARGE)
      }
  }

  fun decrypt(root: JSONObject, password: CharArray): String {
    BackupJson.fields(root, setOf("format", "version", "kdf", "iterations", "cipher", "salt", "nonce", "data"))
    if (!encrypted(root) || BackupJson.integer(root.opt("version")) != 1L
      || root.opt("kdf") != "PBKDF2-HMAC-SHA256" || root.opt("cipher") != "AES-256-GCM"
      || BackupJson.integer(root.opt("iterations")) != ITERATIONS.toLong())
      throw BackupException(BackupFailure.UNSUPPORTED_VERSION)
    if (password.isEmpty() || password.size > 256) throw BackupException(BackupFailure.PASSWORD_REQUIRED)
    val salt = decode(BackupJson.string(root, "salt", 24))
    val nonce = decode(BackupJson.string(root, "nonce", 16))
    val data = decode(BackupJson.string(root, "data", BackupJson.MAX_BYTES))
    if (salt.size != 16 || nonce.size != 12 || data.size !in 16..BackupJson.MAX_BYTES)
      throw BackupException(BackupFailure.INVALID_FORMAT)
    val plain = try { crypt(Cipher.DECRYPT_MODE, data, password, salt, nonce) }
      catch (_: GeneralSecurityException) { throw BackupException(BackupFailure.WRONG_PASSWORD) }
      finally { data.fill(0) }
    return try { BackupJson.decode(plain) } finally { plain.fill(0) }
  }

  private fun crypt(mode: Int, input: ByteArray, password: CharArray, salt: ByteArray, nonce: ByteArray): ByteArray {
    val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
    val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
      finally { spec.clearPassword() }
    return try {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
      cipher.updateAAD(aad)
      cipher.doFinal(input)
    } finally { key.fill(0) }
  }

  fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
  fun decode(value: String): ByteArray = try {
    // Android's decoder tolerates whitespace/invalid characters; insist on canonical base64.
    if (!value.matches(Regex("[A-Za-z0-9+/]*={0,2}"))) throw IllegalArgumentException()
    Base64.decode(value, Base64.NO_WRAP).also { if (encode(it) != value) throw IllegalArgumentException() }
  } catch (_: IllegalArgumentException) { throw BackupException(BackupFailure.INVALID_FORMAT) }
}
