package com.github.lampdelivery

import android.view.View
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.discord.views.CheckedSetting

class ChannelBrowserProtoSettings(private val settings: SettingsAPI) : SettingsPage() {

    override fun onViewBound(view: View) {
        super.onViewBound(view)

        setActionBarTitle("Channel Browser Settings")
        setActionBarSubtitle(null) 
        val ctx = requireContext()

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
                }
            }
        )
    }
}
