/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Applies caches on the main thread while disk/crypto remain off the accessibility event thread. */
internal object BackupRuntime {
  private val main = Handler(Looper.getMainLooper())
  private val restarting = AtomicBoolean(false)
  val isRestarting: Boolean get() = restarting.get()

  fun restore(context: Context, document: BackupDocument, selected: Set<BackupCategory>): Int {
    val data = document.selected(selected)
    val restart = data.sections.any { (category, section) ->
      category in setOf(BackupCategory.SETTINGS, BackupCategory.API_KEYS, BackupCategory.BRAILLE_SETTINGS)
        && section.preferences.any { (store, entries) ->
          val current = store.open(context).all
          entries.any { current[it.key] != it.value }
        }
    }
    var restored = false
    var reloadFailed = false
    try {
      onMain {
        if (isRestarting) throw BackupException(BackupFailure.INTERRUPTED)
        TalkBackService.getInstance()?.beginBackupRestore()
        BackupBrailleSupport.beginRestore()
      }
      val count = SelectiveBackup.restore(context, document, selected)
      restored = true
      return count
    } finally {
      try {
        onMain {
          TalkBackService.getInstance()?.finishBackupRestore()
          BackupBrailleSupport.finishRestore()
        }
      } catch (_: Exception) { reloadFailed = true }
      if (restored && (restart || reloadFailed)) restartIfActive(context)
      if (reloadFailed) main.post {
        Toast.makeText(context, R.string.backup_runtime_failed, Toast.LENGTH_LONG).show()
      }
    }
  }
  private fun onMain(work: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) { work(); return }
    val task = FutureTask<Unit> { work() }
    if (!main.post(task)) throw BackupException(BackupFailure.IO)
    try { task.get(10, TimeUnit.SECONDS) }
    catch (_: Exception) { task.cancel(false); throw BackupException(BackupFailure.IO) }
  }
  /** Only our process restarts; enabled services, permissions and the selected IME are untouched. */
  fun restartIfActive(context: Context) {
    main.post {
      if (!TalkBackService.isServiceActive() || !restarting.compareAndSet(false, true)) return@post
      try {
        // Keep the system accessibility binding (never force-stop or disableSelf).
        val intent = Intent(context, TalkBackPreferencesActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 72016, intent,
          PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
          AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 4000, pending)
        Toast.makeText(context, R.string.backup_restarting, Toast.LENGTH_LONG).show()
        main.postDelayed({ Process.killProcess(Process.myPid()) }, 2500)
      } catch (_: Exception) {
        restarting.set(false)
        Toast.makeText(context, R.string.backup_runtime_failed, Toast.LENGTH_LONG).show()
      }
    }
  }
}
