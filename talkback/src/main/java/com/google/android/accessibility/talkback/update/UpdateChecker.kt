/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.update

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.google.android.accessibility.talkback.BuildConfig
import com.google.android.accessibility.utils.FormFactorUtils
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONException
import org.json.JSONObject

/** Published APK metadata. Editorial release notes never determine update eligibility. */
data class UpdateInfo(
  val build: Int,
  val downloadUrl: String,
  val changes: List<String>,
  val move: Boolean = false,
  val versionName: String = "",
  val sha256: String = "",
) {
  val versionCode: Long get() = UpdateChecker.VERSION_CODE_BASE + build.toLong()
}

/** Checks the latest stable public GitHub release, without embedding any credentials. */
object UpdateChecker {
  internal const val VERSION_CODE_BASE = 70000000
  private const val REPOSITORY = "marcomolinaleija/can-talk"
  @VisibleForTesting
  internal const val RELEASE_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
  @VisibleForTesting const val PHONE_APK_NAME = "cantalk.apk"
  @VisibleForTesting const val WATCH_APK_NAME = "cantalk-wear.apk"
  internal const val MANIFEST_NAME = "update.json"
  private const val TIMEOUT_MS = 30_000
  private const val MAX_JSON_CHARS = 1_000_000

  @WorkerThread
  fun check(context: Context): UpdateInfo? = check(context, ::openConnection)

  internal fun supportsPackage(packageName: String): Boolean =
    packageName == BuildConfig.PUBLISHED_APPLICATION_ID

  @VisibleForTesting
  @WorkerThread
  internal fun check(
    context: Context,
    connectionFactory: (Context, String) -> HttpURLConnection,
  ): UpdateInfo? {
    if (!supportsPackage(context.packageName)) {
      throw IOException("No published updates for ${context.packageName}")
    }
    val release = JSONObject(fetch(connectionFactory(context, RELEASE_URL), api = true))
    val manifestUrl = assetUrl(release, MANIFEST_NAME)
    val manifest = fetch(connectionFactory(context, manifestUrl), api = false)
    return parseRelease(release.toString(), manifest, BuildConfig.COMMIT_COUNT, apkName())
  }

  /** HTTP errors (including rate limits and unpublished releases) are never 'up to date'. */
  private fun fetch(connection: HttpURLConnection, api: Boolean): String {
    try {
      connection.useCaches = false
      connection.setRequestProperty("Accept", if (api) "application/vnd.github+json" else "application/json")
      if (api) connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
      val code = connection.responseCode
      if (code != HttpURLConnection.HTTP_OK) throw IOException("GitHub returned HTTP $code")
      return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readTextBounded() }
    } finally {
      connection.disconnect()
    }
  }

  private fun java.io.Reader.readTextBounded(): String {
    val result = StringBuilder()
    val buffer = CharArray(8192)
    while (true) {
      val count = read(buffer)
      if (count < 0) return result.toString()
      if (result.length + count > MAX_JSON_CHARS) throw IOException("Release metadata is too large")
      result.append(buffer, 0, count)
    }
  }

  fun openConnection(context: Context, url: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
      connectTimeout = TIMEOUT_MS
      readTimeout = TIMEOUT_MS
      setRequestProperty("User-Agent", "CAN-Talk/${BuildConfig.VERSION_NAME}")
    }

  private fun apkName(): String =
    if (FormFactorUtils.isAndroidWear()) WATCH_APK_NAME else PHONE_APK_NAME

  /** Accept only immutable, same-repository GitHub asset URLs from the selected release. */
  internal fun isDownloadUrl(url: String): Boolean =
    try {
      val parsed = URL(url)
      parsed.protocol == "https" && parsed.host == "github.com" && parsed.port == -1 &&
        parsed.userInfo == null && parsed.query == null && parsed.ref == null &&
        parsed.path.startsWith("/$REPOSITORY/releases/download/")
    } catch (_: Exception) { false }

  private fun assetUrl(release: JSONObject, name: String): String {
    if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
      throw JSONException("Expected a published stable release")
    }
    val tag = release.getString("tag_name")
    if (!Regex("v?\\d+\\.\\d+\\.\\d+").matches(tag)) throw JSONException("Invalid stable tag")
    val assets = release.getJSONArray("assets")
    val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
      .singleOrNull { it.optString("name") == name && it.optString("state") == "uploaded" }
      ?: throw JSONException("The release has no uploaded $name")
    val url = asset.getString("browser_download_url")
    if (!isDownloadUrl(url) || URL(url).path != "/$REPOSITORY/releases/download/$tag/$name") {
      throw JSONException("Unexpected asset URL")
    }
    return url
  }

  @VisibleForTesting
  fun parseRelease(
    json: String,
    manifestJson: String,
    currentBuild: Int,
    apkName: String = PHONE_APK_NAME,
  ): UpdateInfo? {
    val release = JSONObject(json)
    val downloadUrl = assetUrl(release, apkName)
    val manifest = JSONObject(manifestJson)
    if (manifest.getInt("schemaVersion") != 1) throw JSONException("Unsupported update schema")
    val apk = manifest.getJSONObject("apks").getJSONObject(apkName)
    val versionCode = apk.getLong("versionCode")
    val build = versionCode - VERSION_CODE_BASE
    if (build !in 1..(Int.MAX_VALUE - VERSION_CODE_BASE).toLong()) throw JSONException("Invalid version code")
    val version = apk.getString("versionName")
    val expectedVersion = release.getString("tag_name").removePrefix("v") +
      if (apkName == WATCH_APK_NAME) "-wear" else ""
    if (expectedVersion != version) {
      throw JSONException("Release and APK versions differ")
    }
    val digest = apk.getString("sha256")
    if (!Regex("[a-fA-F0-9]{64}").matches(digest)) throw JSONException("Invalid APK checksum")
    if (build <= currentBuild) return null
    // Preserve the complete editorial body: no hash stripping, bullet limits or build regexes.
    val notes = if (release.isNull("body")) "" else release.optString("body").trim()
    return UpdateInfo(build.toInt(), downloadUrl, listOfNotNull(notes.takeIf { it.isNotEmpty() }),
      versionName = version, sha256 = digest.lowercase())
  }
}
