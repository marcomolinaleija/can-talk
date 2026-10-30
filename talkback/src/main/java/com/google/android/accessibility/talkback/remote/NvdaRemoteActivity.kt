/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.util.Size
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.remote.NvdaRemoteConnection.LockBehavior

/** Remote controls. The reader owns the connection independently of this screen. */
class NvdaRemoteActivity : AppCompatActivity() {
  private lateinit var form: LinearLayout
  private lateinit var content: FrameLayout
  private lateinit var formScroll: ScrollView
  private lateinit var status: TextView
  private lateinit var lastSpeech: TextView
  private lateinit var host: EditText
  private lateinit var port: EditText
  private lateinit var key: EditText
  private lateinit var text: EditText
  private lateinit var connectButton: Button
  private lateinit var remoteSpeechButton: Button
  private lateinit var lockBehaviorButton: Button
  private lateinit var tutorialButton: Button
  private lateinit var nvdaModifier: Button
  private lateinit var showKey: CheckBox
  private lateinit var saveComputer: CheckBox
  private lateinit var computers: NvdaRemoteComputersDialog
  private var selectedComputer: NvdaRemoteComputer? = null
  private var pendingComputer: NvdaRemoteComputer?
    get() = NvdaRemoteConnection.pendingComputer
    set(value) { NvdaRemoteConnection.pendingComputer = value }
  private val remoteButtons = mutableListOf<Button>()
  private val session get() = NvdaRemoteConnection.session
  private var resumed = false
  private var controlling = false
  private var modifier: Int
    get() = NvdaRemoteConnection.modifier
    set(value) { NvdaRemoteConnection.modifier = value }
  private var certificateDialog: AlertDialog? = null
  private var tutorialInvitation: AlertDialog? = null
  private var uiDialog: AlertDialog? = null
  private lateinit var bindings: NvdaRemoteBindings
  private var surface: NvdaRemoteInput? = null
  private var mode = NvdaRemoteMode.CONTROL
  private var captureMode: NvdaRemoteMode? = null
  private var learning = false
  private var remoteSpeechMuted: Boolean
    get() = NvdaRemoteConnection.speechMuted
    set(value) { NvdaRemoteConnection.speechMuted = value }
  private var lockBehavior: LockBehavior
    get() = NvdaRemoteConnection.lockBehavior
    set(value) { NvdaRemoteConnection.lockBehavior = value }
  private val screenLocked get() = NvdaRemoteConnection.screenLocked
  private var renderedRequest: NvdaRemoteConnection.Request? = null
  private var observedProfileRevision = -1L
  private var shownFailure: NvdaRemoteConnection.Failure? = null
  private val connectionListener = object : NvdaRemoteConnection.Listener {
    override fun onChanged(announce: Boolean) = renderConnection(announce)
    override fun onClipboard(text: String) = receiveClipboard(text)
    override fun onFailure(failure: NvdaRemoteConnection.Failure) = showCertificateFailure(failure)
    override fun onProfileResult(message: String) {
      status.text = message
      if (resumed) announce(message)
    }
  }
  private var pendingBraille = false
  private var ownsTouchRegion = false
  private val preparedModifiers = linkedSetOf<String>()
  private var heldDots = ""
  private var blockedHeldDots = ""
  private var heldModifiers = emptyList<String>()
  private val controlBack = object : OnBackPressedCallback(false) {
    override fun handleOnBackPressed() {
      val target = captureMode
      if (target != null) { captureMode = null; removeSurface(); showBindings(target) }
      else setControl(false)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    NvdaRemoteConnection.initialize(this)
    title = getString(R.string.nvda_remote_title)
    bindings = NvdaRemoteBindings(this)
    computers = NvdaRemoteComputersDialog(this, ::dialog, { resumed && !screenLocked },
      { message -> status.text = message; if (resumed) announce(message) },
      ::connectToComputer,
      { selectedComputer = null; saveComputer.isChecked = false; updateSaveComputerLabel() },
      { computer ->
        NvdaRemoteConnection.updateProfile(computer)
        if (selectedComputer?.id == computer.id) { selectedComputer = computer; updateSaveComputerLabel() }
        if (pendingComputer?.id == computer.id) { pendingComputer = null; saveComputer.isChecked = false }
      },
      { id ->
        NvdaRemoteConnection.deleteProfile(id)
        if (selectedComputer?.id == id) { selectedComputer = null; saveComputer.isChecked = false; updateSaveComputerLabel() }
        if (pendingComputer?.id == id) { pendingComputer = null; saveComputer.isChecked = false }
      })
    mode = runCatching { NvdaRemoteMode.valueOf(preferences(this).getString("input_mode", "CONTROL")!!) }
      .getOrDefault(NvdaRemoteMode.CONTROL)
    lockBehavior = runCatching {
      LockBehavior.valueOf(preferences(this).getString("lock_behavior", "DISCONNECT")!!)
    }.getOrDefault(LockBehavior.DISCONNECT)
    onBackPressedDispatcher.addCallback(this, controlBack)
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      val margin = (16 * resources.displayMetrics.density).toInt()
      setPadding(margin, margin, margin, margin)
    }
    form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(form)
    fun label(resource: Int) = TextView(this).apply {
      setText(resource); textSize = 16f; form.addView(this)
    }
    fun input(resource: Int, type: Int): EditText {
      val label = label(resource)
      return EditText(this).apply {
        id = View.generateViewId()
        label.labelFor = id
        inputType = type
        isSaveEnabled = false
        form.addView(this)
      }
    }
    fun button(resource: Int, remote: Boolean = false, action: () -> Unit) = Button(this).apply {
      setText(resource)
      form.addView(this)
      setOnClickListener { action() }
      if (remote) remoteButtons.add(this)
    }
    label(R.string.nvda_remote_description)
    host = input(R.string.nvda_remote_host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
    host.setText(preferences(this).getString("host", "nvdaremote.com"))
    port = input(R.string.nvda_remote_port, InputType.TYPE_CLASS_NUMBER)
    port.setText(preferences(this).getString("port", "6837"))
    key = input(R.string.nvda_remote_key, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    showKey = CheckBox(this).apply {
      setText(R.string.nvda_remote_show_key)
      isSaveEnabled = false
      setOnCheckedChangeListener { _, visible ->
        // Keep the password input type so revealing it cannot enable auto-capitalization.
        key.transformationMethod = if (visible) null else PasswordTransformationMethod.getInstance()
        key.setSelection(key.text.length)
      }
    }
    form.addView(showKey)
    saveComputer = CheckBox(this).apply {
      isSaveEnabled = false
      setText(R.string.nvda_computer_save_checkbox)
      setOnCheckedChangeListener { _, checked ->
        if (checked && session?.ready == true) finishInput { prepareConnection(saveOnly = true) }
      }
    }
    form.addView(saveComputer)
    status = label(R.string.nvda_remote_disconnected)
    button(R.string.nvda_computers_title) { finishInput { setControl(false, false); computers.show() } }
    connectButton = button(R.string.nvda_remote_connect) {
      if (session == null) finishInput { if (session == null) requestConnection() } else disconnect()
    }
    tutorialButton = button(R.string.nvda_tutorial_title) {
      finishInput { startActivity(Intent(this, NvdaRemoteTutorialActivity::class.java)) }
    }
    remoteSpeechButton = button(R.string.nvda_remote_mute_speech, true) { toggleRemoteSpeech() }
    lockBehaviorButton = button(R.string.nvda_remote_lock_disconnect) {
      val choices = LockBehavior.values()
      lockBehavior = choices[(lockBehavior.ordinal + 1) % choices.size]
      preferences(this).edit().putString("lock_behavior", lockBehavior.name).apply()
      updateLockBehaviorButton()
      announce(lockBehaviorButton.text.toString())
    }
    updateLockBehaviorButton()
    button(R.string.nvda_remote_gestures, true) { finishInput { setControl(true) } }
    button(R.string.nvda_remote_assign_gestures) { chooseBindingsMode() }
    button(R.string.nvda_remote_commands, true) {
      finishInput { setControl(true, false); if (controlling) showCommands() }
    }
    nvdaModifier = button(R.string.nvda_remote_modifier_insert) {
      modifier = if (modifier == 0x2D) 0x14 else 0x2D
      nvdaModifier.setText(if (modifier == 0x2D) R.string.nvda_remote_modifier_insert else R.string.nvda_remote_modifier_caps)
    }
    text = input(R.string.nvda_remote_text, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
    text.maxLines = 6
    button(R.string.nvda_remote_paste, true) {
      finishInput {
        val value = text.text.toString()
        if (value.isEmpty() || value.length > 8192) announce(R.string.nvda_remote_invalid_text)
        else if (session?.paste(value) == true) announce(R.string.nvda_remote_text_sent)
        else announce(R.string.nvda_remote_not_ready)
      }
    }
    lastSpeech = label(R.string.nvda_remote_last_speech)
    button(R.string.nvda_remote_help) {
      AlertDialog.Builder(this).setTitle(R.string.nvda_remote_title)
        .setMessage(getString(R.string.nvda_remote_help_text) + "\n\n" + getString(R.string.nvda_remote_hold_help) +
          "\n\n" + getString(R.string.nvda_remote_lock_help) + "\n\n" + getString(R.string.nvda_computers_help))
        .setPositiveButton(android.R.string.ok, null).show()
    }
    formScroll = ScrollView(this).apply { addView(root) }
    content = FrameLayout(this).apply { addView(formScroll, FrameLayout.LayoutParams(-1, -1)) }
    setContentView(content)
    renderConnection(false)
  }

  private fun finishInput(action: () -> Unit) {
    val editor = currentFocus as? EditText
    if (editor != null) TalkBackService.getInstance()?.commitPendingBrailleInput(packageName, editor.id)
    // Let queued InputConnection commits reach the editor before changing focus or reading it.
    connectButton.isEnabled = false
    connectButton.post {
      connectButton.isEnabled = true
      if (!resumed || isFinishing) return@post
      connectButton.isFocusableInTouchMode = true
      connectButton.requestFocus()
      (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(connectButton.windowToken, 0)
      action()
    }
  }

  private fun getTrustedCertificate(hostname: String?): String? {
    if (hostname.isNullOrBlank()) return null
    return preferences(this).getString("trusted_cert_$hostname", null)
  }

  private fun saveTrustedCertificate(hostname: String?, fingerprint: String) {
    if (hostname.isNullOrBlank()) return
    preferences(this).edit().putString("trusted_cert_$hostname", fingerprint).apply()
  }

  private fun connect(trustedFingerprint: String? = null) {
    val endpoint = NvdaRemoteEndpoint.parse(host.text.toString(), port.text.toString())
    val password = key.text.toString() // A channel key must be kept exactly as entered.
    if (endpoint == null || password.isBlank() || password.length > 1024) {
      pendingComputer = null
      announce(R.string.nvda_remote_invalid_connection)
      return
    }
    val hostname = endpoint.host
    val number = endpoint.port
    if (TalkBackService.getInstance() == null) { announce(R.string.nvda_remote_reader_required); return }
    host.setText(hostname)
    port.setText(number.toString())
    showKey.isChecked = false
    preferences(this).edit().putString("host", hostname).putString("port", number.toString()).apply()
    val profile = selectedComputer?.takeIf {
      it.host.equals(hostname, true) && it.port == number &&
        (it.password == null || it.password == password)
    }
    NvdaRemoteConnection.connect(NvdaRemoteConnection.Request(hostname, number, password,
      trustedFingerprint ?: getTrustedCertificate(hostname)), profile)
  }

  private fun renderConnection(speak: Boolean) {
    val connection = NvdaRemoteConnection
    val request = connection.request
    if (request != null && request !== renderedRequest) {
      renderedRequest = request
      host.setText(request.host); port.setText(request.port.toString()); key.setText(request.password)
      showKey.isChecked = false
      selectedComputer = connection.profile
      saveComputer.isChecked = pendingComputer != null && session?.ready != true
      updateSaveComputerLabel()
    }
    if (observedProfileRevision != connection.profileRevision) {
      observedProfileRevision = connection.profileRevision
      if (session != null) {
        selectedComputer = connection.profile
        saveComputer.isChecked = pendingComputer != null && session?.ready != true
        updateSaveComputerLabel()
      }
    }
    if (session?.ready != true || screenLocked) {
      setControl(false, false)
      captureMode = null
      removeSurface()
    }
    nvdaModifier.setText(if (modifier == 0x2D) R.string.nvda_remote_modifier_insert else R.string.nvda_remote_modifier_caps)
    status.setText(connection.status)
    lastSpeech.text = connection.lastSpeech?.let { getString(R.string.nvda_remote_output, it.toString()) }
      ?: getString(R.string.nvda_remote_last_speech)
    if (session != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    updateButtons()
    if (speak && resumed) announce(connection.status)
  }

  private fun showCertificateFailure(failure: NvdaRemoteConnection.Failure) {
    val request = NvdaRemoteConnection.request ?: return
    val fingerprint = failure.fingerprint ?: return
    if (!resumed || shownFailure === failure || failure.reason != NvdaRemoteSession.Failure.CERTIFICATE ||
      request.fingerprint == fingerprint) return
    shownFailure = failure
    fun retry() {
      host.setText(request.host); port.setText(request.port.toString()); key.setText(request.password)
      pendingComputer = failure.retryComputer
      connect(fingerprint)
    }
    certificateDialog = AlertDialog.Builder(this)
      .setTitle(R.string.nvda_remote_certificate_error)
      .setMessage(getString(R.string.nvda_remote_certificate_message, request.host, fingerprint))
      .setPositiveButton(R.string.nvda_remote_trust_always) { _, _ ->
        saveTrustedCertificate(request.host, fingerprint)
        retry()
      }
      .setNeutralButton(R.string.nvda_remote_trust_once) { _, _ -> retry() }
      .setNegativeButton(android.R.string.cancel, null)
      .create().also { dialog ->
        dialog.setOnDismissListener { if (certificateDialog === dialog) certificateDialog = null }
        dialog.show()
      }
  }

  private fun requestConnection() {
    if (!resumed || session != null || tutorialInvitation?.isShowing == true) return
    val progress = NvdaRemoteTutorial.Progress(this)
    if (!progress.shouldOffer()) { prepareConnection(); return }
    val invitation = AlertDialog.Builder(this)
      .setTitle(R.string.nvda_tutorial_invitation_title)
      .setMessage(R.string.nvda_tutorial_invitation_body)
      .setPositiveButton(if (progress.hasStarted()) R.string.nvda_tutorial_resume else R.string.nvda_tutorial_start) { _, _ ->
        startActivity(Intent(this, NvdaRemoteTutorialActivity::class.java).putExtra("resume", true))
      }
      .setNegativeButton(R.string.nvda_tutorial_connect_without) { _, _ -> prepareConnection() }
      .setNeutralButton(R.string.nvda_tutorial_hide_invitation) { _, _ ->
        progress.hideInvitation()
        prepareConnection()
      }.create()
    tutorialInvitation = invitation
    invitation.setOnDismissListener { if (tutorialInvitation === invitation) tutorialInvitation = null }
    invitation.show()
  }

  private fun updateSaveComputerLabel() {
    saveComputer.text = selectedComputer?.let { getString(R.string.nvda_computer_update_checkbox, it.name) }
      ?: getString(R.string.nvda_computer_save_checkbox)
  }

  private fun prepareConnection(saveOnly: Boolean = false) {
    if (!resumed || (saveOnly && session?.ready != true) || (!saveOnly && session != null)) return
    val endpoint = NvdaRemoteEndpoint.parse(host.text.toString(), port.text.toString())
    val password = key.text.toString()
    if (endpoint == null || password.isBlank() || password.length > 1024) {
      announce(R.string.nvda_remote_invalid_connection); return
    }
    if (!saveComputer.isChecked) {
      if (!saveOnly) { pendingComputer = null; connect() }
      return
    }
    val connection = NvdaRemoteComputer("", "", endpoint.host, endpoint.port, modifier, password)
    val currentSession = session
    val selection = selectedComputer
    computers.prepareSave(connection, selection, !saveOnly) { candidate ->
      val currentEndpoint = NvdaRemoteEndpoint.parse(host.text.toString(), port.text.toString())
      if (currentEndpoint != endpoint || key.text.toString() != password || modifier != connection.nvdaModifier ||
        selectedComputer?.id != selection?.id) {
        announce(R.string.nvda_computer_connection_changed); return@prepareSave
      }
      if (saveOnly) {
        if (session !== currentSession || session?.ready != true) return@prepareSave
        NvdaRemoteConnection.saveComputer(candidate)
      } else if (session == null) {
        pendingComputer = candidate
        connect()
      }
    }
  }

  private fun connectToComputer(computer: NvdaRemoteComputer, password: String) {
    uiDialog?.setOnDismissListener(null); uiDialog?.dismiss(); uiDialog = null
    fun applyComputer() {
      selectedComputer = computer
      saveComputer.isChecked = false
      updateSaveComputerLabel()
      host.setText(computer.host); port.setText(computer.port.toString()); key.setText(password)
      showKey.isChecked = false
      modifier = computer.nvdaModifier
      nvdaModifier.setText(if (modifier == 0x2D) R.string.nvda_remote_modifier_insert else R.string.nvda_remote_modifier_caps)
      requestConnection()
    }
    if (session == null) applyComputer()
    else dialog(AlertDialog.Builder(this).setTitle(R.string.nvda_computer_switch)
      .setMessage(getString(R.string.nvda_computer_switch_message, computer.name))
      .setNegativeButton(android.R.string.cancel, null)
      .setPositiveButton(R.string.nvda_remote_connect) { _, _ -> disconnect(false); applyComputer() })
  }

  private fun receiveClipboard(value: String) {
    if (!resumed || screenLocked) return
    setControl(false, false)
    dialog(AlertDialog.Builder(this).setTitle(R.string.nvda_remote_clipboard)
      .setMessage(R.string.nvda_remote_clipboard_message)
      .setNegativeButton(android.R.string.cancel, null)
      .setPositiveButton(R.string.nvda_remote_copy) { _, _ ->
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NVDA", value))
      })
  }

  private fun remoteSpeechActionLabel(): Int = if (remoteSpeechMuted)
    R.string.nvda_remote_unmute_speech else R.string.nvda_remote_mute_speech

  private fun updateLockBehaviorButton() {
    lockBehaviorButton.text = getString(R.string.nvda_remote_lock_button, getString(lockBehavior.label))
  }

  private fun refreshScreenLock() = NvdaRemoteConnection.refreshScreenLock()

  private fun toggleRemoteSpeech() {
    if (session?.ready != true) { announce(R.string.nvda_remote_not_ready); return }
    remoteSpeechMuted = !remoteSpeechMuted
    // Discard current and queued speech; unmuting only plays newly received output.
    if (remoteSpeechMuted) NvdaRemoteSpeech.cancel()
    updateButtons()
    announce(if (remoteSpeechMuted) R.string.nvda_remote_speech_muted else R.string.nvda_remote_speech_unmuted)
  }

  private fun setControl(enabled: Boolean, speak: Boolean = true) {
    if (enabled && session?.ready != true) { announce(R.string.nvda_remote_not_ready); return }
    if (enabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      announce(R.string.nvda_remote_surface_requires_android); return
    }
    if (controlling == enabled) return
    if (!enabled && resumed && speak) finishRemoteBraille()
    releaseHeldModifiers()
    controlling = enabled
    preparedModifiers.clear()
    learning = false
    captureMode = null
    controlBack.isEnabled = enabled
    if (enabled) showSurface() else removeSurface()
    if (speak) {
      if (enabled && surface != null) announce(getString(mode.label) + ". " + getString(R.string.nvda_remote_surface_hint))
      else if (!enabled) announce(R.string.nvda_remote_gestures_stopped)
    }
  }

  private fun setImmersiveMode(enable: Boolean) {
    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
    if (enable) {
      WindowCompat.setDecorFitsSystemWindows(window, false)
      insetsController.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      insetsController.hide(WindowInsetsCompat.Type.systemBars())
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.attributes.layoutInDisplayCutoutMode =
          WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      }
      @Suppress("DEPRECATION")
      window.decorView.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        or View.SYSTEM_UI_FLAG_FULLSCREEN
      )
    } else {
      WindowCompat.setDecorFitsSystemWindows(window, true)
      insetsController.show(WindowInsetsCompat.Type.systemBars())
      @Suppress("DEPRECATION")
      window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }
  }

  private fun showSurface() {
    if (surface != null || !resumed || uiDialog?.isShowing == true) return
    if (captureMode == null && (!controlling || session?.ready != true)) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || TalkBackService.getInstance() == null) {
      controlling = false; captureMode = null; controlBack.isEnabled = false
      announce(R.string.nvda_remote_surface_requires_android)
      return
    }
    val created = NvdaRemoteInputFactory.create(this, Size(content.width.coerceAtLeast(1),
      content.height.coerceAtLeast(1)), object : NvdaRemoteInput.Listener {
      override fun onGesture(gesture: NvdaRemoteGesture) {
        if (!resumed || surface == null || !hasWindowFocus()) return
        // Record the processing stage, never the dots, key, channel or remote text.
        Log.i("CanTalkNvdaRemote", "Input received; mode=${mode.name}; capture=${captureMode != null}; learning=$learning")
        val target = captureMode
        if (target != null) {
          captureMode = null
          removeSurface()
          content.post { if (resumed) chooseAction(target, gesture) }
        } else {
          performAction(bindings.resolve(mode, gesture), gesture)
        }
      }
      override fun onAnnouncement(text: String) { if (resumed && surface != null) announce(text) }
      override fun onHold(dots: String): Boolean {
        if (!resumed || surface == null || !hasWindowFocus()) return false
        val target = captureMode
        if (target != null) {
          captureMode = null
          removeSurface()
          content.post { if (resumed) chooseAction(target, NvdaRemoteGesture(held = dots, holdOnly = true)) }
          return true
        }
        return holdModifiers(dots)
      }
      override fun onHeldDotsChanged(dots: String) { updateHeldDots(dots) }
    })
    if (created == null) {
      controlling = false; captureMode = null; controlBack.isEnabled = false
      announce(R.string.nvda_remote_surface_requires_android)
      return
    }
    setImmersiveMode(true)
    surface = created
    controlBack.isEnabled = true
    formScroll.visibility = View.GONE
    content.addView(created, FrameLayout.LayoutParams(-1, -1))
    created.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTouchRegion() }
    created.post { updateTouchRegion() }
    updateSurfaceDescription()
  }

  private fun updateTouchRegion() {
    val current = surface ?: return
    if (!resumed || !hasWindowFocus()) return
    val bounds = Rect()
    if (current.getGlobalVisibleRect(bounds) && !bounds.isEmpty) {
      TalkBackService.getInstance()?.setModalTouchRegion(Region(bounds))
      ownsTouchRegion = true
    }
  }

  private fun removeSurface() {
    setImmersiveMode(false)
    releaseHeldModifiers()
    blockedHeldDots = ""
    if (ownsTouchRegion) {
      ownsTouchRegion = false
      TalkBackService.getInstance()?.setModalTouchRegion(null)
    }
    val previous = surface
    surface = null
    previous?.close()
    if (previous != null) content.removeView(previous)
    if (::formScroll.isInitialized) formScroll.visibility = View.VISIBLE
    controlBack.isEnabled = controlling || captureMode != null
  }

  override fun onWindowFocusChanged(focused: Boolean) {
    super.onWindowFocusChanged(focused)
    if (focused) {
      if (surface != null) setImmersiveMode(true)
      updateTouchRegion()
    } else {
      releaseHeldModifiers()
      if (ownsTouchRegion) {
        ownsTouchRegion = false
        TalkBackService.getInstance()?.setModalTouchRegion(null)
      }
    }
  }

  private fun updateSurfaceDescription() {
    val description = if (captureMode != null) getString(R.string.nvda_remote_capture_hint,
      getString(captureMode!!.label)) else getString(mode.label) + "\n" +
      (if (learning) getString(R.string.nvda_remote_learning_on) else getString(R.string.nvda_remote_surface_hint)) +
      "\n" + modifierDescription()
    surface?.setDescription(description)
  }

  private fun modifierDescription(): String {
    val prepared = if (preparedModifiers.isEmpty()) "" else getString(R.string.nvda_remote_modifiers_ready,
      preparedModifiers.joinToString(" + ") { NvdaRemoteBindings.modifierLabel(it) })
    val held = if (heldModifiers.isEmpty()) "" else getString(R.string.nvda_remote_modifiers_held,
      heldModifiers.joinToString(" + ") { NvdaRemoteBindings.modifierLabel(it) })
    return listOf(prepared, held).filter { it.isNotEmpty() }.joinToString(". ")
      .ifEmpty { getString(R.string.nvda_remote_modifiers_empty) }
  }

  private fun modifierKeys(names: Collection<String>) = names.map { when (it) {
    "ctrl" -> 0x11; "alt" -> 0x12; "shift" -> 0x10; "nvda" -> modifier; else -> 0x5B
  } }.distinct().toIntArray()

  private fun holdModifiers(dots: String): Boolean {
    if (!controlling || session?.ready != true || dots.isEmpty() || dots.any { it in blockedHeldDots }) return false
    val names = bindings.heldModifiers(mode, dots)
    val navigation = bindings.navigationHeldDots(mode, dots)
    // A prolonged letter chord (e.g. F=124) must still type on release.
    if (navigation.any { it != '1' } || (names.isEmpty() && navigation != "1")) return false
    if (heldDots == dots && heldModifiers == names) return names.isNotEmpty()
    if (!finishRemoteBraille()) return false
    if (!learning && session?.setHeldModifiers(modifierKeys(names)) != true) {
      releaseHeldModifiers()
      blockedHeldDots = (blockedHeldDots + dots).toCharArray().distinct().sorted().joinToString("")
      announce(R.string.nvda_remote_not_ready)
      return true
    }
    val changed = heldModifiers != names
    heldDots = dots
    heldModifiers = names
    preparedModifiers.removeAll(names.toSet())
    updateSurfaceDescription()
    vibrate(30)
    if (changed && names.isNotEmpty()) announce(getString(R.string.nvda_remote_modifiers_held,
      names.joinToString(" + ") { NvdaRemoteBindings.modifierLabel(it) }))
    return names.isNotEmpty()
  }

  private fun updateHeldDots(dots: String) {
    blockedHeldDots = blockedHeldDots.filter { it in dots }
    val remaining = heldDots.filter { it in dots }
    if (remaining == heldDots) return
    // Releases never activate a new modifier; that requires its own recognized hold.
    val names = bindings.heldModifiers(mode, remaining).filter { it in heldModifiers }
    if (!learning && heldModifiers != names && session?.setHeldModifiers(modifierKeys(names)) != true) {
      releaseHeldModifiers()
      return
    }
    heldDots = remaining
    val changed = heldModifiers != names
    heldModifiers = names
    updateSurfaceDescription()
    if (changed) vibrate(8)
  }

  private fun releaseHeldModifiers() {
    blockedHeldDots = (blockedHeldDots + heldDots).toCharArray().distinct().sorted().joinToString("")
    heldDots = ""
    if (heldModifiers.isNotEmpty()) session?.setHeldModifiers(intArrayOf())
    heldModifiers = emptyList()
    if (surface != null) updateSurfaceDescription()
  }

  private fun vibrate(milliseconds: Long) {
    getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
  }

  private fun finishRemoteBraille(): Boolean {
    if (!pendingBraille || learning) return true
    if (session?.brailleCommand("braille_translate") != true) {
      announce(R.string.nvda_remote_not_ready); return false
    }
    pendingBraille = false
    return true
  }

  private fun performAction(action: String, gesture: NvdaRemoteGesture? = null) {
    if (!resumed || !controlling || session?.ready != true) return
    Log.i("CanTalkNvdaRemote", "Resolving input; prepared modifiers=${preparedModifiers.size}")
    val resolved = if (preparedModifiers.isNotEmpty() || heldModifiers.isNotEmpty()) when (action) {
      "braille" -> {
        val letter = gesture?.dots?.let { NvdaRemoteBindings.letterKey(it) }
        if (letter == null) { announce(R.string.nvda_remote_invalid_gesture); return }
        letter
      }
      "braille_space" -> NvdaRemoteBindings.key(0x20)
      "braille_enter" -> NvdaRemoteBindings.key(0x0D)
      "braille_erase" -> NvdaRemoteBindings.key(0x08)
      else -> action
    } else action
    val code = NvdaRemoteBindings.keyCode(resolved)
    if (code != null) {
      val modifiers = (preparedModifiers + heldModifiers + NvdaRemoteBindings.modifiers(resolved)).distinct()
      preparedModifiers.clear()
      updateSurfaceDescription()
      if (learning) {
        announce(NvdaRemoteBindings.actionLabel(this, NvdaRemoteBindings.key(code, modifiers)))
        return
      }
      val keys = modifierKeys(modifiers)
      NvdaRemoteSpeech.cancel()
      val accepted = if (pendingBraille) session?.pressAfterBraille(code, *keys) else session?.press(code, *keys)
      if (accepted == true) {
        pendingBraille = false
        vibrate(12)
        // Let NVDA announce the selected window or text instead of speaking every held command.
        if (modifiers.isNotEmpty() && heldModifiers.isEmpty()) announce(getString(R.string.nvda_remote_sending_shortcut,
          NvdaRemoteBindings.actionLabel(this, NvdaRemoteBindings.key(code, modifiers))))
        if (code == 0x1B && "alt" in heldModifiers) releaseHeldModifiers()
      } else announce(R.string.nvda_remote_not_ready)
      return
    }
    when (action) {
      "emoji" -> {
        if (learning) {
          announce((gesture?.label(this)?.plus(". ") ?: "") + NvdaRemoteBindings.actionLabel(this, action))
          return
        }
        preparedModifiers.clear()
        updateSurfaceDescription()
        val accepted = if (pendingBraille) session?.pressAfterBraille(0xBE, 0x5B) else session?.press(0xBE, 0x5B)
        if (accepted == true) {
          pendingBraille = false
          vibrate(12)
          announce(R.string.nvda_remote_emoji)
        } else announce(R.string.nvda_remote_not_ready)
      }
      "toggle_remote_speech" -> {
        if (learning) announce(remoteSpeechActionLabel()) else toggleRemoteSpeech()
      }
      "mode" -> {
        releaseHeldModifiers()
        if (!finishRemoteBraille()) return
        mode = if (mode == NvdaRemoteMode.CONTROL) NvdaRemoteMode.KEYBOARD else NvdaRemoteMode.CONTROL
        preferences(this).edit().putString("input_mode", mode.name).apply()
        preparedModifiers.clear()
        updateSurfaceDescription()
        vibrate(if (mode == NvdaRemoteMode.CONTROL) 40 else 20)
        announce(getString(mode.label))
      }
      "exit" -> setControl(false)
      "menu" -> { finishRemoteBraille(); showCommands() }
      "learn" -> {
        releaseHeldModifiers()
        if (!finishRemoteBraille()) return
        learning = !learning
        preparedModifiers.clear()
        updateSurfaceDescription()
        announce(if (learning) R.string.nvda_remote_learning_on else R.string.nvda_remote_learning_off)
      }
      "clear" -> {
        releaseHeldModifiers()
        preparedModifiers.clear(); updateSurfaceDescription(); announce(modifierDescription())
      }
      "ctrl", "alt", "shift", "nvda", "windows" -> {
        if (!finishRemoteBraille()) return
        if (!preparedModifiers.add(action)) preparedModifiers.remove(action)
        updateSurfaceDescription()
        vibrate(18)
        announce(modifierDescription())
      }
      "braille", "braille_space", "braille_erase", "braille_enter", "braille_translate" -> {
        if (learning) {
          announce((gesture?.label(this)?.plus(". ") ?: "") + NvdaRemoteBindings.actionLabel(this, action))
          return
        }
        preparedModifiers.clear()
        updateSurfaceDescription()
        val accepted = when (action) {
          "braille" -> {
            val dots = gesture?.dots
            if (dots == null) { announce(R.string.nvda_remote_invalid_gesture); return }
            val bits = dots.fold(0) { value, dot -> value or (1 shl (dot - '1')) }
            session?.braille(bits)
          }
          "braille_space" -> session?.braille(0, true)
          "braille_erase" -> session?.brailleCommand("braille_eraseLastCell")
          "braille_enter" -> session?.brailleCommand("braille_enter")
          else -> session?.brailleCommand("braille_translate")
        }
        if (accepted == true) {
          if (action == "braille") pendingBraille = true
          else if (action != "braille_erase") pendingBraille = false
          vibrate(10)
        } else announce(R.string.nvda_remote_not_ready)
      }
      else -> announce(if (learning && gesture != null)
        gesture.label(this) + ". " + getString(R.string.nvda_remote_no_action) else getString(R.string.nvda_remote_no_action))
    }
  }

  /** All dialogs restore normal touch exploration; no gesture on a dialog reaches the PC. */
  private fun dialog(builder: AlertDialog.Builder): AlertDialog {
    uiDialog?.setOnDismissListener(null)
    uiDialog?.dismiss()
    removeSurface()
    val created = builder.create()
    uiDialog = created
    created.setOnDismissListener {
      if (uiDialog === created) {
        uiDialog = null
        content.post { if (resumed && captureMode == null) showSurface() }
      }
    }
    created.show()
    return created
  }

  private fun showCommands() {
    val actions = listOf("mode", "toggle_remote_speech", "learn", "clear", "settings", "exit") +
      NvdaRemoteBindings.modifierActions + listOf(
      NvdaRemoteBindings.key(0x54, listOf("nvda")), NvdaRemoteBindings.key(0x09, listOf("nvda")),
      NvdaRemoteBindings.key(0x28, listOf("nvda")), NvdaRemoteBindings.key(0x09, listOf("alt"))) +
      NvdaRemoteBindings.keys.filter { it < 0x41 || it in 0x70..0x7B || it == 0x5B }.map { NvdaRemoteBindings.key(it) }
    val labels = actions.map { if (it == "settings") getString(R.string.nvda_remote_assign_gestures)
      else if (it == "toggle_remote_speech") getString(remoteSpeechActionLabel())
      else NvdaRemoteBindings.actionLabel(this, it) }.toTypedArray()
    dialog(AlertDialog.Builder(this).setTitle(getString(R.string.nvda_remote_commands) + ": " + getString(mode.label))
      .setItems(labels) { _, index ->
        if (actions[index] == "settings") chooseBindingsMode() else performAction(actions[index])
      }.setNegativeButton(android.R.string.cancel, null))
  }

  private fun chooseBindingsMode() {
    preparedModifiers.clear()
    updateSurfaceDescription()
    dialog(AlertDialog.Builder(this).setTitle(R.string.nvda_remote_assign_gestures)
      .setItems(NvdaRemoteMode.values().map { getString(it.label) }.toTypedArray()) { _, index ->
        showBindings(NvdaRemoteMode.values()[index])
      }.setNegativeButton(android.R.string.cancel, null))
  }

  private fun showBindings(target: NvdaRemoteMode) {
    val gestures = bindings.gestures(target)
    val labels = listOf(getString(R.string.nvda_remote_capture), getString(R.string.nvda_remote_restore_bindings)) +
      gestures.map { it.label(this) + ": " + NvdaRemoteBindings.actionLabel(this, bindings.resolve(target, it)) }
    dialog(AlertDialog.Builder(this).setTitle(getString(R.string.nvda_remote_assign_gestures) + ": " + getString(target.label))
      .setItems(labels.toTypedArray()) { _, index ->
        when (index) {
          0 -> {
            uiDialog?.setOnDismissListener(null); uiDialog?.dismiss(); uiDialog = null
            finishInput {
              captureMode = target
              showSurface()
              if (surface != null) announce(getString(R.string.nvda_remote_capture_hint, getString(target.label)))
            }
          }
          1 -> dialog(AlertDialog.Builder(this).setTitle(R.string.nvda_remote_restore_bindings)
            .setMessage(getString(R.string.nvda_remote_restore_message, getString(target.label)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> bindings.restore(target); showBindings(target) })
          else -> chooseAction(target, gestures[index - 2])
        }
      }.setNegativeButton(android.R.string.cancel, null))
  }

  private fun chooseAction(target: NvdaRemoteMode, gesture: NvdaRemoteGesture) {
    val actions = if (gesture.holdOnly) listOf("none") + NvdaRemoteBindings.modifierActions.toList()
      else NvdaRemoteBindings.localActions.filter { it != "braille" || gesture.dots != null } + "shortcut"
    val labels = actions.map { if (it == "shortcut") getString(R.string.nvda_remote_custom_shortcut)
      else NvdaRemoteBindings.actionLabel(this, it) }
    dialog(AlertDialog.Builder(this).setTitle(gesture.label(this))
      .setItems(labels.toTypedArray()) { _, index ->
        if (actions[index] == "shortcut") chooseShortcut(target, gesture)
        else saveBinding(target, gesture, actions[index])
      }.setNegativeButton(android.R.string.cancel, null))
  }

  private fun chooseShortcut(target: NvdaRemoteMode, gesture: NvdaRemoteGesture) {
    val keys = NvdaRemoteBindings.keys
    dialog(AlertDialog.Builder(this).setTitle(R.string.nvda_remote_choose_key)
      .setItems(keys.map { NvdaRemoteBindings.actionLabel(this, NvdaRemoteBindings.key(it)) }.toTypedArray()) { _, index ->
        val modifiers = listOf("ctrl", "alt", "shift", "nvda", "windows")
        val selected = BooleanArray(modifiers.size)
        dialog(AlertDialog.Builder(this).setTitle(R.string.nvda_remote_choose_modifiers)
          .setMultiChoiceItems(modifiers.map { NvdaRemoteBindings.modifierLabel(it) }.toTypedArray(), selected) { _, position, checked ->
            selected[position] = checked
          }.setNegativeButton(android.R.string.cancel, null)
          .setPositiveButton(R.string.nvda_remote_save_binding) { _, _ ->
            saveBinding(target, gesture, NvdaRemoteBindings.key(keys[index], modifiers.filterIndexed { position, _ -> selected[position] }))
          })
      }.setNegativeButton(android.R.string.cancel, null))
  }

  private fun saveBinding(target: NvdaRemoteMode, gesture: NvdaRemoteGesture, action: String) {
    bindings.assign(target, gesture, action)
    announce(getString(R.string.nvda_remote_binding_saved, getString(target.label), gesture.label(this),
      NvdaRemoteBindings.actionLabel(this, action)))
  }

  private fun updateButtons() {
    val connecting = session != null
    connectButton.setText(if (connecting) R.string.nvda_remote_disconnect else R.string.nvda_remote_connect)
    remoteSpeechButton.setText(remoteSpeechActionLabel())
    host.isEnabled = !connecting; port.isEnabled = !connecting; key.isEnabled = !connecting
    showKey.isEnabled = !connecting
    saveComputer.isEnabled = !connecting || session?.ready == true
    tutorialButton.isEnabled = !connecting
    remoteButtons.forEach { it.isEnabled = session?.ready == true }
  }

  private fun disconnect(speak: Boolean = true) {
    setControl(false, false)
    captureMode = null
    pendingBraille = false
    removeSurface()
    NvdaRemoteConnection.disconnect(speak)
  }

  private fun announce(resource: Int) {
    announce(getString(resource))
  }
  private fun announce(message: String) {
    if (TalkBackService.getInstance() != null) NvdaRemoteSpeech.speak(message, true)
    else status.announceForAccessibility(message)
  }

  override fun onStart() {
    super.onStart()
    NvdaRemoteConnection.addListener(connectionListener)
    renderConnection(false)
  }
  override fun onResume() {
    super.onResume()
    resumed = true
    active = this
    refreshScreenLock()
    NvdaRemoteConnection.failure?.let(::showCertificateFailure)
  }
  override fun onPause() {
    resumed = false
    if (active === this) active = null
    refreshScreenLock()
    setControl(false, false)
    captureMode = null
    removeSurface()
    if (certificateDialog?.isShowing == true) shownFailure = null
    certificateDialog?.dismiss()
    certificateDialog = null
    tutorialInvitation?.dismiss()
    tutorialInvitation = null
    uiDialog?.dismiss()
    uiDialog = null
    super.onPause()
  }
  override fun onStop() {
    NvdaRemoteConnection.removeListener(connectionListener)
    super.onStop()
  }
  override fun onDestroy() {
    NvdaRemoteConnection.removeListener(connectionListener)
    removeSurface()
    super.onDestroy()
  }

  companion object {
    const val PREFS_NAME = "com.google.android.accessibility.talkback.remote.NvdaRemoteActivity"
    private const val LEGACY_PREFS_NAME = "remote.NvdaRemoteActivity"

    fun preferences(context: Context): android.content.SharedPreferences {
      val app = context.applicationContext
      val current = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      if (current.all.isEmpty()) {
        val legacy = app.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        if (legacy.all.isNotEmpty()) {
          val edit = current.edit()
          legacy.all.forEach { (k, v) ->
            if (v is String) edit.putString(k, v)
          }
          edit.apply()
        }
      }
      return current
    }

    private var active: NvdaRemoteActivity? = null // Accessed only on the Android main thread.
    @JvmStatic fun handleGesture(id: Int): Boolean = active?.let {
      it.resumed && it.surface != null && it.hasWindowFocus()
    } == true
  }
}
