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

class ChannelBrowserSettings(private val settings: SettingsAPI) : SettingsPage() {
    private var SettingsAPI.showHeader by settings.delegate(true)
    private var SettingsAPI.confirmActions by settings.delegate(true)

    override fun onViewBound(view: View) {
        super.onViewBound(view)

        setActionBarTitle("Channel Browser Settings")
        setActionBarSubtitle("Customize behaviour")

        TextView(context, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            text = MDUtils.render("Configure Channel Browser options.")
            typeface = ResourcesCompat.getFont(context, Constants.Fonts.whitney_medium)
            textSize = 14f
        }.addTo(linearLayout)

        TextView(context, null, 0, R.i.UiKit_Settings_Item).apply {
            text = MDUtils.render("Show Manage Channels header: ${if (settings.showHeader) "On" else "Off"}")
            typeface = ResourcesCompat.getFont(context, Constants.Fonts.whitney_medium)
            textSize = 16f
            setOnClickListener {
                settings.showHeader = !settings.showHeader
                text = MDUtils.render("Show Manage Channels header: ${if (settings.showHeader) "On" else "Off"}")
                Utils.showToast(if (settings.showHeader) "Header enabled" else "Header disabled")
            }
        }.addTo(linearLayout)

        TextView(context, null, 0, R.i.UiKit_Settings_Item).apply {
            text = MDUtils.render("Confirm channel actions: ${if (settings.confirmActions) "On" else "Off"}")
            typeface = ResourcesCompat.getFont(context, Constants.Fonts.whitney_medium)
            textSize = 16f
            setOnClickListener {
                settings.confirmActions = !settings.confirmActions
                text = MDUtils.render("Confirm channel actions: ${if (settings.confirmActions) "On" else "Off"}")
                Utils.showToast(if (settings.confirmActions) "Confirmations enabled" else "Confirmations disabled")
            }
        }.addTo(linearLayout)
    }
}
