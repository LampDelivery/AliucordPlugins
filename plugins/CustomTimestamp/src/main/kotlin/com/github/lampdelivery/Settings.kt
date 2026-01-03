package com.github.lampdelivery

import android.view.View
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.discord.views.CheckedSetting
import android.text.Editable
import android.text.TextWatcher
import com.aliucord.views.TextInput
import com.aliucord.R

class CustomTimestampSettings(private val settings: SettingsAPI) : SettingsPage() {

    private fun addTextInput(hint: String, initial: String, onChange: (String) -> Unit) {
        val ctx = requireContext()
        val textInput = TextInput(ctx)
        textInput.setHint(hint)
        val mainTextColor = textInput.getEditText().currentTextColor
        textInput.getEditText().setHintTextColor(mainTextColor)
        textInput.getEditText().setText(initial)
        textInput.getEditText().setSingleLine(false)
        textInput.getEditText().maxLines = 3
        textInput.getEditText().isSingleLine = false
        textInput.getEditText().addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onChange(s?.toString() ?: "")
            }
        })
        addView(textInput)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val todayReplacement = settings.getString("todayReplacement", "")
        val formatOptions = listOf(
            "MMM dd, yyyy",
            "yyyy-MM-dd",
            "yyyy/MM/dd",
            "dd-MM-yyyy",
            "yy/MM/dd",
            "dd-MM-yy",
            "MM/dd/yyyy",
            "dd MMM yyyy"
        )
        val formatLabels = listOf(
            "Mon DD, YYYY (Default)",
            "YYYY-MM-DD (Numerical)",
            "YYYY/MM/DD",
            "DD-MM-YYYY",
            "YY/MM/DD",
            "DD-MM-YY",
            "MM/DD/YYYY",
            "DD Mon YYYY"
        )
        val defaultFormat = "MMM dd, yyyy"
        val currentFormat = settings.getString("customDateFormat", defaultFormat)
        var currentIndex = formatOptions.indexOf(currentFormat).let { if (it == -1) formatOptions.indexOf(defaultFormat) else it }
        val ctx = requireContext()
        val dateFormatSelector = com.github.lampdelivery.Selector(ctx).apply {
            setLabel("Date Format")
            setValue(formatLabels[currentIndex])
            setSelectorClickListener(View.OnClickListener {
                val dialog = com.github.lampdelivery.SelectDialog()
                dialog.setItems(formatLabels)
                dialog.setTitle("Select Date Format")
                dialog.setOnResultListener { which ->
                    currentIndex = which
                    setValue(formatLabels[currentIndex])
                    settings.setString("customDateFormat", formatOptions[currentIndex])
                    Utils.promptRestart("Restart required to apply changes.")
                }
                dialog.show((this@CustomTimestampSettings.parentFragmentManager ?: return@OnClickListener), "date_format_selector")
            })
        }
        addView(dateFormatSelector)

        addTextInput(
            hint = "Today Replacement (leave blank to remove)",
            initial = todayReplacement
        ) {
            settings.setString("todayReplacement", it)
            Utils.promptRestart("Restart required to apply changes.")
        }
        val space = android.widget.Space(requireContext())
        space.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            (12 * resources.displayMetrics.density).toInt()
        )
        addView(space)
        val yesterdayReplacement = settings.getString("yesterdayReplacement", "")
        addTextInput(
            hint = "Yesterday Replacement (leave blank to remove)",
            initial = yesterdayReplacement
        ) {
            settings.setString("yesterdayReplacement", it)
            Utils.promptRestart("Restart required to apply changes.")
        }

        val infoText = android.widget.TextView(requireContext()).apply {
            text = "Tip: Use %date% in the box to insert the formatted date."
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
        }
        addView(infoText)

        addView(
            Utils.createCheckedSetting(
                ctx,
                CheckedSetting.ViewType.SWITCH,
                "Hide 'Today at' prefix",
                "Hides the 'Today at' prefix from timestamps"
            ).apply {
                isChecked = settings.getBool("hideToday", false)
                setOnCheckedListener {
                    settings.setBool("hideToday", it)
                    Utils.promptRestart("Restart required to apply changes.")
                }
            }
        )

        addView(
            Utils.createCheckedSetting(
                ctx,
                CheckedSetting.ViewType.SWITCH,
                "Hide 'Yesterday at' prefix",
                "Hides the 'Yesterday at' prefix from timestamps"
            ).apply {
                isChecked = settings.getBool("hideYesterday", false)
                setOnCheckedListener {
                    settings.setBool("hideYesterday", it)
                    Utils.promptRestart("Restart required to apply changes.")
                }
            }
        )

        addView(
            Utils.createCheckedSetting(
                ctx,
                CheckedSetting.ViewType.SWITCH,
                "24 Hour Format",
                "Use 24 hour time instead of AM/PM"
            ).apply {
                isChecked = settings.getBool("use24Hour", false)
                setOnCheckedListener {
                    settings.setBool("use24Hour", it)
                    Utils.promptRestart("Restart required to apply changes.")
                }
            }
        )
    }
}
