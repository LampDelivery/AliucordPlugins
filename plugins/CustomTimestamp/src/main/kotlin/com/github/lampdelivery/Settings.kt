package com.github.lampdelivery

import android.view.View
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.discord.views.CheckedSetting

class CustomTimestampSettings(private val settings: SettingsAPI) : SettingsPage() {

    override fun onViewBound(view: View) {
        super.onViewBound(view)

        setActionBarTitle("Custom Timestamp Settings")
        setActionBarSubtitle("Customize timestamp display")
        val ctx = requireContext()

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
                }
                dialog.show((this@CustomTimestampSettings.parentFragmentManager ?: return@OnClickListener), "date_format_selector")
            })
        }
        addView(dateFormatSelector)
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
                }
            }
        )
    }
}
