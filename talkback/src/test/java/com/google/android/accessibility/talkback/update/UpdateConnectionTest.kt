/*
 * Copyright 2026 can-talk contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.google.android.accessibility.talkback.update

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import com.google.android.accessibility.talkback.BuildConfig
import com.google.android.accessibility.utils.FormFactorUtils
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateConnectionTest {
  private val context =
    object : ContextWrapper(RuntimeEnvironment.getApplication()) {
      override fun getPackageName() = "can.cantalk"
    }

  @Before
  fun setUp() {
    FormFactorUtils.initialize(context)
  }

  @Test
  fun phoneChecksOurDevReleaseAndGetsThePhoneApk() {
    val connection = FakeConnection(release(BuildConfig.COMMIT_COUNT + 1))
    val update = UpdateChecker.check(context) { _, url ->
      assertEquals("https://api.github.com/repos/marcomolinaleija/can-talk/releases/tags/dev", url)
      connection
    }
    assertEquals(BuildConfig.COMMIT_COUNT + 1, update!!.build)
    assertEquals(downloadUrl("cantalk.apk"), update.downloadUrl)
    assertFalse(update.move)
    assertEquals("application/vnd.github+json", connection.getRequestProperty("Accept"))
    assertNull(connection.getRequestProperty("Authorization"))
    assertTrue(connection.disconnected)
  }

  @Test
  fun watchChecksOurReleaseAndGetsTheWatchApk() {
    shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_WATCH, true)
    FormFactorUtils.initialize(context)
    val update = UpdateChecker.check(context) { _, _ ->
      FakeConnection(release(BuildConfig.COMMIT_COUNT + 1))
    }
    assertEquals(downloadUrl("cantalk-wear.apk"), update!!.downloadUrl)
  }

  @Test
  fun newerLocalBuildIsNeverDowngraded() {
    val update = UpdateChecker.check(context) { _, _ ->
      FakeConnection(release(BuildConfig.COMMIT_COUNT - 1))
    }
    assertNull(update)
  }

  @Test
  fun inaccessiblePrivateReleaseAndNetworkErrorsAreNotReportedAsUpToDate() {
    for (status in listOf(403, 404, 429, 500)) {
      val connection = FakeConnection("", status)
      val error = assertThrows(IOException::class.java) {
        UpdateChecker.check(context) { _, _ -> connection }
      }
      assertTrue(error.message!!.contains("HTTP $status"))
      assertTrue(connection.disconnected)
    }
    assertThrows(IOException::class.java) {
      UpdateChecker.check(context) { _, _ -> throw IOException("Offline") }
    }
  }

  @Test
  fun malformedReleaseIsAnErrorAndConnectionIsClosed() {
    val connection = FakeConnection("not JSON")
    assertThrows(JSONException::class.java) {
      UpdateChecker.check(context) { _, _ -> connection }
    }
    assertTrue(connection.disconnected)
  }

  @Test
  fun independentTestAppDoesNotRequestAnApkWithADifferentPackage() {
    val testContext = object : ContextWrapper(context) {
      override fun getPackageName() = "can.cantalk.cantalktest"
    }
    assertThrows(IOException::class.java) {
      UpdateChecker.check(testContext) { _, _ ->
        throw AssertionError("The unpublished test app must not make an update request")
      }
    }
  }

  @Test
  fun realConnectionHasTimeoutsAndCanTalkUserAgentWithoutCredentials() {
    val connection = UpdateChecker.openConnection(context, UpdateChecker.RELEASE_URL)
    try {
      assertEquals(30_000, connection.connectTimeout)
      assertEquals(30_000, connection.readTimeout)
      assertTrue(connection.getRequestProperty("User-Agent").startsWith("CAN-Talk/"))
      assertNull(connection.getRequestProperty("Authorization"))
    } finally {
      connection.disconnect()
    }
  }

  private fun downloadUrl(name: String) =
    "https://github.com/marcomolinaleija/can-talk/releases/download/dev/$name"

  private fun release(build: Int): String =
    JSONObject()
      .put("body", "build: $build\n- bbbbbbb Update")
      .put("assets", JSONArray().apply {
        for (name in listOf("cantalk.apk", "cantalk-wear.apk")) {
          put(JSONObject().put("name", name).put("browser_download_url", downloadUrl(name)))
        }
      })
      .toString()

  private class FakeConnection(
    private val body: String,
    private val status: Int = HttpURLConnection.HTTP_OK,
  ) :
    HttpURLConnection(URL(UpdateChecker.RELEASE_URL)) {
    var disconnected = false
      private set

    override fun getResponseCode() = status
    override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    override fun connect() {}
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
  }
}
