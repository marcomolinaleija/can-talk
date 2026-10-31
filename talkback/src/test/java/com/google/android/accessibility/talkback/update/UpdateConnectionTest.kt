/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.update

import android.app.Application
import android.content.ContextWrapper
import android.content.pm.PackageManager
import com.google.android.accessibility.talkback.BuildConfig
import com.google.android.accessibility.utils.FormFactorUtils
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONException
import org.junit.Assert.*
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
  private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
    override fun getPackageName() = "can.cantalk"
  }
  @Before fun setUp() { FormFactorUtils.initialize(context) }

  @Test fun checksStableApiThenManifestWithoutCredentials() {
    val api = FakeConnection(UpdateFixtures.release())
    val manifest = FakeConnection(UpdateFixtures.manifest(BuildConfig.COMMIT_COUNT + 1))
    val urls = mutableListOf<String>()
    val update = UpdateChecker.check(context) { _, url ->
      urls.add(url)
      if (url == UpdateChecker.RELEASE_URL) api else manifest
    }!!
    assertEquals(listOf("https://api.github.com/repos/marcomolinaleija/can-talk/releases/latest", UpdateFixtures.url("update.json")), urls)
    assertEquals(BuildConfig.COMMIT_COUNT + 1, update.build)
    assertEquals(UpdateFixtures.url("cantalk.apk"), update.downloadUrl)
    assertEquals("application/vnd.github+json", api.getRequestProperty("Accept"))
    assertEquals("2026-03-10", api.getRequestProperty("X-GitHub-Api-Version"))
    for (connection in listOf(api, manifest)) {
      assertNull(connection.getRequestProperty("Authorization"))
      assertFalse(connection.useCaches)
      assertTrue(connection.disconnected)
    }
  }

  @Test fun watchUsesWatchApk() {
    shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_WATCH, true)
    FormFactorUtils.initialize(context)
    val update = UpdateChecker.check(context) { _, url -> FakeConnection(
      if (url == UpdateChecker.RELEASE_URL) UpdateFixtures.release() else UpdateFixtures.manifest(BuildConfig.COMMIT_COUNT + 1)) }!!
    assertEquals(UpdateFixtures.url("cantalk-wear.apk"), update.downloadUrl)
  }

  @Test fun newerLocalBuildIsNotDowngraded() {
    assertNull(UpdateChecker.check(context) { _, url -> FakeConnection(
      if (url == UpdateChecker.RELEASE_URL) UpdateFixtures.release() else UpdateFixtures.manifest(BuildConfig.COMMIT_COUNT - 1)) })
  }

  @Test fun httpAndNetworkFailuresAreNotUpToDateAndDisconnect() {
    for (status in listOf(403, 404, 429, 500)) {
      for (failManifest in listOf(false, true)) {
        val connection = FakeConnection("", status)
        val error = assertThrows(IOException::class.java) {
          UpdateChecker.check(context) { _, url ->
            if (failManifest && url == UpdateChecker.RELEASE_URL) FakeConnection(UpdateFixtures.release()) else connection
          }
        }
        assertTrue(error.message!!.contains("HTTP $status"))
        assertTrue(connection.disconnected)
      }
    }
    assertThrows(IOException::class.java) { UpdateChecker.check(context) { _, _ -> throw IOException("Offline") } }
  }

  @Test fun malformedJsonClosesConnection() {
    val connection = FakeConnection("not JSON")
    assertThrows(JSONException::class.java) { UpdateChecker.check(context) { _, _ -> connection } }
    assertTrue(connection.disconnected)
  }

  @Test fun unpublishedPackageDoesNotAccessNetwork() {
    val other = object : ContextWrapper(context) { override fun getPackageName() = "can.cantalk.cantalktest" }
    assertThrows(IOException::class.java) {
      UpdateChecker.check(other) { _, _ -> throw AssertionError("No request expected") }
    }
  }

  @Test fun realConnectionHasTimeoutsAndUserAgent() {
    val connection = UpdateChecker.openConnection(context, UpdateChecker.RELEASE_URL)
    try {
      assertEquals(30000, connection.connectTimeout)
      assertEquals(30000, connection.readTimeout)
      assertTrue(connection.getRequestProperty("User-Agent").startsWith("CAN-Talk/"))
      assertNull(connection.getRequestProperty("Authorization"))
    } finally { connection.disconnect() }
  }

  private class FakeConnection(private val body: String, private val status: Int = 200) : HttpURLConnection(URL(UpdateChecker.RELEASE_URL)) {
    var disconnected = false
    override fun getResponseCode() = status
    override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    override fun connect() {}
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
  }
}
