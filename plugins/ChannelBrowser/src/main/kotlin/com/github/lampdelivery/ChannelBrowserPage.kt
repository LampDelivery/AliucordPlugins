package com.github.lampdelivery

import android.annotation.SuppressLint
import com.aliucord.utils.ViewUtils.addTo
import android.view.Gravity
import android.view.View
import android.content.Context
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.ContextCompat
import com.aliucord.Constants
import com.aliucord.api.SettingsAPI
import com.lytefast.flexinput.R
import com.aliucord.fragments.SettingsPage
import com.discord.stores.StoreStream


class ChannelBrowserPage(val settings: SettingsAPI, val channels: MutableList<String>) : SettingsPage() {
    fun themeAlertDialogText(dialog: AlertDialog, ctx: Context) {
        try {
            val textColorRes = R.c.primary_dark
            val textColor = ContextCompat.getColor(ctx, textColorRes)
            dialog.window?.decorView?.post {
                val messageId = android.R.id.message
                val messageView = dialog.findViewById<TextView>(messageId)
                messageView?.setTextColor(textColor)
                messageView?.setTypeface(ResourcesCompat.getFont(ctx, Constants.Fonts.whitney_medium))
            }
        } catch (_: Throwable) {}
    }

    @SuppressLint("SetTextI18n")
    override fun onViewBound(view: View) {
        super.onViewBound(view)

        setActionBarTitle("Channel Browser")
        setActionBarSubtitle("Manage Channels")

        val ctx = context ?: return
        val guildId = StoreStream.getGuildSelected().selectedGuildId
        val allChannels = StoreStream.getChannels().getChannelsForGuild(guildId)
        val typeField = com.discord.api.channel.Channel::class.java.getDeclaredField("type").apply { isAccessible = true }
        val parentIdField = com.discord.api.channel.Channel::class.java.getDeclaredField("parentId").apply { isAccessible = true }
        val idField = com.discord.api.channel.Channel::class.java.getDeclaredField("id").apply { isAccessible = true }
        val nameField = com.discord.api.channel.Channel::class.java.getDeclaredField("name").apply { isAccessible = true }
        
        val channelNames = allChannels.values.map {
            try { nameField.get(it) as? String ?: "Unnamed Channel" } catch (_: Throwable) { "Unnamed Channel" }
        }
        val unnamedCount = channelNames.count { it == "Unnamed Channel" }
        val totalCount = channelNames.size
        val threshold = 3
        val mostlyUnnamed = totalCount > 0 && unnamedCount >= totalCount * 2 / 3
        if (guildId == 0L) {
            activity?.finish()
            return
        } else if (totalCount < threshold || mostlyUnnamed) {
            linearLayout.removeAllViews()
            return
        }

        val categories = allChannels.values.filter {
            try { typeField.getInt(it) == 4 } catch (_: Throwable) { false }
        }
        val channelsByCategory = mutableMapOf<Long, MutableList<com.discord.api.channel.Channel>>()
        val uncategorized = mutableListOf<com.discord.api.channel.Channel>()

        for (ch in allChannels.values) {
            val type = try { typeField.getInt(ch) } catch (_: Throwable) { -1 }
            if (type == 4) continue
            val parentId = try { parentIdField.get(ch) as? Long } catch (_: Throwable) { null }
            if (parentId != null && allChannels.containsKey(parentId)) {
                channelsByCategory.getOrPut(parentId) { mutableListOf() }.add(ch)
            } else {
                uncategorized.add(ch)
            }
        }


        for (cat in categories) {
            val catName = try { nameField.get(cat) as? String ?: "Unnamed Category" } catch (_: Throwable) { "Unnamed Category" }
            val catId = try { idField.get(cat) as? Long } catch (_: Throwable) { null }
            val catRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 24, 0, 8)
                gravity = Gravity.CENTER_VERTICAL
            }
            val catTv = TextView(ctx, null, 0, R.i.UiKit_Settings_Item).apply {
                text = catName
                typeface = ResourcesCompat.getFont(ctx, Constants.Fonts.whitney_bold)
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val categoryChildren = if (catId != null) channelsByCategory[catId] else null
            val allFollowed = categoryChildren?.all { ch ->
                val chName = try { nameField.get(ch) as? String ?: "Unnamed Channel" } catch (_: Throwable) { "Unnamed Channel" }
                val channelKey = "$chName-$guildId"
                channelKey !in channels
            } ?: true

            val followCb = CheckBox(ctx)
            followCb.apply {
                isChecked = allFollowed
                text = ""
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                var suppressListener = false
                setOnCheckedChangeListener { buttonView, checked ->
                    if (suppressListener) return@setOnCheckedChangeListener
                    val previousState = !checked
                    val doAction = {
                        val children = if (catId != null) channelsByCategory[catId] else null
                        if (children != null) {
                            for (ch in children) {
                                val chName = try { nameField.get(ch) as? String ?: "Unnamed Channel" } catch (_: Throwable) { "Unnamed Channel" }
                                val channelKey = "$chName-$guildId"
                                if (!checked) {
                                    if (channelKey !in channels) channels += channelKey
                                } else {
                                    channels -= channelKey
                                }
                            }
                            settings.setObject("channels", channels)
                            linearLayout.removeAllViews()
                            onViewBound(view)
                        }
                    }
                    if (settings.getBool("confirmActions", false)) {
                        val textColorRes = R.c.primary_dark
                        val textColor = ContextCompat.getColor(ctx, textColorRes)
                        val customTitle = TextView(ctx).apply {
                            text = if (!checked) "Unfollow Category" else "Follow Category"
                            setTextColor(textColor)
                            typeface = ResourcesCompat.getFont(ctx, Constants.Fonts.whitney_bold)
                            textSize = 20f
                            setPadding(32, 32, 32, 16)
                        }
                        val themedDialog = AlertDialog.Builder(ctx)
                            .setCustomTitle(customTitle)
                            .setMessage("Are you sure you want to ${if (!checked) "unfollow" else "follow"} this category?")
                            .setPositiveButton("Yes") { _: android.content.DialogInterface, _: Int -> doAction() }
                            .setNegativeButton("No") { dialog, _ ->
                                suppressListener = true
                                buttonView.isChecked = previousState
                                suppressListener = false
                                dialog.dismiss()
                            }
                            .setOnCancelListener {
                                suppressListener = true
                                buttonView.isChecked = previousState
                                suppressListener = false
                            }
                            .create()
                        themedDialog.show()
                        themeAlertDialogText(themedDialog, ctx)
                    } else {
                        doAction()
                    }
                }
            }
            val followLabel = TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                text = "Follow Category"
                typeface = ResourcesCompat.getFont(ctx, Constants.Fonts.whitney_medium)
                textSize = 13f
                setPadding(8, 0, 0, 0)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            catRow.addView(catTv)
            catRow.addView(followCb)
            catRow.addView(followLabel)
            catRow.addTo(linearLayout)
            val children = if (catId != null) channelsByCategory[catId] else null
            if (children != null) {
                for (ch in children) {
                    addChannelRowReflect(ch, guildId, ctx, nameField)
                }
            }
        }

