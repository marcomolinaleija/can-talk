/* Copyright 2026 can-talk contributors. SPDX-License-Identifier: Apache-2.0 */
package com.google.android.accessibility.talkback.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText

/**
 * Accessible in-overlay search panel for clipboard history.
 * Works with normal TalkBack exploration and any keyboard (including Braille keyboard).
 */
@SuppressLint("ViewConstructor")
class ClipboardSearchOverlayView(
  serviceContext: Context,
  private val canPaste: Boolean,
  initialFavoritesOnly: Boolean,
  private val listener: Listener,
) : LinearLayout(ContextThemeWrapper(serviceContext, R.style.TalkbackBaseTheme)) {

  interface Listener {
    fun onQueryChanged(query: String, favoritesOnly: Boolean)
    fun onPrimarySelect(entry: ClipboardHistoryStore.Entry)
    fun onCopy(entry: ClipboardHistoryStore.Entry)
    fun onToggleFavorite(entry: ClipboardHistoryStore.Entry)
    fun onDelete(entry: ClipboardHistoryStore.Entry)
    fun onOpenSettings()
    fun onClose()
  }

  private val themedContext: Context = context
  private val dp = resources.displayMetrics.density
  private val searchInput: KeyboardSafeEditText
  private val clearButton: Button
  private val generalButton: Button
  private val favoritesButton: Button
  private val statusView: TextView
  private val resultsList: RecyclerView
  private val adapter = ResultsAdapter()

  var favoritesOnly: Boolean = initialFavoritesOnly
    private set
  val currentQuery: String
    get() = searchInput.text?.toString().orEmpty()

  init {
    orientation = VERTICAL
    setBackgroundColor(Color.rgb(20, 24, 30))
    val outerPad = (16 * dp).toInt()
    val topPad = (40 * dp).toInt()
    setPadding(outerPad, topPad, outerPad, outerPad)

    // Header row: title + settings + close
    val header = LinearLayout(themedContext).apply {
      orientation = HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    val titleView = TextView(themedContext).apply {
      text = themedContext.getString(R.string.clipboard_history_search_title)
      setTextColor(Color.WHITE)
      textSize = 20f
      setTypeface(null, Typeface.BOLD)
      ViewCompat.setAccessibilityHeading(this, true)
    }
    header.addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

    val settingsButton = styledButton(R.string.clipboard_history_action_settings).apply {
      setOnClickListener {
        hideKeyboard()
        listener.onOpenSettings()
      }
    }
    header.addView(settingsButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
      marginEnd = (8 * dp).toInt()
    })

    val closeButton = styledButton(R.string.clipboard_history_action_close).apply {
      setOnClickListener {
        hideKeyboard()
        listener.onClose()
      }
    }
    header.addView(closeButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

    // Search field row
    val searchRow = LinearLayout(themedContext).apply {
      orientation = HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    searchInput = KeyboardSafeEditText(themedContext).apply {
      id = View.generateViewId()
      hint = themedContext.getString(R.string.clipboard_history_search_hint)
      setTextColor(Color.WHITE)
      setHintTextColor(Color.rgb(180, 188, 198))
      textSize = 18f
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
      imeOptions = EditorInfo.IME_ACTION_SEARCH
      isSingleLine = true
      background = roundedBox(Color.rgb(34, 41, 51), Color.rgb(96, 112, 134))
      val padH = (14 * dp).toInt()
      val padV = (12 * dp).toInt()
      setPadding(padH, padV, padH, padV)
      addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
          val text = s?.toString().orEmpty()
          clearButton.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
          listener.onQueryChanged(text, favoritesOnly)
        }
      })
      setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_SEARCH) {
          hideKeyboard()
          true
        } else {
          false
        }
      }
    }
    searchRow.addView(searchInput, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

    clearButton = styledButton(R.string.clipboard_history_search_clear).apply {
      visibility = View.GONE
      setOnClickListener {
        searchInput.text?.clear()
        searchInput.requestFocus()
      }
    }
    searchRow.addView(clearButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
      marginStart = (8 * dp).toInt()
    })
    addView(searchRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = (12 * dp).toInt()
    })

    // Filter row: General vs Favorites
    val filterRow = LinearLayout(themedContext).apply {
      orientation = HORIZONTAL
    }
    generalButton = styledButton(R.string.clipboard_history_general).apply {
      setOnClickListener {
        if (favoritesOnly) {
          favoritesOnly = false
          updateFilterAppearance()
          listener.onQueryChanged(currentQuery, false)
        }
      }
    }
    favoritesButton = styledButton(R.string.clipboard_history_favorites).apply {
      setOnClickListener {
        if (!favoritesOnly) {
          favoritesOnly = true
          updateFilterAppearance()
          listener.onQueryChanged(currentQuery, true)
        }
      }
    }
    filterRow.addView(generalButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
      marginEnd = (8 * dp).toInt()
    })
    filterRow.addView(favoritesButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    addView(filterRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = (10 * dp).toInt()
    })

    // Status line
    statusView = TextView(themedContext).apply {
      setTextColor(Color.rgb(200, 208, 218))
      textSize = 15f
      setPadding(0, (10 * dp).toInt(), 0, (6 * dp).toInt())
    }
    addView(statusView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

    // Results list
    resultsList = RecyclerView(themedContext).apply {
      layoutManager = LinearLayoutManager(themedContext)
      adapter = this@ClipboardSearchOverlayView.adapter
    }
    addView(resultsList, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

    updateFilterAppearance()
  }

  fun focusSearchField() {
    searchInput.requestFocus()
    searchInput.post {
      val imm = themedContext.getSystemService(InputMethodManager::class.java)
      imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }
  }

  fun hideKeyboard() {
    val imm = themedContext.getSystemService(InputMethodManager::class.java)
    imm?.hideSoftInputFromWindow(windowToken ?: searchInput.windowToken, 0)
  }

  fun updateResults(
    entries: List<ClipboardHistoryStore.Entry>,
    totalAll: Int,
    totalFavorites: Int,
  ) {
    generalButton.text = "${themedContext.getString(R.string.clipboard_history_general)} ($totalAll)"
    favoritesButton.text = "${themedContext.getString(R.string.clipboard_history_favorites)} ($totalFavorites)"
    updateFilterAppearance()
    val query = currentQuery.trim()
    statusView.text = when {
      entries.isEmpty() && query.isNotEmpty() ->
        themedContext.getString(R.string.clipboard_history_search_no_results, query)
      entries.isEmpty() ->
        themedContext.getString(R.string.clipboard_history_empty)
      else ->
        resources.getQuantityString(R.plurals.clipboard_history_results_count, entries.size, entries.size)
    }
    adapter.submit(entries)
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if ((event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE)
        && event.action == KeyEvent.ACTION_UP) {
      hideKeyboard()
      listener.onClose()
      return true
    }
    return super.dispatchKeyEvent(event)
  }

  private fun updateFilterAppearance() {
    val selectedBg = roundedBox(Color.rgb(46, 108, 184), Color.rgb(138, 188, 250))
    val idleBg = roundedBox(Color.rgb(34, 41, 51), Color.rgb(78, 92, 110))
    val selectedState = themedContext.getString(R.string.clipboard_history_selected_state)
    generalButton.background = if (!favoritesOnly) selectedBg else idleBg
    favoritesButton.background = if (favoritesOnly) selectedBg else idleBg
    generalButton.isSelected = !favoritesOnly
    favoritesButton.isSelected = favoritesOnly
    ViewCompat.setStateDescription(generalButton, if (!favoritesOnly) selectedState else null)
    ViewCompat.setStateDescription(favoritesButton, if (favoritesOnly) selectedState else null)
  }

  private fun styledButton(labelRes: Int): Button = Button(themedContext).apply {
    setText(labelRes)
    setTextColor(Color.WHITE)
    textSize = 14f
    isAllCaps = false
    background = roundedBox(Color.rgb(42, 52, 66), Color.rgb(92, 108, 128))
    val padH = (12 * dp).toInt()
    val padV = (8 * dp).toInt()
    setPadding(padH, padV, padH, padV)
    minHeight = (44 * dp).toInt()
  }

  private fun roundedBox(fillColor: Int, strokeColor: Int): GradientDrawable = GradientDrawable().apply {
    cornerRadius = 10 * dp
    setColor(fillColor)
    setStroke((1.5f * dp).toInt().coerceAtLeast(1), strokeColor)
  }

  private inner class ResultsAdapter : RecyclerView.Adapter<ResultHolder>() {
    private var data: List<ClipboardHistoryStore.Entry> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(entries: List<ClipboardHistoryStore.Entry>) {
      data = entries
      notifyDataSetChanged()
    }

    override fun getItemCount(): Int = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultHolder {
      val container = LinearLayout(themedContext).apply {
        orientation = VERTICAL
        isFocusable = true
        isClickable = true
        background = roundedBox(Color.rgb(30, 36, 46), Color.rgb(66, 78, 94))
        val pad = (14 * dp).toInt()
        setPadding(pad, pad, pad, pad)
        layoutParams = RecyclerView.LayoutParams(
          RecyclerView.LayoutParams.MATCH_PARENT,
          RecyclerView.LayoutParams.WRAP_CONTENT,
        ).apply {
          bottomMargin = (8 * dp).toInt()
        }
      }
      val primary = TextView(themedContext).apply {
        setTextColor(Color.WHITE)
        textSize = 17f
        setTypeface(null, Typeface.BOLD)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
      }
      val secondary = TextView(themedContext).apply {
        setTextColor(Color.rgb(190, 198, 210))
        textSize = 14f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
      }
      container.addView(primary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
      container.addView(secondary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = (4 * dp).toInt()
      })
      return ResultHolder(container, primary, secondary)
    }

    override fun onBindViewHolder(holder: ResultHolder, position: Int) {
      holder.bind(data[position], position, data.size)
    }
  }

  private inner class ResultHolder(
    private val row: LinearLayout,
    private val primary: TextView,
    private val secondary: TextView,
  ) : RecyclerView.ViewHolder(row) {
    fun bind(entry: ClipboardHistoryStore.Entry, position: Int, total: Int) {
      val preview = singleLinePreview(entry.text, 160)
      val favoriteTag = themedContext.getString(R.string.clipboard_history_favorite)
      if (entry.label.isNotEmpty()) {
        primary.text = entry.label
        secondary.text = if (entry.favorite) "$favoriteTag · $preview" else preview
        secondary.visibility = View.VISIBLE
      } else {
        primary.text = preview
        if (entry.favorite) {
          secondary.text = favoriteTag
          secondary.visibility = View.VISIBLE
        } else {
          secondary.visibility = View.GONE
        }
      }

      val spokenParts = mutableListOf<String>()
      if (entry.label.isNotEmpty()) spokenParts.add(entry.label)
      spokenParts.add(entry.text.take(300))
      if (entry.favorite) spokenParts.add(favoriteTag)
      spokenParts.add(themedContext.getString(R.string.template_list_position, position + 1, total))
      row.contentDescription = spokenParts.joinToString(". ")

      row.setOnClickListener {
        hideKeyboard()
        listener.onPrimarySelect(entry)
      }

      val clickLabel = themedContext.getString(
        if (canPaste) R.string.clipboard_history_action_paste else R.string.clipboard_history_action_copy
      )
      ViewCompat.replaceAccessibilityAction(
        row,
        AccessibilityActionCompat.ACTION_CLICK,
        clickLabel,
      ) { _, _ ->
        hideKeyboard()
        listener.onPrimarySelect(entry)
        true
      }

      if (canPaste) {
        val copyLabel = themedContext.getString(R.string.clipboard_history_action_copy)
        ViewCompat.replaceAccessibilityAction(
          row,
          AccessibilityActionCompat(ACTION_COPY_ID, copyLabel),
          copyLabel,
        ) { _, _ ->
          hideKeyboard()
          listener.onCopy(entry)
          true
        }
      } else {
        ViewCompat.removeAccessibilityAction(row, ACTION_COPY_ID)
      }

      val favLabel = themedContext.getString(
        if (entry.favorite) R.string.clipboard_history_action_remove_favorite
        else R.string.clipboard_history_action_add_favorite
      )
      ViewCompat.replaceAccessibilityAction(
        row,
        AccessibilityActionCompat(ACTION_FAVORITE_ID, favLabel),
        favLabel,
      ) { _, _ ->
        listener.onToggleFavorite(entry)
        true
      }

      val deleteLabel = themedContext.getString(R.string.clipboard_history_action_delete)
      ViewCompat.replaceAccessibilityAction(
        row,
        AccessibilityActionCompat(ACTION_DELETE_ID, deleteLabel),
        deleteLabel,
      ) { _, _ ->
        listener.onDelete(entry)
        true
      }
    }
  }

  companion object {
    private val ACTION_COPY_ID = R.id.accessibility_custom_action_0
    private val ACTION_FAVORITE_ID = R.id.accessibility_custom_action_1
    private val ACTION_DELETE_ID = R.id.accessibility_custom_action_2

    internal fun singleLinePreview(text: String, maxChars: Int): String =
      text.replace(Regex("\\s+"), " ").trim().take(maxChars)
  }
}
