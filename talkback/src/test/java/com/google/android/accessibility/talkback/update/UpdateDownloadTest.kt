/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.update

import android.app.Application
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class UpdateDownloadTest {
  private val bytes = "APK fixture".toByteArray()
  private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  @Test fun downloadChecksDigestAndClosesConnection() {
    val connection = DownloadConnection(bytes)
    val file = Updater.download(RuntimeEnvironment.getApplication(), UpdateFixtures.url("cantalk.apk"), hash) { _, _ -> connection }
    try { assertArrayEquals(bytes, file.readBytes()) } finally { file.delete() }
    assertTrue(connection.disconnected)
  }

  @Test fun corruptAndFailedDownloadsLeaveNoApk() {
    val context = RuntimeEnvironment.getApplication()
    for (connection in listOf(DownloadConnection(bytes), DownloadConnection(bytes, 500))) {
      assertThrows(IOException::class.java) {
        Updater.download(context, UpdateFixtures.url("cantalk.apk"), UpdateFixtures.DIGEST) { _, _ -> connection }
      }
      assertFalse(java.io.File(context.cacheDir, "update/cantalk.apk").exists())
      assertTrue(connection.disconnected)
    }
  }

  @Test fun foreignUrlAndMissingChecksumNeverStartDownload() {
    for ((url, checksum) in listOf("https://example.com/cantalk.apk" to hash, UpdateFixtures.url("cantalk.apk") to "")) {
      assertThrows(IOException::class.java) {
        Updater.download(RuntimeEnvironment.getApplication(), url, checksum) { _, _ -> throw AssertionError("No network expected") }
      }
    }
  }

  private class DownloadConnection(private val bytes: ByteArray, private val status: Int = 200) : HttpURLConnection(URL(UpdateFixtures.url("cantalk.apk"))) {
    var disconnected = false
    override fun getResponseCode() = status
    override fun getInputStream() = ByteArrayInputStream(bytes)
    override fun connect() {}
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
  }
}
