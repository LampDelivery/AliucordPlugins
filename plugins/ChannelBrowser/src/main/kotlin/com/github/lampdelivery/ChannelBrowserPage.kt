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
                private val handler = android.os.Handler(android.os.Looper.getMainLooper())
            private var lastView: View? = null
        // Fetches the user's guild channel settings from Discord API
    fun fetchGuildSettings(): Map<String, Any>? {
        return try {
            val req = com.aliucord.Http.Request.newDiscordRNRequest("/users/@me/guilds/settings", "GET")
            val res = req.execute()
            if (res.ok()) {
                val json = res.text()
                @Suppress("UNCHECKED_CAST")
                com.google.gson.Gson().fromJson(json, Map::class.java) as? Map<String, Any>
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

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
            lastView = view
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

        // Fetch Discord's channel settings for the user
        val guildSettings = fetchGuildSettings()
        val hiddenChannels = mutableSetOf<String>()
        var channelsObj: Map<*, *>? = null
        if (guildSettings != null) {
            val guildObj = (guildSettings["guilds"] as? Map<*, *>)?.get(guildId.toString()) as? Map<*, *>
            channelsObj = guildObj?.get("channels") as? Map<*, *>
            if (channelsObj != null) {
                for ((cid, checked) in channelsObj) {
                    if (checked == false) hiddenChannels.add(cid.toString())
                }
            }
        }
        // ...existing code...

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
            val children = if (catId != null) channelsByCategory[catId] else null
            // Determine category checkbox state
            val childIds = children?.mapNotNull { ch ->
                try { ch.javaClass.getDeclaredField("id").apply { isAccessible = true }.get(ch)?.toString() } catch (_: Throwable) { null }
            } ?: emptyList()
            val checkedCount = childIds.count { id -> !hiddenChannels.contains(id) }
            val allChecked = checkedCount == childIds.size && childIds.isNotEmpty()
            val noneChecked = checkedCount == 0
            val catCb = CheckBox(ctx)
            catCb.isChecked = allChecked
            // Indeterminate state (visual only)
            if (!allChecked && !noneChecked) catCb.buttonDrawable?.alpha = 128 else catCb.buttonDrawable?.alpha = 255
            catCb.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            catCb.setOnCheckedChangeListener { _, checked ->
                // PATCH all child channels
                if (catId != null && children != null) {
                    // Update local hiddenChannels set
                    childIds.forEach { chId ->
                        if (checked) hiddenChannels.remove(chId) else hiddenChannels.add(chId)
                    }
                    // Build PATCH map: only hidden channels as false
                    val patchMap = hiddenChannels.associateWith { false }
                    val patchBody = mapOf(
                        "guild_id" to guildId,
                        "channels" to patchMap
                    )
                    try {
                        val req = com.aliucord.Http.Request.newDiscordRNRequest(
                            "/users/@me/guilds/settings",
                            "PATCH"
                        )
                        req.executeWithJson(patchBody)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    // Refresh UI after PATCH with delay
                    lastView?.let { v ->
                        handler.postDelayed({ onViewBound(v) }, 200)
                    }
                }
            }
            catRow.addView(catTv)
            catRow.addView(catCb)
            catRow.addTo(linearLayout)
            if (children != null) {
                for (ch in children) {
                    addChannelRowReflect(ch, guildId, ctx, nameField, hiddenChannels, channelsObj, allChannels)
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
                addChannelRowReflect(ch, guildId, ctx, nameField, hiddenChannels, channelsObj, allChannels)
            }
        }
    }

    fun addChannelRowReflect(
        ch: com.discord.api.channel.Channel,
        guildId: Long,
        ctx: Context,
        nameField: java.lang.reflect.Field,
        hiddenChannels: MutableSet<String>,
        channelsObj: Map<*, *>?,
        allChannels: Map<Long, com.discord.api.channel.Channel>
    )
    {
        val chName = try { nameField.get(ch) as? String ?: "Unnamed Channel" } catch (_: Throwable) { "Unnamed Channel" }
        val channelKey = "$chName-$guildId"
        val chId = try { ch.javaClass.getDeclaredField("id").apply { isAccessible = true }.get(ch)?.toString() } catch (_: Throwable) { null }
        val isChecked = chId != null && !hiddenChannels.contains(chId)

        var suppressChannelListener = BooleanArray(1) { false }

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
            alpha = if (!isChecked) 0.5f else 1.0f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val cb = CheckBox(ctx)
        cb.isChecked = isChecked
        cb.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        cb.setOnCheckedChangeListener { buttonView, checked ->
            if (suppressChannelListener[0]) return@setOnCheckedChangeListener
            val previousState = !checked
            val doAction = {
                // PATCH request to update Discord settings
                if (chId != null) {
                    // Update local hiddenChannels set
                    if (checked) hiddenChannels.remove(chId) else hiddenChannels.add(chId)
                    // Build PATCH map: only hidden channels as false
                    val patchMap = hiddenChannels.associateWith { false }
                    val patchBody = mapOf(
                        "guild_id" to guildId,
                        "channels" to patchMap
                    )
                    try {
                        val req = com.aliucord.Http.Request.newDiscordRNRequest(
                            "/users/@me/guilds/settings",
                            "PATCH"
                        )
                        req.executeWithJson(patchBody)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                // Refresh UI after PATCH with delay
                lastView?.let { v ->
                    handler.postDelayed({ onViewBound(v) }, 200)
                }
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
                        suppressChannelListener[0] = true
                        buttonView.isChecked = previousState
                        suppressChannelListener[0] = false
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
        row.addView(tv)
        row.addView(cb)
        row.alpha = if (!isChecked) 0.5f else 1.0f
        row.addTo(linearLayout)
    }

}
