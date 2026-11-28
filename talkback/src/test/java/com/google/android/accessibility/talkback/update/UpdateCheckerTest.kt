/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.update

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal object UpdateFixtures {
  const val DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  fun url(name: String, tag: String = "1.0.3", repository: String = "can-apps/can-talk") =
    "https://github.com/$repository/releases/download/$tag/$name"
  fun release(
    body: Any = "Notas completas\n\n- Corrección de voz\n- Mejoras sin hashes",
    tag: String = "1.0.3",
    repository: String = "can-apps/can-talk",
  ): String =
    JSONObject().put("tag_name", tag).put("body", body)
      .put("draft", false).put("prerelease", false)
      .put("assets", JSONArray().apply {
        for (name in listOf("cantalk.apk", "cantalk-wear.apk", "update.json")) {
          put(JSONObject().put("name", name).put("state", "uploaded").put("browser_download_url", url(name, tag, repository)))
        }
      }).toString()
  fun manifest(build: Int, version: String = "1.0.3", digest: String = DIGEST): String = JSONObject().put("schemaVersion", 1)
    .put("apks", JSONObject().apply {
      for (name in listOf("cantalk.apk", "cantalk-wear.apk")) {
        put(name, JSONObject().put("versionCode", 70000000L + build)
          .put("versionName", if (name == "cantalk.apk") version else "$version-wear")
          .put("sha256", digest))
      }
    }).toString()
}

class UpdateCheckerTest {
  @Test fun olderAndSameBuildAreNotOffered() {
    for (build in listOf(9, 10)) {
      assertNull(UpdateChecker.parseRelease(UpdateFixtures.release(), UpdateFixtures.manifest(build), 10))
    }
  }

  @Test fun bodyIsEditorialTextNotAVersionContract() {
    for (body in listOf("Sin build ni commits", "# Novedades\n\n- Una mejora sin hash\n\nTexto final", "build: basura\n- abc1234 No recortar")) {
      val update = UpdateChecker.parseRelease(UpdateFixtures.release(body), UpdateFixtures.manifest(11), 10)!!
      assertEquals(11, update.build)
      assertEquals(70000011L, update.versionCode)
      assertEquals("1.0.3", update.versionName)
      assertEquals(listOf(body), update.changes)
      assertEquals(UpdateFixtures.DIGEST, update.sha256)
      assertFalse(update.move)
    }
  }

  @Test fun absentNotesDoNotPreventUpdates() {
    for (body in listOf("", JSONObject.NULL)) {
      assertTrue(UpdateChecker.parseRelease(UpdateFixtures.release(body), UpdateFixtures.manifest(11), 10)!!.changes.isEmpty())
    }
  }

  @Test fun devicesUseTheirOwnApks() {
    for (name in listOf(UpdateChecker.PHONE_APK_NAME, UpdateChecker.WATCH_APK_NAME)) {
      assertEquals(UpdateFixtures.url(name),
        UpdateChecker.parseRelease(UpdateFixtures.release(), UpdateFixtures.manifest(11), 10, name)!!.downloadUrl)
    }
    val release = JSONObject(UpdateFixtures.release()).put("assets", JSONArray()
      .put(JSONObject().put("name", "cantalk.apk").put("state", "uploaded")
        .put("browser_download_url", UpdateFixtures.url("cantalk.apk"))))
    assertThrows(JSONException::class.java) {
      UpdateChecker.parseRelease(release.toString(), UpdateFixtures.manifest(11), 10, UpdateChecker.WATCH_APK_NAME)
    }
  }

  @Test fun invalidMetadataIsNotReportedAsCurrent() {
    for (manifest in listOf(
      UpdateFixtures.manifest(11).replace("1.0.3", "1.0.4"),
      UpdateFixtures.manifest(11).replace(UpdateFixtures.DIGEST, "wrong"),
      UpdateFixtures.manifest(11).replace("70000011", "0"),
      UpdateFixtures.manifest(11).replace("schemaVersion\":1", "schemaVersion\":2"),
      "{}",
    )) {
      assertThrows(JSONException::class.java) { UpdateChecker.parseRelease(UpdateFixtures.release(), manifest, 10) }
    }
  }

