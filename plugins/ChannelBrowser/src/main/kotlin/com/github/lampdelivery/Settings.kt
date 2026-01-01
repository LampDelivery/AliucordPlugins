package com.github.lampdelivery

import android.view.View
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.aliucord.Constants
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.settings.delegate
import com.aliucord.utils.MDUtils
import com.aliucord.utils.ViewUtils.addTo
import com.lytefast.flexinput.R
import com.discord.views.CheckedSetting

class ChannelBrowserSettings(private val settings: SettingsAPI) : SettingsPage() {

    override fun onViewBound(view: View) {
        super.onViewBound(view)

        setActionBarTitle("Channel Browser Settings")
        setActionBarSubtitle("Customize behaviour")
        val ctx = requireContext()

        addView(
            Utils.createCheckedSetting(
                ctx,
                CheckedSetting.ViewType.SWITCH,
                "Enable header (EXPERIMENTAL)",
                "Show a header in the channel list to mimic RN"
            ).apply {
                isChecked = settings.getBool("showHeader", false)
                setOnCheckedListener {
                    settings.setBool("showHeader", it)
                    Utils.promptRestart()
                }
            }
        )

        addView(
            Utils.createCheckedSetting(
                ctx,
                CheckedSetting.ViewType.SWITCH,
                "Confirm channel actions",
                "Require confirmation to modify channel list"
            ).apply {
                isChecked = settings.getBool("confirmActions", false)
                setOnCheckedListener {
                    settings.setBool("confirmActions", it)
                    Utils.promptRestart()
                }
            }
        )
    }
}
