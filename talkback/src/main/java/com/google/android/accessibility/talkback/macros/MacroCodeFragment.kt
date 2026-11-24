/*
 * Copyright 2026 CAN Talk contributors
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

package com.google.android.accessibility.talkback.macros

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Base fragment for programmatically built macro preference screens and accessibility-friendly
 * dialogs in CAN Talk.
 */
abstract class MacroCodeFragment(@StringRes private val pageTitleRes: Int = 0) :
  TalkbackBaseFragment() {

  protected val prefs: SharedPreferences
    get() = SharedPreferencesUtils.getSharedPreferences(requireContext())

  override fun getTitle(): CharSequence =
    if (pageTitleRes != 0) getText(pageTitleRes) else ""

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    val context = context ?: return
    preferenceScreen = preferenceManager.createPreferenceScreen(context)
    rebuild()
  }

  override fun onResume() {
    super.onResume()
    rebuild()
  }

  protected open fun build(screen: PreferenceGroup) {}

  protected fun rebuild() {
    val screen = preferenceScreen ?: return
    screen.removeAll()
    build(screen)
  }

  protected fun dialog(): AlertDialog.Builder = AlertDialog.Builder(requireContext())

  protected fun toast(@StringRes messageRes: Int) {
    val ctx = context ?: return
    Toast.makeText(ctx, messageRes, Toast.LENGTH_SHORT).show()
  }

  protected fun toast(message: CharSequence) {
    val ctx = context ?: return
    Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
  }

  protected fun PreferenceGroup.category(@StringRes titleRes: Int): PreferenceCategory {
    val cat =
      PreferenceCategory(context).apply {
        setTitle(titleRes)
        isIconSpaceReserved = false
      }
    addPreference(cat)
    return cat
  }

  protected fun PreferenceGroup.action(
    title: CharSequence,
    summary: CharSequence? = null,
    onClick: () -> Unit,
  ): Preference {
    val pref =
      Preference(context).apply {
        this.title = title
        this.summary = summary
        isPersistent = false
        isIconSpaceReserved = false
        setOnPreferenceClickListener {
          onClick()
          true
        }
      }
    addPreference(pref)
    return pref
  }

  protected fun PreferenceGroup.link(
    title: CharSequence,
    summary: CharSequence?,
    fragmentClass: Class<out Fragment>,
    args: Bundle.() -> Unit = {},
  ): Preference {
    val pref =
      Preference(context).apply {
        this.title = title
        this.summary = summary
        isPersistent = false
        isIconSpaceReserved = false
        fragment = fragmentClass.name
        extras.args()
      }
    addPreference(pref)
    return pref
  }

  protected fun PreferenceGroup.switch(
    title: CharSequence,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
  ): SwitchPreferenceCompat {
    val pref =
      SwitchPreferenceCompat(context).apply {
        this.title = title
        isChecked = checked
        isPersistent = false
        isIconSpaceReserved = false
        setOnPreferenceChangeListener { _, newValue ->
          onChange(newValue as Boolean)
          true
        }
      }
    addPreference(pref)
    return pref
  }

  /** Choice spinner configuration passed into [askText]. */
  class DialogChoice(
    val title: CharSequence,
    val labels: List<CharSequence>,
    initialSelection: Int,
  ) {
    var selected: Int = initialSelection.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
  }

  /** Named accessibility action attached to an [ActionRow]. */
  class RowAction(
    val id: Int,
    @StringRes val labelRes: Int,
    val handler: () -> Unit,
  )

  /** Preference row with custom accessibility actions on top of its click handler. */
  class ActionRow(
    context: Context,
    title: CharSequence,
    summary: CharSequence? = null,
    private val actions: List<RowAction>,
    onClick: () -> Unit,
  ) : Preference(context) {
    init {
      this.title = title
      this.summary = summary
      isPersistent = false
      isIconSpaceReserved = false
      setOnPreferenceClickListener {
        onClick()
        true
      }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
      super.onBindViewHolder(holder)
      val row = holder.itemView
      val activeIds = actions.mapTo(HashSet()) { it.id }
      for (id in ALL_ROW_ACTION_IDS) {
        if (id !in activeIds) {
          ViewCompat.removeAccessibilityAction(row, id)
        }
      }
      for (action in actions) {
        val label = context.getString(action.labelRes)
        ViewCompat.replaceAccessibilityAction(
          row,
          AccessibilityActionCompat(action.id, label),
          label,
        ) { _, _ ->
          action.handler()
          true
        }
      }
    }

    companion object {
      private val ALL_ROW_ACTION_IDS =
        intArrayOf(
          R.id.accessibility_custom_action_0,
          R.id.accessibility_custom_action_1,
          R.id.accessibility_custom_action_2,
          R.id.accessibility_custom_action_3,
        )
    }
  }

  protected fun askText(
    title: CharSequence,
    text: String = "",
    hint: CharSequence? = null,
    secondHint: CharSequence? = null,
    secondText: String = "",
    message: CharSequence? = null,
    checkLabels: List<CharSequence> = emptyList(),
    checked: List<Boolean> = emptyList(),
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    secondInputType: Int = InputType.TYPE_CLASS_TEXT,
    choice: DialogChoice? = null,
    secondChoice: DialogChoice? = null,
    onDone: (String, String, List<Boolean>) -> CharSequence?,
  ) {
    val context = requireContext()
    val density = context.resources.displayMetrics.density
    val padH = (20 * density).toInt()
    val padV = (12 * density).toInt()
    val layout =
      LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padH, padV, padH, padV / 2)
      }

    if (!message.isNullOrEmpty()) {
      layout.addView(
        TextView(context).apply {
          this.text = message
          setPadding(0, 0, 0, (8 * density).toInt())
        }
      )
    }

    val errorView =
      TextView(context).apply {
        visibility = View.GONE
        setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
        setTextColor(0xFFB3261E.toInt())
      }

    val firstEdit =
      KeyboardSafeEditText(context).apply {
        setText(text)
        this.hint = hint
        this.inputType = inputType
        setSelection(text.length)
      }
    layout.addView(firstEdit)

    val secondEdit: KeyboardSafeEditText? =
      if (secondHint != null) {
        KeyboardSafeEditText(context).apply {
          setText(secondText)
          this.hint = secondHint
          this.inputType = secondInputType
          setSelection(secondText.length)
        }.also { layout.addView(it) }
      } else {
        null
      }

    val checkBoxes =
      checkLabels.mapIndexed { idx, label ->
        CheckBox(context).apply {
          this.text = label
          isChecked = checked.getOrElse(idx) { false }
        }.also { layout.addView(it) }
      }

    fun addChoiceSpinner(option: DialogChoice?) {
      if (option == null || option.labels.isEmpty()) return
      layout.addView(
        TextView(context).apply {
          this.text = option.title
          setPadding(0, (8 * density).toInt(), 0, (2 * density).toInt())
        }
      )
      val spinner =
        Spinner(context).apply {
          adapter =
            ArrayAdapter(context, android.R.layout.simple_spinner_item, option.labels).apply {
              setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
          setSelection(option.selected)
          onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
              override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
              ) {
                option.selected = position
              }

              override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
      layout.addView(spinner)
    }

    addChoiceSpinner(choice)
    addChoiceSpinner(secondChoice)
    layout.addView(errorView)

    val watcher =
      object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
          errorView.visibility = View.GONE
        }

        override fun afterTextChanged(s: Editable?) {}
      }
    firstEdit.addTextChangedListener(watcher)
    secondEdit?.addTextChangedListener(watcher)

    val built =
      dialog()
        .setTitle(title)
        .setView(layout)
        .setPositiveButton(android.R.string.ok, null)
        .setNegativeButton(android.R.string.cancel, null)
        .create()

    built.setOnShowListener {
      built.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val err =
          onDone(
            firstEdit.text?.toString().orEmpty(),
            secondEdit?.text?.toString().orEmpty(),
            checkBoxes.map { it.isChecked },
          )
        if (err == null) {
          built.dismiss()
        } else {
          if (err.isNotEmpty()) {
            errorView.text = err
            errorView.visibility = View.VISIBLE
            errorView.announceForAccessibility(err)
          } else {
            val missing = hint ?: title
            firstEdit.announceForAccessibility(missing)
          }
        }
      }
    }
    built.show()
  }

  protected fun choose(
    title: CharSequence,
    options: List<CharSequence>,
    selectedIndex: Int = -1,
    onChosen: (Int) -> Unit,
  ) {
    if (options.isEmpty()) return
    val items = options.map { it.toString() }.toTypedArray()
    val builder = dialog().setTitle(title).setNegativeButton(android.R.string.cancel, null)
    if (selectedIndex in items.indices) {
      builder.setSingleChoiceItems(items, selectedIndex) { dlg, which ->
        dlg.dismiss()
        onChosen(which)
      }
    } else {
      builder.setItems(items) { dlg, which ->
        dlg.dismiss()
        onChosen(which)
      }
    }
    builder.show()
  }

  protected fun confirm(
    title: CharSequence,
    @StringRes buttonRes: Int = R.string.menu_delete,
    onConfirm: () -> Unit,
  ) {
    dialog()
      .setTitle(title)
      .setPositiveButton(buttonRes) { _, _ -> onConfirm() }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  protected fun chooseApp(
    allLabel: String? = null,
    onChosen: (packageName: String, appLabel: String) -> Unit,
  ) {
    val appContext = context?.applicationContext ?: return
    APP_LOADER.execute {
      val apps = installedApps(appContext)
      activity?.runOnUiThread {
        if (!isAdded) return@runOnUiThread
        val entries = ArrayList<Pair<String, String>>()
        if (!allLabel.isNullOrEmpty()) {
          entries += "" to allLabel
        }
        entries.addAll(apps)
        val searchLabel = getString(android.R.string.search_go)
        val dialogTitle = getString(R.string.notification_choose_app)
        val display = listOf(searchLabel) + entries.map { it.second }
        choose(dialogTitle, display) { index ->
          if (index == 0) {
            askText(searchLabel, inputType = InputType.TYPE_CLASS_TEXT) { query, _, _ ->
              val matches = entries.filter { it.second.contains(query, ignoreCase = true) }
              if (matches.isEmpty()) {
                getString(R.string.keyboard_shortcuts_no_results)
              } else {
                choose(dialogTitle, matches.map { it.second }) { chosen ->
                  val item = matches[chosen]
                  onChosen(item.first, item.second)
                }
                null
              }
            }
          } else {
            val item = entries[index - 1]
            onChosen(item.first, item.second)
          }
        }
      }
    }
  }

  companion object {
    private val APP_LOADER =
      ThreadPoolExecutor(1, 1, 15L, TimeUnit.SECONDS, LinkedBlockingQueue())

    fun appLabel(context: Context, packageName: String): String {
      return try {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString()
      } catch (_: PackageManager.NameNotFoundException) {
        packageName
      }
    }

    private fun installedApps(context: Context): List<Pair<String, String>> {
      val pm = context.packageManager
      val launchable = pm.getInstalledApplications(PackageManager.MATCH_ALL)
        .filter { app ->
          (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
            pm.getLaunchIntentForPackage(app.packageName) != null
        }
        .map { it.packageName to appLabel(context, it.packageName) }
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }
      return launchable
    }
  }
}