  @Test fun unpublishedAndForeignAssetsAreRejected() {
    for (release in listOf(
      UpdateFixtures.release().replace("github.com", "example.com"),
      UpdateFixtures.release(repository = "marcomolinaleija/can-talk"),
      UpdateFixtures.release(repository = "can-apps/other-repo"),
      UpdateFixtures.release(repository = "can-apps-fake/can-talk"),
      UpdateFixtures.release().replace("https:", "http:"),
      UpdateFixtures.release().replace("\"draft\":false", "\"draft\":true"),
      UpdateFixtures.release().replace("\"prerelease\":false", "\"prerelease\":true"),
      UpdateFixtures.release().replace("\"uploaded\"", "\"new\""),
    )) {
      assertThrows(JSONException::class.java) { UpdateChecker.parseRelease(release, UpdateFixtures.manifest(11), 10) }
    }
  }

  @Test fun downloadUrlValidatorAcceptsOnlyExactOrgRepoAssetUrls() {
    assertTrue(UpdateChecker.isDownloadUrl("https://github.com/can-apps/can-talk/releases/download/1.0.11/cantalk.apk"))
    assertTrue(UpdateChecker.isDownloadUrl("https://github.com/can-apps/can-talk/releases/download/1.0.11/update.json"))
    for (badUrl in listOf(
      "https://github.com/marcomolinaleija/can-talk/releases/download/1.0.10/cantalk.apk",
      "https://github.com/can-apps/other-repo/releases/download/1.0.11/cantalk.apk",
      "https://github.com/can-apps-fake/can-talk/releases/download/1.0.11/cantalk.apk",
      "http://github.com/can-apps/can-talk/releases/download/1.0.11/cantalk.apk",
      "https://github.com:443/can-apps/can-talk/releases/download/1.0.11/cantalk.apk",
      "https://user@github.com/can-apps/can-talk/releases/download/1.0.11/cantalk.apk",
      "https://github.com/can-apps/can-talk/releases/download/1.0.11/cantalk.apk?raw=1",
      "https://github.com/can-apps/can-talk/releases/download/1.0.11/cantalk.apk#frag",
      "https://api.github.com/repos/can-apps/can-talk/releases/latest",
      "not a url",
    )) {
      assertFalse("Expected rejection for $badUrl", UpdateChecker.isDownloadUrl(badUrl))
    }
  }

  @Test fun parsesRealisticGitHubLatestReleaseResponse() {
    val apiRelease = JSONObject()
      .put("url", "https://api.github.com/repos/can-apps/can-talk/releases/5001")
      .put("html_url", "https://github.com/can-apps/can-talk/releases/tag/1.0.11")
      .put("tag_name", "1.0.11")
      .put("name", "CAN Talk 1.0.11")
      .put("draft", false)
      .put("prerelease", false)
      .put("body", "CAN Talk 1.0.11\r\n\r\n- Cambio uno\r\n- Cambio dos\r\n")
      .put("assets", JSONArray().apply {
        for (name in listOf("cantalk.apk", "release-notes.txt", "update.json")) {
          put(
            JSONObject()
              .put("name", name)
              .put("state", "uploaded")
              .put("browser_download_url", UpdateFixtures.url(name, tag = "1.0.11"))
          )
        }
      })
      .toString()
    val upperDigest = UpdateFixtures.DIGEST.uppercase()
    val manifest = UpdateFixtures.manifest(361, version = "1.0.11", digest = upperDigest)
    val update = UpdateChecker.parseRelease(apiRelease, manifest, 359)!!
    assertEquals(361, update.build)
    assertEquals(70000361L, update.versionCode)
    assertEquals("1.0.11", update.versionName)
    assertEquals(UpdateFixtures.url("cantalk.apk", tag = "1.0.11"), update.downloadUrl)
    assertEquals(UpdateFixtures.DIGEST, update.sha256)
    assertEquals(listOf("CAN Talk 1.0.11\r\n\r\n- Cambio uno\r\n- Cambio dos"), update.changes)
  }

  @Test fun onlyPublishedPackageSupportsUpdates() {
    assertTrue(UpdateChecker.supportsPackage("can.cantalk"))
    for (name in listOf("can.cantalk.cantalktest", "fyi.quin.backtalk", "com.android.talkback")) {
      assertFalse(UpdateChecker.supportsPackage(name))
    }
  }
}
