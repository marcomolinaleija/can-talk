/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.backup

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.util.concurrent.Executors

/** Survives rotation/SAF without saving passwords or decoded private data in an Android Bundle. */
internal class BackupUiState : ViewModel() {
  sealed class Result {
    data class Inventory(val counts: Map<BackupCategory, Int>) : Result()
    data class ExportReady(val json: String) : Result()
    data class ImportReady(val document: BackupDocument) : Result()
    data class Encrypted(val json: String) : Result()
    data class Restored(val count: Int) : Result()
    data object Saved : Result()
    data class Failed(val failure: BackupFailure) : Result()
  }
  val busy = MutableLiveData(false)
  val result = MutableLiveData<Result?>()
  var pendingExport: String? = null
  var encryptedImport: String? = null
  @Volatile private var closed = false

  fun run(work: () -> Result) {
    if (busy.value == true) return
    if (BackupRuntime.isRestarting) {
      result.value = Result.Failed(BackupFailure.INTERRUPTED)
      return
    }
    busy.value = true
    executor.execute {
      val outcome = try {
        if (BackupRuntime.isRestarting) throw BackupException(BackupFailure.INTERRUPTED)
        work()
      }
      catch (e: BackupException) { Result.Failed(e.failure) }
      catch (_: OutOfMemoryError) { Result.Failed(BackupFailure.TOO_LARGE) }
      catch (_: Exception) { Result.Failed(BackupFailure.IO) }
      if (!closed) {
        busy.postValue(false)
        result.postValue(outcome)
      }
    }
  }

  override fun onCleared() {
    closed = true
    pendingExport = null
    encryptedImport = null
    result.value = null
    // Confirmed operations must finish their commit/rollback even if the user closes this screen.
  }

  companion object { private val executor = Executors.newSingleThreadExecutor() }
}
