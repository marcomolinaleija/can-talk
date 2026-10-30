/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

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

/** A development build that is newer than the running one. */
data class UpdateInfo(
  /** The build number, which is the number of commits in the build. */
  val build: Int,
  /** Where to download the APK. */
  val downloadUrl: String,
  /** The subjects of the commits that are newer than the running build, newest first. */
  val changes: List<String>,
  /** True if the build is Backtalk under its new app ID, which installs as a new app. */
  val move: Boolean = false,
)

/**
 * Checks a rolling pre-release on GitHub for a newer development build.
 *
 * CAN Talk publishes phone and watch APKs on its own "dev" pre-release. No request is sent to
 * Backtalk's repository. The release must be publicly readable for checks without credentials.
 *
 * CI writes the release notes as a line "build: <commit count>", then one line per commit as "-
 * <7-character hash> <subject>", newest first. A release is newer only when its commit count is
 * higher, so a local build of a newer commit is never offered an older build.
 */
object UpdateChecker {
  @VisibleForTesting
  internal const val RELEASE_URL =
    "https://api.github.com/repos/marcomolinaleija/can-talk/releases/tags/dev"

  /** The phone build, which is also what builds before the watch build was published look for. */
  @VisibleForTesting const val PHONE_APK_NAME = "cantalk.apk"
  /** The watch build, so that a watch never installs the phone app. */
  @VisibleForTesting const val WATCH_APK_NAME = "cantalk-wear.apk"
  private const val SHORT_HASH_LENGTH = 7
  private const val MAX_CHANGES = 50
  private const val TIMEOUT_MS = 30_000

  private val BUILD_LINE = Regex("""build:\s*(\d+)""")

  /**
   * Returns the newer build, or null if this build is current. This blocks on the network.
   *
   * @throws IOException if the release is inaccessible, or this app uses an unpublished app ID.
   * @throws JSONException if the release is not in the expected format.
   */
  @WorkerThread
  fun check(context: Context): UpdateInfo? = check(context, ::openConnection)

  /** The independent test app has no matching APK on the published release. */
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
    val json = fetchRelease(connectionFactory(context, RELEASE_URL))
    return parseRelease(json, BuildConfig.COMMIT_COUNT, BuildConfig.COMMIT_HASH, apkName())
  }

  /** An inaccessible release is an error, never evidence that the installed build is current. */
  @WorkerThread
  private fun fetchRelease(connection: HttpURLConnection): String {
    try {
      connection.setRequestProperty("Accept", "application/vnd.github+json")
      val code = connection.responseCode
      if (code != HttpURLConnection.HTTP_OK) {
        throw IOException("GitHub returned HTTP $code")
      }
      return connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
      connection.disconnect()
    }
  }

  /** Parses a legacy app-ID migration; normal CAN Talk checks never offer a migration. */
  @VisibleForTesting
  fun parseMove(
    json: String,
    currentHash: String,
    apkName: String = PHONE_APK_NAME,
  ): UpdateInfo? = parseRelease(json, currentBuild = 0, currentHash, apkName)?.copy(move = true)

  /** Opens a connection with the timeouts and user agent that GitHub requests should use. */
  fun openConnection(context: Context, url: String): HttpURLConnection {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = TIMEOUT_MS
    connection.readTimeout = TIMEOUT_MS
    connection.setRequestProperty("User-Agent", "CAN-Talk/${versionName(context)}")
    return connection
  }

  private fun apkName(): String =
    if (FormFactorUtils.isAndroidWear()) WATCH_APK_NAME else PHONE_APK_NAME

  @VisibleForTesting
  fun parseRelease(
    json: String,
    currentBuild: Int,
    currentHash: String,
    apkName: String = PHONE_APK_NAME,
  ): UpdateInfo? {
    val release = JSONObject(json)
    val lines = release.optString("body").lines().map { it.trim() }
    val build =
      lines.firstNotNullOfOrNull { BUILD_LINE.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        ?: throw JSONException("The release has no build number")
    if (build <= currentBuild) {
      return null
    }

    val assets = release.getJSONArray("assets")
    val downloadUrl =
      (0 until assets.length())
        .map { assets.getJSONObject(it) }
        .firstOrNull { it.optString("name") == apkName }
        ?.getString("browser_download_url")
        ?: throw JSONException("The release has no $apkName")

    val commits = lines.filter { it.startsWith("- ") }.map { it.removePrefix("- ") }
    val shortHash = currentHash.take(SHORT_HASH_LENGTH)
    val currentIndex =
      if (shortHash.length == SHORT_HASH_LENGTH) {
        commits.indexOfFirst { it.substringBefore(' ') == shortHash }
      } else {
        -1
      }
    val newCommits =
      if (currentIndex >= 0) {
        commits.subList(0, currentIndex)
      } else {
        commits.take((build - currentBuild).coerceAtMost(MAX_CHANGES))
      }
    return UpdateInfo(build, downloadUrl, newCommits.map { it.substringAfter(' ') })
  }

  private fun versionName(context: Context): String =
    try {
      context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
      ""
    }
}
