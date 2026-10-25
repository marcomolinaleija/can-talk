/* Copyright 2026 CAN Talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import org.json.JSONObject
import java.net.URI

/** Foreground-only experimental client. Leaving this screen disconnects the session. */
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
  private lateinit var nvdaModifier: Button
  private lateinit var showKey: CheckBox
  private val remoteButtons = mutableListOf<Button>()
  private var session: NvdaRemoteSession? = null
  private var resumed = false
  private var controlling = false
  private var lastReady: Boolean? = null
  private var modifier = 0x2D // Insert; can be changed to Caps Lock for laptop layouts.
  private var certificateDialog: AlertDialog? = null
  private var uiDialog: AlertDialog? = null
  private lateinit var bindings: NvdaRemoteBindings
  private var surface: NvdaRemoteInput? = null
  private var mode = NvdaRemoteMode.CONTROL
  private var captureMode: NvdaRemoteMode? = null
  private var learning = false
  private var remoteSpeechMuted = false
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
    title = getString(R.string.nvda_remote_title)
    bindings = NvdaRemoteBindings(this)
    mode = runCatching { NvdaRemoteMode.valueOf(getPreferences(MODE_PRIVATE).getString("input_mode", "CONTROL")!!) }
      .getOrDefault(NvdaRemoteMode.CONTROL)
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
    host.setText(getPreferences(Context.MODE_PRIVATE).getString("host", "nvdaremote.com"))
    port = input(R.string.nvda_remote_port, InputType.TYPE_CLASS_NUMBER)
    port.setText(getPreferences(Context.MODE_PRIVATE).getString("port", "6837"))
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
    status = label(R.string.nvda_remote_disconnected)
    connectButton = button(R.string.nvda_remote_connect) {
      if (session == null) finishInput { if (session == null) connect() } else disconnect()
    }
    remoteSpeechButton = button(R.string.nvda_remote_mute_speech, true) { toggleRemoteSpeech() }
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
        .setMessage(getString(R.string.nvda_remote_help_text) + "\n\n" + getString(R.string.nvda_remote_hold_help))
        .setPositiveButton(android.R.string.ok, null).show()
    }
    formScroll = ScrollView(this).apply { addView(root) }
    content = FrameLayout(this).apply { addView(formScroll, FrameLayout.LayoutParams(-1, -1)) }
    setContentView(content)
    updateButtons()
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

  private fun connect(trustedFingerprint: String? = null) {
    val address = host.text.toString().trim()
    val endpoint = try {
      // Accept host:port and bracketed IPv6, as in NVDA's connection dialog.
      val authority = if (!address.startsWith("[") && address.count { it == ':' } > 1) "[$address]" else address
      URI("nvda://$authority").takeIf {
        it.host != null && it.rawUserInfo == null && it.rawQuery == null && it.rawFragment == null && it.rawPath.isNullOrEmpty()
      }
    } catch (_: Exception) { null }
    val hostname = endpoint?.host?.removeSurrounding("[", "]")
    val number = if (endpoint != null && endpoint.port != -1) endpoint.port else port.text.toString().trim().toIntOrNull()
    val password = key.text.toString() // A channel key must be kept exactly as entered.
    if (hostname.isNullOrBlank() ||
        number == null || number !in 1..65535 || password.isBlank() || password.length > 1024) {
      announce(R.string.nvda_remote_invalid_connection)
      return
    }
    if (TalkBackService.getInstance() == null) { announce(R.string.nvda_remote_reader_required); return }
    host.setText(hostname)
    port.setText(number.toString())
    showKey.isChecked = false
    getPreferences(Context.MODE_PRIVATE).edit().putString("host", hostname).putString("port", number.toString()).apply()
    lastReady = null
    lastSpeech.setText(R.string.nvda_remote_last_speech)
    lateinit var created: NvdaRemoteSession
    fun onUi(action: () -> Unit) { runOnUiThread { if (session === created && !isFinishing) action() } }
    created = NvdaRemoteSession(hostname, number, password, trustedFingerprint, object : NvdaRemoteSession.Listener {
      override fun onState(ready: Boolean) = onUi {
        if (!ready) setControl(false, false)
        val message = when {
          ready -> R.string.nvda_remote_ready
          password != password.trim() -> R.string.nvda_remote_waiting_spaces
          else -> R.string.nvda_remote_waiting
        }
        status.setText(message)
        if (lastReady != ready && resumed) announce(message)
        lastReady = ready
        updateButtons()
      }
      override fun onMessage(message: JSONObject) = onUi {
        if (resumed) receive(message)
      }
      override fun onFailure(failure: NvdaRemoteSession.Failure, fingerprint: String?) = onUi {
        disconnect(false)
        val message = when (failure) {
          NvdaRemoteSession.Failure.CERTIFICATE -> R.string.nvda_remote_certificate_error
          NvdaRemoteSession.Failure.CONNECTION -> R.string.nvda_remote_connection_error
          NvdaRemoteSession.Failure.DISCONNECTED -> R.string.nvda_remote_connection_lost
          NvdaRemoteSession.Failure.PROTOCOL -> R.string.nvda_remote_protocol_error
          NvdaRemoteSession.Failure.MULTIPLE_COMPUTERS -> R.string.nvda_remote_multiple_computers
        }
        status.setText(message)
        if (resumed) announce(message)
        if (resumed && failure == NvdaRemoteSession.Failure.CERTIFICATE && fingerprint != null && trustedFingerprint == null) {
          certificateDialog = AlertDialog.Builder(this@NvdaRemoteActivity)
            .setTitle(R.string.nvda_remote_certificate_error)
            .setMessage(getString(R.string.nvda_remote_certificate_message, hostname, fingerprint))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.nvda_remote_trust_once) { _, _ -> connect(fingerprint) }
            .create().also { it.show() }
        }
      }
    })
    session = created
    status.setText(R.string.nvda_remote_connecting)
    announce(R.string.nvda_remote_connecting)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    updateButtons()
    created.start()
  }

  private fun receive(message: JSONObject) {
    when (message.optString("type")) {
      "speak" -> {
        val sequence = message.optJSONArray("sequence") ?: return
        val spoken = NvdaRemoteSpeech.text(sequence)
        lastSpeech.text = getString(R.string.nvda_remote_output, spoken.toString())
        if (!remoteSpeechMuted) NvdaRemoteSpeech.speak(spoken, message.optInt("priority") == 2)
      }
      // Muted remote output must not interrupt or pause CAN Talk's local announcements.
      "cancel" -> if (!remoteSpeechMuted) NvdaRemoteSpeech.cancel()
      "pause_speech" -> if (!remoteSpeechMuted) NvdaRemoteSpeech.pause(message.optBoolean("switch"))
      "set_clipboard_text" -> {
        // Receiving text is an explicit local action; it is not copied automatically.
        val value = message.optString("text").take(8192)
        setControl(false, false)
        AlertDialog.Builder(this).setTitle(R.string.nvda_remote_clipboard)
          .setMessage(R.string.nvda_remote_clipboard_message)
          .setNegativeButton(android.R.string.cancel, null)
          .setPositiveButton(R.string.nvda_remote_copy) { _, _ ->
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NVDA", value))
          }.show()
      }
    }
  }

  private fun remoteSpeechActionLabel(): Int = if (remoteSpeechMuted)
    R.string.nvda_remote_unmute_speech else R.string.nvda_remote_mute_speech

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
    if (focused) updateTouchRegion()
    else {
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
      "toggle_remote_speech" -> {
        if (learning) announce(remoteSpeechActionLabel()) else toggleRemoteSpeech()
      }
      "mode" -> {
        releaseHeldModifiers()
        if (!finishRemoteBraille()) return
        mode = if (mode == NvdaRemoteMode.CONTROL) NvdaRemoteMode.KEYBOARD else NvdaRemoteMode.CONTROL
        getPreferences(MODE_PRIVATE).edit().putString("input_mode", mode.name).apply()
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
  private fun dialog(builder: AlertDialog.Builder) {
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
  }

  private fun showCommands() {
    val actions = listOf("mode", "toggle_remote_speech", "learn", "clear", "settings", "exit") + listOf(
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
    remoteButtons.forEach { it.isEnabled = session?.ready == true }
  }

  private fun disconnect(speak: Boolean = true) {
    val previous = session
    session = null // Invalidate queued callbacks before closing its worker threads.
    remoteSpeechMuted = false
    previous?.close()
    setControl(false, false)
    captureMode = null
    pendingBraille = false
    removeSurface()
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    if (previous != null) NvdaRemoteSpeech.cancel()
    status.setText(R.string.nvda_remote_disconnected)
    updateButtons()
    if (speak) announce(R.string.nvda_remote_disconnected)
  }

  private fun announce(resource: Int) {
    announce(getString(resource))
  }
  private fun announce(message: String) {
    if (TalkBackService.getInstance() != null) NvdaRemoteSpeech.speak(message, true)
    else status.announceForAccessibility(message)
  }

  override fun onResume() { super.onResume(); resumed = true; active = this }
  override fun onPause() {
    resumed = false
    if (active === this) active = null
    setControl(false, false)
    captureMode = null
    removeSurface()
    certificateDialog?.dismiss()
    certificateDialog = null
    uiDialog?.dismiss()
    uiDialog = null
    super.onPause()
  }
  override fun onStop() { disconnect(false); super.onStop() }
  override fun onDestroy() { session?.close(); super.onDestroy() }

  companion object {
    private var active: NvdaRemoteActivity? = null // Accessed only on the Android main thread.
    @JvmStatic fun handleGesture(id: Int): Boolean = active?.let {
      it.resumed && it.surface != null && it.hasWindowFocus()
    } == true
  }
}
