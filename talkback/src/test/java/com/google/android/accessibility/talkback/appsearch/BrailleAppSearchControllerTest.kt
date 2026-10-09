/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.appsearch

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Region
import android.os.UserManager
import android.view.KeyEvent
import com.google.android.accessibility.brailleime.search.BrailleAppSearchInput
import com.google.android.accessibility.brailleime.search.BrailleAppSearchInput.Action
import com.google.android.accessibility.brailleime.search.BrailleSearchQuery
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.utils.FormFactorUtils
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "es-rMX")
@LooperMode(LooperMode.Mode.PAUSED)
class BrailleAppSearchControllerTest {
  class RecordingService : TalkBackService() {
    var region: Region? = null
    var launched: Intent? = null
    var rejectLaunch = false
    override fun onCreate() { }
    override fun onDestroy() { }
    override fun setModalTouchRegion(region: Region?) { this.region = region }
    override fun startActivity(intent: Intent) {
      if (rejectLaunch) throw android.content.ActivityNotFoundException()
      launched = intent
    }
  }

  private lateinit var service: RecordingService
  private lateinit var controller: BrailleAppSearchController
  private val spoken = mutableListOf<String>()

  @Before fun setup() {
    service = Robolectric.buildService(RecordingService::class.java).create().get()
    FormFactorUtils.initialize(service)
    shadowOf(service.getSystemService(UserManager::class.java)).setUserUnlocked(true)
    addApp("YouTube", "youtube")
    addApp("YouTube Music", "music")
    addApp("YouTube disabled", "disabled", enabled = false)
    addApp("YouTube private", "private", exported = false)
    addApp("YouTube suspended", "suspended", suspended = true)
    controller = BrailleAppSearchController(service, Pipeline.FeedbackReturner { feedback ->
      feedback.failovers().forEach { part -> part.speech()?.text()?.let { spoken.add(it.toString()) } }
      true
    }) { size, listener ->
      BrailleAppSearchInput(service, size, listener, BrailleSearchQuery { "youtube" })
    }
  }

  @After fun tearDown() { controller.shutdown() }

  private fun addApp(label: String, pkg: String, enabled: Boolean = true,
      exported: Boolean = true, suspended: Boolean = false) {
    val result = ResolveInfo().apply {
      nonLocalizedLabel = label
      activityInfo = ActivityInfo().apply {
        packageName = pkg
        name = "$pkg.Main"
        this.enabled = enabled
        this.exported = exported
        applicationInfo = ApplicationInfo().apply {
          packageName = pkg
          this.enabled = enabled
          flags = if (suspended) ApplicationInfo.FLAG_SUSPENDED else 0
        }
      }
    }
    // Launcher activities need not declare CATEGORY_DEFAULT; querying with MATCH_DEFAULT_ONLY
    // would incorrectly hide these apps on real devices.
    shadowOf(service.packageManager).addResolveInfoForIntent(
      Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), result)
  }

  @Test fun typingFindsLaunchableAppsAndTwoFingersRightOpensTheSelectedComponent() {
    assertTrue(controller.show())
    assertNotNull(service.region)
    controller.onQueryChanged("you")
    assertEquals("YouTube. 1 de 2", spoken.last())
    controller.onAction(Action.NEXT)
    assertEquals("YouTube Music. 2 de 2", spoken.last())
    controller.onAction(Action.OPEN)
    assertFalse(controller.isShowing)
    assertNull(service.region)
    assertEquals("music", service.launched!!.component!!.packageName)
    assertEquals("music.Main", service.launched!!.component!!.className)
    assertTrue(service.launched!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
  }

  @Test fun emptyOrMissingResultsCannotLaunchAndSearchRemainsOpen() {
    assertTrue(controller.show())
    controller.onAction(Action.OPEN)
    assertNull(service.launched)
    controller.onQueryChanged("not installed")
    controller.onAction(Action.OPEN)
    assertNull(service.launched)
    assertTrue(controller.isShowing)
    assertTrue(spoken.last().contains("Sin aplicaciones coincidentes"))
  }

  @Test fun failedLaunchRestoresTouchExplorationAndExplainsTheFailure() {
    assertTrue(controller.show())
    controller.onQueryChanged("youtube")
    service.rejectLaunch = true
    controller.onAction(Action.OPEN)
    assertFalse(controller.isShowing)
    assertNull(service.region)
    assertEquals("No se pudo abrir YouTube", spoken.last())
  }

  @Test fun backClosesAndLateCallbacksCannotReopenOrLaunch() {
    assertTrue(controller.show())
    controller.onQueryChanged("youtube")
    assertTrue(controller.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
    assertTrue(controller.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
    assertEquals("Búsqueda de aplicaciones cerrada", spoken.last())
    controller.onAction(Action.OPEN)
    assertNull(service.launched)
    assertNull(service.region)
    assertFalse(controller.isShowing)
  }

  @Test fun reopeningStartsWithAnEmptyQueryAndRequiresNewInputToLaunch() {
    assertTrue(controller.show())
    controller.onQueryChanged("youtube")
    controller.dismiss()
    assertTrue(controller.show())
    controller.onAction(Action.OPEN)
    assertNull(service.launched)
    assertEquals("Escribe el nombre de una aplicación en braille", spoken.last())
  }
}
