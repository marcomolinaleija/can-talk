/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.clipboard.ClipboardHistoryStore.EditResult
import com.google.android.accessibility.talkback.clipboard.ClipboardHistoryStore.Entry
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.talkback.preference.base.announce
import com.google.android.accessibility.talkback.preference.base.positionText
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText

/**
 * Settings screen to search, filter, add, edit, rename, reorder, favorite, copy, share, and delete
 * clipboard history entries, as well as configure clipboard history options.
 */
class ClipboardHistoryFragment : TalkbackBaseFragment() {
  private var store: ClipboardHistoryStore? = null
  private var searchQuery: String = ""
  private var favoritesOnly: Boolean = false
  private var activeDialog: A11yAlertDialogWrapper? = null

  public override fun getTitle(): CharSequence = getText(R.string.clipboard_history_title)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    if (savedInstanceState != null) {
      searchQuery = savedInstanceState.getString(STATE_QUERY, "")
      favoritesOnly = savedInstanceState.getBoolean(STATE_FAVORITES_ONLY, false)
    }
    rebuildScreen()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putString(STATE_QUERY, searchQuery)
    outState.putBoolean(STATE_FAVORITES_ONLY, favoritesOnly)
  }

  override fun onResume() {
    super.onResume()
    rebuildScreen()
  }

  override fun onDestroyView() {
    activeDialog?.dismiss()
    activeDialog = null
    super.onDestroyView()
  }

  private fun openStore(): ClipboardHistoryStore? {
    val context = context ?: return null
    val userManager = context.getSystemService(UserManager::class.java)
    if (userManager != null && !userManager.isUserUnlocked) return null
    if (store == null) store = ClipboardHistoryStore.open(context)
    return store
  }

  private fun rebuildScreen() {
    val context = context ?: return
    val screen = preferenceManager.createPreferenceScreen(context)
    val history = openStore()
    if (history == null) {
      screen.addPreference(Preference(context).apply {
        isPersistent = false
        layoutResource = R.layout.listitem_2texts
        setTitle(R.string.clipboard_history_locked)
        isSelectable = false
      })
      preferenceScreen = screen
      return
    }

    val allItems = history.items(false)
    val favoriteItems = history.items(true)
    val visibleItems = history.search(searchQuery, favoritesOnly)
    val nonFavoriteCount = allItems.size - favoriteItems.size

    // Search action
    screen.addPreference(actionPreference(
      context,
      key = "clipboard_search",
      title = getString(R.string.clipboard_history_search_title),
      summary = if (searchQuery.isBlank()) {
        getString(R.string.clipboard_history_search_summary)
      } else {
        getString(R.string.clipboard_history_search_active_summary, searchQuery, visibleItems.size)
      },
    ) { showSearchDialog() })

    if (searchQuery.isNotBlank()) {
      screen.addPreference(actionPreference(
        context,
        key = "clipboard_clear_search",
        title = getString(R.string.clipboard_history_search_clear),
        summary = null,
      ) {
        searchQuery = ""
        rebuildScreen()
      })
    }

    // Category filter toggle
    screen.addPreference(actionPreference(
      context,
      key = "clipboard_filter",
      title = getString(R.string.clipboard_history_filter_title),
      summary = if (favoritesOnly) {
        getString(R.string.clipboard_history_filter_favorites, favoriteItems.size)
      } else {
        getString(R.string.clipboard_history_filter_all, allItems.size)
      },
    ) {
      favoritesOnly = !favoritesOnly
      rebuildScreen()
      val announcement = if (favoritesOnly) {
        getString(R.string.clipboard_history_filter_favorites, favoriteItems.size)
      } else {
        getString(R.string.clipboard_history_filter_all, allItems.size)
      }
      view?.let { announce(it, announcement) }
    })

    // Add new manual entry
    screen.addPreference(actionPreference(
      context,
      key = "clipboard_add",
      title = getString(R.string.clipboard_history_add_item),
      summary = getString(R.string.clipboard_history_add_item_summary),
    ) { showAddOrEditDialog(existing = null) })

    // Import current system clipboard (works on Android 10+ because this Activity has window focus!)
    screen.addPreference(actionPreference(
      context,
      key = "clipboard_import_current",
      title = getString(R.string.clipboard_history_import_current),
      summary = getString(R.string.clipboard_history_import_current_summary),
    ) { importCurrentSystemClipboard() })

    // Items list category
    val itemsCategory = PreferenceCategory(context).apply {
      isIconSpaceReserved = false
      title = if (favoritesOnly) {
        getString(R.string.clipboard_history_favorites_category, favoriteItems.size, history.maxFavorites)
      } else {
        getString(R.string.clipboard_history_items_category, allItems.size, history.maxEntries)
      }
    }
    screen.addPreference(itemsCategory)

    if (visibleItems.isEmpty()) {
      itemsCategory.addPreference(Preference(context).apply {
        isPersistent = false
        layoutResource = R.layout.listitem_2texts
        isSelectable = false
        title = if (searchQuery.isNotBlank()) {
          getString(R.string.clipboard_history_search_no_results, searchQuery)
        } else {
          getString(R.string.clipboard_history_empty)
        }
      })
    } else {
      val allowReorder = searchQuery.isBlank()
      visibleItems.forEachIndexed { idx, entry ->
        itemsCategory.addPreference(
          ClipboardEntryPreference(context, entry, idx, visibleItems.size, allowReorder).apply {
            order = idx
          }
        )
      }
    }

    // Options and maintenance category
    val optionsCategory = PreferenceCategory(context).apply {
      isIconSpaceReserved = false
      setTitle(R.string.clipboard_history_options_category)
    }
    screen.addPreference(optionsCategory)

    optionsCategory.addPreference(SwitchPreferenceCompat(context).apply {
      key = ClipboardHistorySettings.PREF_CAPTURE_ENABLED
      isPersistent = false
      setTitle(R.string.clipboard_history_capture_title)
      setSummary(R.string.clipboard_history_capture_summary)
      isChecked = ClipboardHistorySettings.isCaptureEnabled(context)
      setOnPreferenceChangeListener { _, newValue ->
        ClipboardHistorySettings.setCaptureEnabled(context, newValue as Boolean)
        true
      }
    })

    optionsCategory.addPreference(SwitchPreferenceCompat(context).apply {
      key = ClipboardHistorySettings.PREF_SPEAK_CONTENT_WITH_LABEL
      isPersistent = false
      setTitle(R.string.clipboard_history_speak_content_with_label_title)
      setSummary(R.string.clipboard_history_speak_content_with_label_summary)
      isChecked = ClipboardHistorySettings.speakContentWithLabel(context)
      setOnPreferenceChangeListener { _, newValue ->
        ClipboardHistorySettings.setSpeakContentWithLabel(context, newValue as Boolean)
        true
      }
    })

    optionsCategory.addPreference(actionPreference(
      context,
      key = ClipboardHistorySettings.PREF_MAX_ENTRIES,
      title = getString(R.string.clipboard_history_max_entries_title),
      summary = getString(
        R.string.clipboard_history_max_entries_summary,
        history.maxEntries,
        ClipboardHistoryStore.DEFAULT_MAX_ENTRIES,
      ),
    ) { showMaxEntriesDialog(history) })

    optionsCategory.addPreference(actionPreference(
      context,
      key = "clipboard_clear_non_favorites",
      title = getString(R.string.clipboard_history_clear_non_favorites),
      summary = getString(R.string.clipboard_history_clear_non_favorites_summary, nonFavoriteCount),
      enabled = nonFavoriteCount > 0,
    ) { confirmClear(keepFavorites = true, count = nonFavoriteCount) })

    optionsCategory.addPreference(actionPreference(
      context,
      key = "clipboard_clear_all",
      title = getString(R.string.clipboard_history_clear_all),
      summary = getString(R.string.clipboard_history_clear_all_summary, allItems.size),
      enabled = allItems.isNotEmpty(),
    ) { confirmClear(keepFavorites = false, count = allItems.size) })

    preferenceScreen = screen
  }

  private fun actionPreference(
    context: Context,
    key: String,
    title: String,
    summary: String?,
    enabled: Boolean = true,
    onClick: () -> Unit,
  ) = Preference(context).apply {
    this.key = key
    isPersistent = false
    layoutResource = R.layout.listitem_2texts
    this.title = title
    this.summary = summary
    isEnabled = enabled
    setOnPreferenceClickListener {
      onClick()
      true
    }
  }

  private fun showMaxEntriesDialog(history: ClipboardHistoryStore) {
    val context = requireContext()
    val container = dialogColumn(context)
    val label = TextView(context).apply {
      text = getString(
        R.string.clipboard_history_max_entries_hint,
        ClipboardHistoryStore.MIN_ENTRIES_LIMIT,
        ClipboardHistoryStore.MAX_ENTRIES_LIMIT,
      )
      isSaveEnabled = false
    }
    val input = KeyboardSafeEditText(context).apply {
      id = View.generateViewId()
      label.labelFor = id
      isSaveEnabled = false
      inputType = InputType.TYPE_CLASS_NUMBER
      isSingleLine = true
      filters = arrayOf(InputFilter.LengthFilter(4))
      setText(history.maxEntries.toString())
      setSelection(text?.length ?: 0)
    }
    container.addView(label)
    container.addView(input)

    val wrapper = A11yAlertDialogWrapper.materialDialogBuilder(context)
      .setTitle(R.string.clipboard_history_max_entries_title)
      .setView(container)
      .setPositiveButton(R.string.clipboard_history_save, null)
      .setNeutralButton(R.string.clipboard_history_max_entries_reset) { _, _ ->
        ClipboardHistorySettings.setMaxEntries(context, ClipboardHistoryStore.DEFAULT_MAX_ENTRIES)
        history.applyLimit()
        Toast.makeText(
          context,
          getString(R.string.clipboard_history_max_entries_saved, ClipboardHistoryStore.DEFAULT_MAX_ENTRIES),
          Toast.LENGTH_SHORT,
        ).show()
        rebuildScreen()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .create()
    showDialog(wrapper)
    wrapper.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
      commitBrailleAndRun(input, wrapper) {
        val parsed = input.text?.toString()?.trim()?.toIntOrNull()
        if (parsed == null || parsed !in ClipboardHistoryStore.MIN_ENTRIES_LIMIT..ClipboardHistoryStore.MAX_ENTRIES_LIMIT) {
          val msg = getString(
            R.string.clipboard_history_max_entries_error,
            ClipboardHistoryStore.MIN_ENTRIES_LIMIT,
            ClipboardHistoryStore.MAX_ENTRIES_LIMIT,
          )
          input.error = msg
          input.requestFocus()
          announce(input, msg)
          return@commitBrailleAndRun
        }
        ClipboardHistorySettings.setMaxEntries(context, parsed)
        history.applyLimit()
        wrapper.dismiss()
        Toast.makeText(
          context,
          getString(R.string.clipboard_history_max_entries_saved, parsed),
          Toast.LENGTH_SHORT,
        ).show()
        rebuildScreen()
      }
    }
  }

  private fun showSearchDialog() {
    val context = requireContext()
    val container = dialogColumn(context)
    val label = TextView(context).apply {
      setText(R.string.clipboard_history_search_hint)
      isSaveEnabled = false
    }
    val input = KeyboardSafeEditText(context).apply {
      id = View.generateViewId()
      label.labelFor = id
      isSaveEnabled = false
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
      imeOptions = EditorInfo.IME_ACTION_SEARCH
      isSingleLine = true
      setText(searchQuery)
      setSelection(text?.length ?: 0)
    }
    container.addView(label)
    container.addView(input)

    val builder = A11yAlertDialogWrapper.materialDialogBuilder(context)
      .setTitle(R.string.clipboard_history_search_title)
      .setView(container)
      .setPositiveButton(R.string.clipboard_history_search_title, null)
      .setNegativeButton(android.R.string.cancel, null)
    if (searchQuery.isNotBlank()) {
      builder.setNeutralButton(R.string.clipboard_history_search_clear) { _, _ ->
        searchQuery = ""
        rebuildScreen()
      }
    }
    val wrapper = builder.create()
    showDialog(wrapper)
    wrapper.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
      commitBrailleAndRun(input, wrapper) {
        searchQuery = input.text?.toString()?.trim().orEmpty()
        wrapper.dismiss()
        rebuildScreen()
        val count = openStore()?.search(searchQuery, favoritesOnly)?.size ?: 0
        view?.let {
          val msg = if (count == 0 && searchQuery.isNotEmpty()) {
            getString(R.string.clipboard_history_search_no_results, searchQuery)
          } else {
            resources.getQuantityString(R.plurals.clipboard_history_results_count, count, count)
          }
          announce(it, msg)
        }
      }
    }
  }

  private fun showEntryOptionsDialog(entry: Entry) {
    val context = requireContext()
    val favoriteLabel = getString(
      if (entry.favorite) R.string.clipboard_history_action_remove_favorite
      else R.string.clipboard_history_action_add_favorite
    )
    val options = arrayOf(
      getString(R.string.clipboard_history_action_copy),
      getString(R.string.clipboard_history_action_rename),
      getString(R.string.clipboard_history_action_edit),
      favoriteLabel,
      getString(R.string.clipboard_history_action_view_full),
      getString(R.string.clipboard_history_action_share),
      getString(R.string.clipboard_history_action_delete),
    )
    val title = if (entry.label.isNotEmpty()) entry.label
      else ClipboardSearchOverlayView.singleLinePreview(entry.text, 60)

    val wrapper = A11yAlertDialogWrapper.materialDialogBuilder(context)
      .setTitle(title)
      .setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, options)) { _, which ->
        when (which) {
          0 -> copyEntry(entry)
          1 -> showRenameDialog(entry)
          2 -> showAddOrEditDialog(existing = entry)
          3 -> toggleFavoriteEntry(entry)
          4 -> showFullTextDialog(entry)
          5 -> shareEntry(entry)
          6 -> confirmDeleteEntry(entry)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .create()
    showDialog(wrapper)
  }

  private fun showRenameDialog(entry: Entry) {
    val context = requireContext()
    val container = dialogColumn(context)
    val fieldLabel = TextView(context).apply {
      setText(R.string.clipboard_history_field_label)
      isSaveEnabled = false
    }
    val input = KeyboardSafeEditText(context).apply {
      id = View.generateViewId()
      fieldLabel.labelFor = id
      isSaveEnabled = false
      setHint(R.string.clipboard_history_field_label_hint)
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
      isSingleLine = true
      filters = arrayOf(InputFilter.LengthFilter(ClipboardHistoryStore.MAX_LABEL_LENGTH))
      setText(entry.label)
      setSelection(text?.length ?: 0)
    }
    container.addView(fieldLabel)
    container.addView(input)

    val builder = A11yAlertDialogWrapper.materialDialogBuilder(context)
      .setTitle(R.string.clipboard_history_action_rename)
      .setView(container)
      .setPositiveButton(R.string.clipboard_history_save, null)
      .setNegativeButton(android.R.string.cancel, null)
    if (entry.label.isNotEmpty()) {
      builder.setNeutralButton(R.string.clipboard_history_remove_label) { _, _ ->
        if (openStore()?.rename(entry.text, "") == true) {
          toast(R.string.clipboard_history_label_removed)
          rebuildScreen()
        }
      }
    }
    val wrapper = builder.create()
    showDialog(wrapper)
    wrapper.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
      commitBrailleAndRun(input, wrapper) {
        val newLabel = ClipboardHistoryStore.sanitizeLabel(input.text?.toString().orEmpty())
        if (openStore()?.rename(entry.text, newLabel) == true) {
          wrapper.dismiss()
          toast(if (newLabel.isEmpty()) R.string.clipboard_history_label_removed else R.string.clipboard_history_renamed)
          rebuildScreen()
        }
      }
    }
  }

  private fun showAddOrEditDialog(existing: Entry?) {
    val context = requireContext()
    val container = dialogColumn(context)

    val labelHeading = TextView(context).apply {
      setText(R.string.clipboard_history_field_label)
      isSaveEnabled = false
    }
    val labelInput = KeyboardSafeEditText(context).apply {
      id = View.generateViewId()
      labelHeading.labelFor = id
      isSaveEnabled = false
      setHint(R.string.clipboard_history_field_label_hint)
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
      isSingleLine = true
      filters = arrayOf(InputFilter.LengthFilter(ClipboardHistoryStore.MAX_LABEL_LENGTH))
      setText(existing?.label.orEmpty())
    }
    container.addView(labelHeading)
    container.addView(labelInput)

    val textHeading = TextView(context).apply {
      setText(R.string.clipboard_history_field_text)
      isSaveEnabled = false
      setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
    }
    val textInput = KeyboardSafeEditText(context).apply {
      id = View.generateViewId()
      textHeading.labelFor = id
      isSaveEnabled = false
      setHint(R.string.clipboard_history_field_text_hint)
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
      minLines = 3
      maxLines = 8
      filters = arrayOf(InputFilter.LengthFilter(ClipboardHistoryStore.MAX_TEXT_LENGTH))
      setText(existing?.text.orEmpty())
    }
    container.addView(textHeading)
    container.addView(textInput)

    val favoriteCheck = CheckBox(context).apply {
      isSaveEnabled = false
      setText(R.string.clipboard_history_field_favorite)
      isChecked = existing?.favorite ?: favoritesOnly
    }
    container.addView(favoriteCheck)

    val scroll = ScrollView(context).apply {
      isSaveEnabled = false
      addView(container)
    }
    val wrapper = A11yAlertDialogWrapper.materialDialogBuilder(context)
      .setTitle(if (existing == null) R.string.clipboard_history_add_item else R.string.clipboard_history_action_edit)
      .setView(scroll)
      .setPositiveButton(R.string.clipboard_history_save, null)
      .setNegativeButton(android.R.string.cancel, null)
      .create()
    showDialog(wrapper)

    wrapper.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
      val focusedEditor = (wrapper.window?.currentFocus as? EditText) ?: textInput
      commitBrailleAndRun(focusedEditor, wrapper) {
        val newLabel = labelInput.text?.toString().orEmpty()
        val newText = textInput.text?.toString().orEmpty()
        val isFav = favoriteCheck.isChecked
        val history = openStore() ?: return@commitBrailleAndRun
        val result = if (existing == null) {
          history.addManual(newText, newLabel, isFav)
        } else {
          history.update(existing.text, newText, newLabel, isFav)
        }
        when (result) {
          EditResult.SUCCESS -> {
            wrapper.dismiss()
            toast(R.string.clipboard_history_saved)
            rebuildScreen()
          }
          EditResult.INVALID_TEXT -> {
            val msg = getString(R.string.clipboard_history_error_empty)
            textInput.error = msg
            textInput.requestFocus()
            announce(textInput, msg)
          }
          EditResult.TEXT_TOO_LONG -> {
            val msg = getString(R.string.clipboard_history_error_too_long)
            textInput.error = msg
            textInput.requestFocus()
            announce(textInput, msg)
          }
          EditResult.DUPLICATE -> {
            val msg = getString(R.string.clipboard_history_error_duplicate)
            textInput.error = msg
            textInput.requestFocus()
            announce(textInput, msg)
          }
          EditResult.FAVORITES_FULL -> {
            toast(R.string.clipboard_history_favorites_full)
          }
          EditResult.NOT_FOUND -> {
            wrapper.dismiss()
            rebuildScreen()
          }
        }
      }
    }
  }

  private fun showFullTextDialog(entry: Entry) {
    val context = requireContext()
    val title = if (entry.label.isNotEmpty()) entry.label
      else getString(R.string.clipboard_history_action_view_full)
    val wrapper = A11yAlertDialogWrapper.materialDialogBuilder(context)
      .setTitle(title)
      .setMessage(entry.text)
      .setPositiveButton(R.string.clipboard_history_action_copy) { _, _ -> copyEntry(entry) }
      .setNeutralButton(R.string.clipboard_history_action_share) { _, _ -> shareEntry(entry) }
      .setNegativeButton(android.R.string.ok, null)
      .create()
    showDialog(wrapper)
  }

  private fun copyEntry(entry: Entry) {
    val context = requireContext()
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    try {
      clipboard?.setPrimaryClip(ClipData.newPlainText("can-talk", entry.text))
      toast(R.string.clipboard_history_copied)
    } catch (_: RuntimeException) {
      toast(R.string.clipboard_history_copy_failed)
    }
  }

  private fun shareEntry(entry: Entry) {
    val context = requireContext()
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_TEXT, entry.text)
      if (entry.label.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, entry.label)
    }
    try {
      startActivity(Intent.createChooser(intent, getString(R.string.clipboard_history_action_share)))
    } catch (_: RuntimeException) {
      toast(R.string.clipboard_history_copy_failed)
    }
  }

  private fun toggleFavoriteEntry(entry: Entry) {
    val result = openStore()?.toggleFavorite(entry.text)
    if (result == null) {
      toast(R.string.clipboard_history_favorites_full)
      return
    }
    toast(if (result) R.string.clipboard_history_favorite_added else R.string.clipboard_history_favorite_removed)
    rebuildScreen()
  }

  private fun confirmDeleteEntry(entry: Entry) {
    val context = requireContext()
    val title = if (entry.label.isNotEmpty()) entry.label
      else ClipboardSearchOverlayView.singleLinePreview(entry.text, 60)
    val wrapper = A11yAlertDialogWrapper.materialDialogBuilder(context)
      .setTitle(title)
      .setMessage(R.string.clipboard_history_delete_confirm_message)
      .setPositiveButton(R.string.clipboard_history_action_delete) { _, _ ->
        if (openStore()?.delete(entry.text) == true) {
          toast(R.string.clipboard_history_deleted)
          rebuildScreen()
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .create()
    showDialog(wrapper)
  }

  private fun confirmClear(keepFavorites: Boolean, count: Int) {
    val context = requireContext()
    val message = if (keepFavorites) {
      getString(R.string.clipboard_history_clear_non_favorites_confirm, count)
    } else {
      getString(R.string.clipboard_history_clear_all_confirm, count)
    }
    val wrapper = A11yAlertDialogWrapper.materialDialogBuilder(context)
      .setTitle(if (keepFavorites) R.string.clipboard_history_clear_non_favorites else R.string.clipboard_history_clear_all)
      .setMessage(message)
      .setPositiveButton(R.string.clipboard_history_action_delete) { _, _ ->
        val removed = openStore()?.clear(keepFavorites) ?: 0
        if (removed > 0) {
          Toast.makeText(context, getString(R.string.clipboard_history_cleared, removed), Toast.LENGTH_SHORT).show()
          rebuildScreen()
        } else {
          toast(R.string.clipboard_history_nothing_to_clear)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .create()
    showDialog(wrapper)
  }

  private fun importCurrentSystemClipboard() {
    val context = requireContext()
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clip = try { clipboard?.primaryClip } catch (_: RuntimeException) { null }
    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
    if (text.isNullOrBlank()) {
      toast(R.string.clipboard_history_import_current_empty)
      return
    }
    if (text.length > ClipboardHistoryStore.MAX_TEXT_LENGTH) {
      toast(R.string.clipboard_history_error_too_long)
      return
    }
    if (openStore()?.record(text) == true) {
      toast(R.string.clipboard_history_import_current_saved)
      rebuildScreen()
    } else {
      toast(R.string.clipboard_history_import_current_empty)
    }
  }

  private fun moveEntry(entry: Entry, offset: Int, currentIndex: Int, totalCount: Int): Boolean {
    if (openStore()?.moveInView(entry.text, offset, favoritesOnly) != true) return false
    val nextIndex = (currentIndex + offset).coerceIn(0, (totalCount - 1).coerceAtLeast(0))
    rebuildScreen()
    view?.let { announce(it, positionText(it, nextIndex, totalCount)) }
    return true
  }

  private fun commitBrailleAndRun(editor: EditText, dialog: A11yAlertDialogWrapper, action: () -> Unit) {
    val pkg = context?.packageName
    if (pkg != null) {
      TalkBackService.getInstance()?.commitPendingBrailleInput(pkg, editor.id)
    }
    editor.post {
      if (isAdded && dialog.isShowing) action()
    }
  }

  private fun dialogColumn(context: Context) = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    val padding = (20 * resources.displayMetrics.density).toInt()
    setPadding(padding, padding / 2, padding, padding / 2)
    isSaveEnabled = false
  }

  private fun showDialog(wrapper: A11yAlertDialogWrapper) {
    activeDialog?.dismiss()
    activeDialog = wrapper
    wrapper.setOnDismissListener {
      if (activeDialog === wrapper) activeDialog = null
    }
    wrapper.show()
  }

  private fun toast(messageRes: Int) {
    if (isAdded) Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
  }

  private inner class ClipboardEntryPreference(
    context: Context,
    private val entry: Entry,
    private val position: Int,
    private val totalCount: Int,
    private val allowReorder: Boolean,
  ) : Preference(context) {
    init {
      key = "clipboard_entry_$position"
      isPersistent = false
      layoutResource = R.layout.listitem_2texts
      val preview = ClipboardSearchOverlayView.singleLinePreview(entry.text, 120)
      val favoriteTag = context.getString(R.string.clipboard_history_favorite)
      val lengthInfo = if (entry.text.length > 120) {
        context.getString(R.string.clipboard_history_char_count, entry.text.length)
      } else null

      if (entry.label.isNotEmpty()) {
        title = entry.label
        summary = listOfNotNull(
          if (entry.favorite) favoriteTag else null,
          preview,
          lengthInfo,
        ).joinToString(" · ")
      } else {
        title = ClipboardSearchOverlayView.singleLinePreview(entry.text, 80)
        summary = listOfNotNull(
          if (entry.favorite) favoriteTag else null,
          lengthInfo,
        ).joinToString(" · ").ifEmpty { null }
      }

      setOnPreferenceClickListener {
        showEntryOptionsDialog(entry)
        true
      }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
      super.onBindViewHolder(holder)
      val row = holder.itemView
      replaceAction(row, ACTION_COPY, R.string.clipboard_history_action_copy) {
        copyEntry(entry)
        true
      }
      replaceAction(row, ACTION_RENAME, R.string.clipboard_history_action_rename) {
        showRenameDialog(entry)
        true
      }
      replaceAction(row, ACTION_EDIT, R.string.clipboard_history_action_edit) {
        showAddOrEditDialog(entry)
        true
      }
      replaceAction(
        row,
        ACTION_FAVORITE,
        if (entry.favorite) R.string.clipboard_history_action_remove_favorite
        else R.string.clipboard_history_action_add_favorite,
      ) {
        toggleFavoriteEntry(entry)
        true
      }
      replaceAction(row, ACTION_DELETE, R.string.clipboard_history_action_delete) {
        confirmDeleteEntry(entry)
        true
      }
      if (allowReorder && position > 0) {
        replaceAction(row, ACTION_MOVE_UP, R.string.action_move_up) {
          moveEntry(entry, -1, position, totalCount)
        }
      } else {
        ViewCompat.removeAccessibilityAction(row, ACTION_MOVE_UP)
      }
      if (allowReorder && position < totalCount - 1) {
        replaceAction(row, ACTION_MOVE_DOWN, R.string.action_move_down) {
          moveEntry(entry, 1, position, totalCount)
        }
      } else {
        ViewCompat.removeAccessibilityAction(row, ACTION_MOVE_DOWN)
      }
    }

    private fun replaceAction(row: View, id: Int, labelRes: Int, handler: () -> Boolean) {
      val label = row.context.getString(labelRes)
      ViewCompat.replaceAccessibilityAction(row, AccessibilityActionCompat(id, label), label) { _, _ ->
        handler()
      }
    }
  }

  companion object {
    private const val STATE_QUERY = "clipboard_search_query"
    private const val STATE_FAVORITES_ONLY = "clipboard_favorites_only"
    private val ACTION_COPY = R.id.accessibility_custom_action_0
    private val ACTION_RENAME = R.id.accessibility_custom_action_1
    private val ACTION_EDIT = R.id.accessibility_custom_action_2
    private val ACTION_FAVORITE = R.id.accessibility_custom_action_3
    private val ACTION_DELETE = R.id.accessibility_custom_action_4
    private val ACTION_MOVE_UP = R.id.accessibility_custom_action_5
    private val ACTION_MOVE_DOWN = R.id.accessibility_custom_action_6
  }
}
