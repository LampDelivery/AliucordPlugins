package com.github.lampdelivery

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook

@AliucordPlugin

class DebugLogEnhancer : Plugin() {
    override fun start(context: Context) {
        try {
            val cls = Class.forName("com.discord.widgets.debugging.WidgetDebugging")
            val onViewBound = cls.getDeclaredMethod("onViewBound", View::class.java)
            patcher.patch(onViewBound, Hook { cf ->
                val root = cf.args?.firstOrNull { it is View } as? View ?: return@Hook
                val res = root.resources
                // Find the header TextView (with text "Debug") safely
                fun findTextViewWithText(v: View, text: String): TextView? {
                    if (v is TextView && v.text?.toString()?.trim()?.equals(text, true) == true) return v
                    if (v is ViewGroup) {
                        for (i in 0 until v.childCount) {
                            val found = findTextViewWithText(v.getChildAt(i), text)
                            if (found != null) return found
                        }
                    }
                    return null
                }
                fun findRecyclerView(v: View): RecyclerView? {
                    if (v is RecyclerView) return v
                    if (v is ViewGroup) {
                        for (i in 0 until v.childCount) {
                            val found = findRecyclerView(v.getChildAt(i))
                            if (found != null) return found
                        }
                    }
                    return null
                }
                // Find the RecyclerView for the logs
                val recycler = findRecyclerView(root)
                logger.info("DebugLogEnhancer: recycler found? ${recycler != null}")
                if (recycler != null) {
                    val parent = recycler.parent as? ViewGroup
                    if (parent != null && parent.findViewWithTag<EditText>("debug_log_search_bar") == null) {
                        val ctx = root.context
                        val margin = (ctx.resources.displayMetrics.density * 8).toInt()
                        val toolbarHeight = (ctx.resources.displayMetrics.density * 56).toInt() // Typical action bar height
                        // Use Discord's dialog background color for theming (like ChannelBrowser)
                        // Try to match the parent/root background color for theming
                        val parentBgColor = try {
                            val bg = (parent?.background as? android.graphics.drawable.ColorDrawable)?.color
                            bg ?: 0xFF23272A.toInt()
                        } catch (_: Throwable) { 0xFF23272A.toInt() }
                        // Create a container for the search bar with background and padding
                        val searchContainer = android.widget.FrameLayout(ctx).apply {
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                topMargin = toolbarHeight
                                leftMargin = margin
                                rightMargin = margin
                            }
                            setPadding(margin, margin, margin, margin)
                            setBackgroundColor(parentBgColor)
                            elevation = ctx.resources.displayMetrics.density * 2
                        }
                        val searchBar = EditText(ctx).apply {
                            hint = "Search logs..."
                            tag = "debug_log_search_bar"
                            isSingleLine = true
                            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            try {
                                val bgAttr = intArrayOf(android.R.attr.editTextBackground)
                                val ta = ctx.obtainStyledAttributes(bgAttr)
                                background = ta.getDrawable(0)
                                ta.recycle()
                            } catch (_: Throwable) {}
                            try {
                                val textColor = com.discord.utilities.color.ColorCompat.getThemedColor(ctx, com.lytefast.flexinput.R.b.colorInteractiveNormal)
                                setTextColor(textColor)
                                setHintTextColor(textColor and 0x88FFFFFF.toInt())
                            } catch (_: Throwable) {}
                        }
                        // Finder controls: up/down buttons and result count
                        val finderLayout = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        }
                        val btnUp = android.widget.ImageButton(ctx).apply {
                            setImageResource(android.R.drawable.arrow_up_float)
                            contentDescription = "Previous match"
                        }
                        val btnDown = android.widget.ImageButton(ctx).apply {
                            setImageResource(android.R.drawable.arrow_down_float)
                            contentDescription = "Next match"
                        }
                        val resultCount = TextView(ctx).apply {
                            text = "0/0"
                            setPadding(16, 0, 0, 0)
                            try {
                                val textColor = com.discord.utilities.color.ColorCompat.getThemedColor(ctx, com.lytefast.flexinput.R.b.colorInteractiveNormal)
                                setTextColor(textColor)
                            } catch (_: Throwable) {}
                        }
                        finderLayout.addView(btnUp)
                        finderLayout.addView(btnDown)
                        finderLayout.addView(resultCount)
                        val finderContainer = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        }
                        finderContainer.addView(searchBar)
                        finderContainer.addView(finderLayout)
                        searchContainer.addView(finderContainer)
                        // Insert the searchContainer above the RecyclerView, but not at index 0 if possible
                        val recyclerIndex = parent.indexOfChild(recycler)
                        val insertIndex = if (recyclerIndex == 0 && parent.childCount > 1) 1 else recyclerIndex
                        parent.addView(searchContainer, insertIndex)

                        // Finder logic
                        var matchIndexes = listOf<Int>()
                        var currentMatch = 0
                        fun clearHighlights() {
                            val adapter = recycler.adapter ?: return
                            for (i in 0 until adapter.itemCount) {
                                val vh = recycler.findViewHolderForAdapterPosition(i)
                                val item = vh?.itemView
                                val msgView = item?.findViewById<TextView?>(res.getIdentifier("log_message", "id", root.context.packageName))
                                msgView?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            }
                        }
                        fun highlightMatch(index: Int) {
                            clearHighlights()
                            if (index in matchIndexes.indices) {
                                val matchPos = matchIndexes[index]
                                recycler.smoothScrollToPosition(matchPos)
                                recycler.post {
                                    val vh = recycler.findViewHolderForAdapterPosition(matchPos)
                                    val item = vh?.itemView
                                    val msgView = item?.findViewById<TextView?>(res.getIdentifier("log_message", "id", root.context.packageName))
                                    msgView?.setBackgroundColor(0xFF4444AA.toInt()) // Highlight color
                                }
                            }
                        }
                        fun updateFinder(query: String) {
                            val lowerQuery = query.trim().lowercase()
                            val adapter = recycler.adapter ?: return
                            matchIndexes = (0 until adapter.itemCount).filter { i ->
                                val vh = recycler.findViewHolderForAdapterPosition(i)
                                val item = vh?.itemView
                                val msgView = item?.findViewById<TextView?>(res.getIdentifier("log_message", "id", root.context.packageName))
                                msgView != null && lowerQuery.isNotEmpty() && (msgView.text?.toString()?.lowercase()?.contains(lowerQuery) == true)
                            }
                            if (matchIndexes.isEmpty()) {
                                resultCount.text = "0/0"
                                currentMatch = 0
                                clearHighlights()
                            } else {
                                currentMatch = 0
                                resultCount.text = "1/${matchIndexes.size}"
                                highlightMatch(currentMatch)
                            }
                        }
                        btnUp.setOnClickListener {
                            if (matchIndexes.isNotEmpty()) {
                                currentMatch = if (currentMatch - 1 < 0) matchIndexes.size - 1 else currentMatch - 1
                                resultCount.text = "${currentMatch + 1}/${matchIndexes.size}"
                                highlightMatch(currentMatch)
                            }
                        }
                        btnDown.setOnClickListener {
                            if (matchIndexes.isNotEmpty()) {
                                currentMatch = (currentMatch + 1) % matchIndexes.size
                                resultCount.text = "${currentMatch + 1}/${matchIndexes.size}"
                                highlightMatch(currentMatch)
                            }
                        }
                        // Add Tab key support: insert tab character instead of moving focus
                        searchBar.setOnKeyListener { v, keyCode, event ->
                            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_TAB) {
                                val edit = searchBar.text
                                val start = searchBar.selectionStart
                                val end = searchBar.selectionEnd
                                edit.replace(Math.min(start, end), Math.max(start, end), "\t")
                                // Move cursor after the tab
                                val newPos = Math.min(start, end) + 1
                                searchBar.setSelection(newPos)
                                true // consume event
                            } else {
                                false
                            }
                        }
                        searchBar.addTextChangedListener(object : android.text.TextWatcher {
                            override fun afterTextChanged(s: android.text.Editable?) {
                                val query = s?.toString() ?: ""
                                updateFinder(query)
                            }
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        })
                    }
                }
            })
            // Patch menu filter logic (stub, ready for custom logic)
            // You can expand this to add more menu items or override filter behavior
            // Example: patch setActionBarOptionsMenu or menu callbacks if needed
        } catch (_: Throwable) {}
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