        if (uncategorized.isNotEmpty()) {
            TextView(ctx, null, 0, R.i.UiKit_Settings_Item).apply {
                text = "Uncategorized"
                typeface = ResourcesCompat.getFont(ctx, Constants.Fonts.whitney_bold)
                textSize = 15f
                setPadding(0, 24, 0, 8)
            }.addTo(linearLayout)
            for (ch in uncategorized) {
                addChannelRowReflect(ch, guildId, ctx, nameField)
            }
        }
    }

    fun addChannelRowReflect(
        ch: com.discord.api.channel.Channel,
        guildId: Long,
        ctx: Context,
        nameField: java.lang.reflect.Field
    ) {
        val chName = try { nameField.get(ch) as? String ?: "Unnamed Channel" } catch (_: Throwable) { "Unnamed Channel" }
        val channelKey = "$chName-$guildId"
        val isNuked = channelKey in channels

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 8)
            gravity = Gravity.CENTER_VERTICAL
        }
        val tv = TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            text = chName
            typeface = ResourcesCompat.getFont(ctx, Constants.Fonts.whitney_medium)
            textSize = 14f
            val colorRes = try { R.c.primary_dark } catch (_: Throwable) { android.R.color.black }
            val color = try { ContextCompat.getColor(ctx, colorRes) } catch (_: Throwable) { 0xFF000000.toInt() }
            setTextColor(color)
            alpha = if (isNuked) 0.5f else 1.0f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val cb = CheckBox(ctx).apply {
            isChecked = !isNuked
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            val suppressListener = false
            setOnCheckedChangeListener { buttonView, checked ->
                if (suppressListener) return@setOnCheckedChangeListener
                val previousState = !checked
                val doAction = {
                    if (!checked) {
                        if (channelKey !in channels) channels += channelKey
                    } else {
                        channels -= channelKey
                    }
                    settings.setObject("channels", channels)
                    row.alpha = if (!checked) 0.5f else 1.0f
                }
                if (settings.getBool("confirmActions", false)) {
                    val textColor = ContextCompat.getColor(ctx, R.c.primary_dark)
                    val customTitle = TextView(ctx).apply {
                        text = if (!checked) "Hide Channel" else "Restore Channel"
                        setTextColor(textColor)
                        typeface = ResourcesCompat.getFont(ctx, Constants.Fonts.whitney_bold)
                        textSize = 20f
                        setPadding(32, 32, 32, 16)
                    }
                    val themedDialog = AlertDialog.Builder(ctx)
                        .setCustomTitle(customTitle)
                        .setMessage("Are you sure you want to ${if (!checked) "hide" else "restore"} this channel?")
                        .setPositiveButton("Yes") { _: android.content.DialogInterface, _: Int -> doAction() }
                        .setNegativeButton("No") { _: android.content.DialogInterface, _ ->
                            buttonView.isChecked = previousState
                        }
                        .setOnCancelListener {
                            buttonView.isChecked = previousState
                        }
                        .create()
                    themedDialog.show()
                    themeAlertDialogText(themedDialog, ctx)
                } else {
                    doAction()
                }
            }
        }
        row.addView(tv)
        row.addView(cb)
        row.alpha = if (isNuked) 0.5f else 1.0f
        row.addTo(linearLayout)
    }

}
