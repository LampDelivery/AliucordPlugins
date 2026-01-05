package com.github.lampdelivery

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.content.Context
import android.widget.*
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.ContextCompat
import com.aliucord.Constants
import com.aliucord.api.SettingsAPI
import com.lytefast.flexinput.R
import com.aliucord.fragments.SettingsPage
import com.discord.stores.StoreStream

import com.github.lampdelivery.ChannelBrowserProtoSettingsManager
import com.discord.utilities.rx.ObservableExtensionsKt

class ChannelBrowserProtoPage(
    val settings: SettingsAPI,
    val channels: MutableList<String>,
    val protoSettingsManager: ChannelBrowserProtoSettingsManager
) : SettingsPage() {
    private fun getCurrentGuildSettings(guildId: Long): Map<String, Any>? {
        return try {
            val store = com.discord.stores.StoreStream.getUserGuildSettings()
            val settingsMap = store.getGuildSettings() 
            val settings = settingsMap[guildId]
            logger.debug("[getCurrentGuildSettings] Raw settings for guild $guildId: $settings (class: ${settings?.javaClass?.name})")
            if (settings == null) return null
            val field = settings.javaClass.declaredFields.find { it.name == "channelOverrides" }
            field?.isAccessible = true
            val overridesList = field?.get(settings) as? List<*>
            if (overridesList == null) {
                logger.debug("[getCurrentGuildSettings] channelOverrides field not found or not a List")
                return null
            }
            val overridesMap = mutableMapOf<String, Int>()
            for (override in overridesList) {
                if (override == null) continue
                val chIdField = override.javaClass.declaredFields.find { it.name == "channelId" }
                val flagsField = override.javaClass.declaredFields.find { it.name == "flags" }
                chIdField?.isAccessible = true
                flagsField?.isAccessible = true
                val chId = chIdField?.get(override)?.toString()
                val flags = (flagsField?.get(override) as? Int) ?: 0
                if (chId != null) overridesMap[chId] = flags
            }
            mapOf("channel_overrides" to overridesMap)
        } catch (e: Throwable) {
            logger.error("[getCurrentGuildSettings] Exception: ${e.message}", e)
            null
        }
    }

    fun addChannelRowReflect(
        ch: com.discord.api.channel.Channel,
        guildId: Long,
        ctx: Context,
        nameField: java.lang.reflect.Field,
        channelOverrides: Map<String, Int>,
        linearLayout: LinearLayout,
        allChannelsRaw: Map<Long, com.discord.api.channel.Channel>,
        grayOut: Boolean = false,
        parentCategoryFollowed: Boolean = false
    ) {
        val chName = try {
            nameField.get(ch) as? String ?: "Unnamed Channel"
        } catch (_: Throwable) {
            "Unnamed Channel"
        }
        val chId = try {
            ch.javaClass.getDeclaredField("id").apply { isAccessible = true }.get(ch)?.toString()
        } catch (_: Throwable) {
            null
        }
        val flags = channelOverrides[chId] ?: 4096
        val isChecked = (flags and 4096) != 0
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
            val color = try {
                if (isChecked) {
                    com.discord.utilities.color.ColorCompat.getThemedColor(ctx, com.lytefast.flexinput.R.b.colorInteractiveMuted)
                } else {
                    com.discord.utilities.color.ColorCompat.getThemedColor(ctx, com.lytefast.flexinput.R.b.colorInteractiveNormal)
                }
            } catch (_: Throwable) {
                if (isChecked) 0xFF222222.toInt() else 0xFF888888.toInt()
            }
            setTextColor(color)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            try {
                setCompoundDrawablesWithIntrinsicBounds(
                    ctx.getDrawable(R.e.ic_channel_text),
                    null, null, null
                )
                val scale = ctx.resources.displayMetrics.density
                compoundDrawablePadding = (8 * scale).toInt()
            } catch (_: Throwable) {}
        }
        val cb = CheckBox(ctx)
        cb.isChecked = if (parentCategoryFollowed) true else isChecked
        cb.isEnabled = !parentCategoryFollowed
        cb.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        cb.setOnCheckedChangeListener { buttonView, checked ->
            if (suppressChannelListener[0]) return@setOnCheckedChangeListener
            val previousState = !checked
            val doAction = {
                if (chId != null) {
                    suppressChannelListener[0] = true
                    Thread {
                        val overrideObj = mutableMapOf<String, Any>(
                            "channel_id" to chId,
                            "flags" to if (checked) 4096 else 0
                        )
                        protoSettingsManager.patchGuildSettingsAsync(guildId, listOf(overrideObj))
                        handler.post {
                            cb.isChecked = checked
                            row.alpha = if (!checked || parentCategoryFollowed) 0.5f else 1.0f
                            suppressChannelListener[0] = false
                        }
                    }.start()
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
        row.alpha = if (!isChecked || grayOut) 0.5f else 1.0f
        linearLayout.addView(row)
    }

    private val logger = com.aliucord.Logger("ChannelBrowserProto")
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastView: View? = null

    private fun deepCopyOverrides(orig: Any?): MutableList<MutableMap<String, Any>> {
        val result = mutableListOf<MutableMap<String, Any>>()
        if (orig is List<*>) {
            for (item in orig) {
                if (item is Map<*, *>) {
                    val map = HashMap<String, Any>()
                    for ((k, v) in item) {
                        if (k is String && v != null) {
                            map[k] = v
                        }
                    }
                    result.add(map)
                }
            }
        }
        return result
    }

    private fun themeAlertDialogText(dialog: AlertDialog, ctx: Context) {
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

        setActionBarTitle("Browse Channels")
        setActionBarSubtitle(null)

        val ctx = context ?: return
        val guildId = StoreStream.getGuildSelected().selectedGuildId

        val root = if (view is ViewGroup) {
            view.removeAllViews()
            view
        } else {
            logger.error("ChannelBrowserProtoPage: Provided view is not a ViewGroup, cannot build UI.", null)
            return
        }

        val buildUI = fun(loadingView: ProgressBar, guildId: Long, ctx: Context) {
            root.removeAllViews()

            logger.info("[buildUI] view type: ${root.javaClass.name}, ctx: ${ctx.javaClass.name}, guildId: $guildId")
            val guildSettings = getCurrentGuildSettings(guildId)
            if (guildSettings == null) {
                handler.postDelayed({
                    val hasSpinner = root.childCount == 1 && root.getChildAt(0) is ProgressBar
                    if (hasSpinner) {
                        val msg = TextView(ctx).apply {
                            text = "Unable to load guild settings.\nCheck your connection or try switching servers."
                            setPadding(32, 32, 32, 32)
                            textSize = 16f
                        }
                        root.addView(msg)
                        logger.error("[buildUI] Fallback: Guild settings still null after delay.", null)
                    }
                }, 2500)
                logger.debug("[buildUI] Guild settings not loaded yet, showing spinner")
                val parent = loadingView.parent as? ViewGroup
                parent?.removeView(loadingView)
                root.addView(loadingView)
                handler.postDelayed({
                    if (root.childCount == 1 && root.getChildAt(0) is ProgressBar) {
                        val msg = TextView(ctx).apply {
                            text = "No guild settings loaded. Try again later or check your connection."
                            setPadding(32, 32, 32, 32)
                        }
                        root.addView(msg)
                        logger.warn("[buildUI] Fallback: Guild settings still null after delay.")
                    }
                }, 2500)
                return
            }
            logger.debug("[buildUI] Called for guildId=$guildId")
            val allChannelsRaw = StoreStream.getChannels().getChannelsForGuild(guildId)
            if (allChannelsRaw.isEmpty()) {
                val msg = TextView(ctx).apply {
                    text = "No channels found for this guild.\nYou may not have permission or the server is empty."
                    setPadding(32, 32, 32, 32)
                    textSize = 16f
                }
                root.addView(msg)
                logger.error("[buildUI] Fallback: allChannelsRaw is empty.", null)
                return
            }
            logger.debug("[buildUI] allChannelsRaw size: ${allChannelsRaw.size}")
            val idField = com.discord.api.channel.Channel::class.java.getDeclaredField("id").apply { isAccessible = true }
            val nameField = com.discord.api.channel.Channel::class.java.getDeclaredField("name").apply { isAccessible = true }
            val typeField = com.discord.api.channel.Channel::class.java.getDeclaredField("type").apply { isAccessible = true }
            val parentIdField = com.discord.api.channel.Channel::class.java.getDeclaredField("parentId").apply { isAccessible = true }
            val linearLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val header = TextView(ctx).apply {
                text = "Browse Channels"
                textSize = 20f
                typeface = ResourcesCompat.getFont(ctx, Constants.Fonts.whitney_bold)
                setPadding(0, 24, 0, 24)
                gravity = Gravity.CENTER
            }
            linearLayout.addView(header)
            root.addView(linearLayout)
            val channelOverrides = guildSettings["channel_overrides"] as? Map<String, Int> ?: emptyMap()
            logger.debug("[buildUI] channelOverrides size: ${channelOverrides.size}")
            val hiddenChannels = channelOverrides.filterValues { it == 0 }.keys.toSet()
            val channelsByCategory = mutableMapOf<Long, MutableList<com.discord.api.channel.Channel>>()
            val uncategorized = mutableListOf<com.discord.api.channel.Channel>()
            for (ch in allChannelsRaw.values) {
                val type = try { typeField.getInt(ch) } catch (_: Throwable) { -1 }
                if (type == 4) continue
                val parentId = try { parentIdField.get(ch) as? Long } catch (_: Throwable) { null }
                if (parentId != null && allChannelsRaw.containsKey(parentId)) {
                    channelsByCategory.getOrPut(parentId) { mutableListOf() }.add(ch)
                } else {
                    uncategorized.add(ch)
                }
            }
            logger.debug("[buildUI] categories size: ${channelsByCategory.size}, uncategorized size: ${uncategorized.size}")
            val categories = allChannelsRaw.values.filter {
                try { typeField.getInt(it) == 4 } catch (_: Throwable) { false }
            }
            val addedCategoryIds = mutableSetOf<Long>()
            for (cat in categories) {
                val catName = try { nameField.get(cat) as? String ?: "Unnamed Category" } catch (_: Throwable) { "Unnamed Category" }
                val catId = try { idField.get(cat) as? Long } catch (_: Throwable) { null }
                if (catId == null || !addedCategoryIds.add(catId)) continue // deduplicate
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
                val followLabel = TextView(ctx, null, 0, R.i.UiKit_Settings_Item).apply {
                    text = "Follow Category"
                    val color = try {
                        com.discord.utilities.color.ColorCompat.getThemedColor(ctx, com.lytefast.flexinput.R.b.colorInteractiveNormal)
                    } catch (_: Throwable) { 0xFF222222.toInt() }
                    setTextColor(color)
                    typeface = ResourcesCompat.getFont(ctx, Constants.Fonts.whitney_medium)
                    textSize = 14f
                    setPadding(16, 0, 16, 0)
                }
                val childChannels = if (catId != null) channelsByCategory[catId] else null
                val childIds = childChannels?.mapNotNull { ch ->
                    try { idField.get(ch) as? Long } catch (_: Throwable) { null }
                } ?: emptyList()
                val allChecked = childIds.all { !hiddenChannels.contains(it.toString()) }
                val catToggle = Switch(ctx)
                catToggle.isChecked = allChecked
                catToggle.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
                catToggle.setOnCheckedChangeListener { _, checked ->
                    val newOverridesList = childIds.map { chId ->
                        mutableMapOf<String, Any>(
                            "channel_id" to chId,
                            "flags" to if (checked) 4096 else 0
                        )
                    }
                    protoSettingsManager.patchGuildSettingsAsync(guildId, newOverridesList)
                }
                catRow.addView(catTv)
                catRow.addView(followLabel)
                catRow.addView(catToggle)
                linearLayout.addView(catRow)
                if (childChannels != null) {
                    val addedChildIds = mutableSetOf<Long>()
                    for (ch in childChannels) {
                        val chId = try { idField.get(ch) as? Long } catch (_: Throwable) { null }
                        if (chId == null || !addedChildIds.add(chId)) continue // deduplicate
                        addChannelRowReflect(ch, guildId, ctx, nameField, channelOverrides, linearLayout, allChannelsRaw, false, catToggle.isChecked)
                    }
                }
            }
            if (uncategorized.isNotEmpty()) {
                val uncategorizedLabel = TextView(ctx, null, 0, R.i.UiKit_Settings_Item).apply {
                    text = "Uncategorized"
                    typeface = ResourcesCompat.getFont(ctx, Constants.Fonts.whitney_bold)
                    textSize = 15f
                    setPadding(0, 24, 0, 8)
                }
                linearLayout.addView(uncategorizedLabel)
                val addedUncatIds = mutableSetOf<Long>()
                for (ch in uncategorized) {
                    val chId = try { idField.get(ch) as? Long } catch (_: Throwable) { null }
                    if (chId == null || !addedUncatIds.add(chId)) continue // deduplicate
                    addChannelRowReflect(ch, guildId, ctx, nameField, channelOverrides, linearLayout, allChannelsRaw, false, false)
                }
            }
            if (categories.isEmpty() && uncategorized.isEmpty()) {
                val msg = TextView(ctx).apply {
                    text = "No channels or categories found in this guild.\nTry refreshing or check your permissions."
                    setPadding(32, 32, 32, 32)
                    textSize = 16f
                }
                linearLayout.addView(msg)
                logger.error("[buildUI] Fallback: No channels or categories found.", null)
            }
        }

        val loadingView = ProgressBar(ctx).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        buildUI(loadingView, guildId, ctx)

        val store = StoreStream.getUserGuildSettings()
        ObservableExtensionsKt.appSubscribe(
            store.observeGuildSettings(guildId),
            ChannelBrowserProtoPage::class.java,
            ctx,
            { /* onSubscription, not used */ },
            { error: com.discord.utilities.error.Error ->
                val cause = error.throwable
                if (cause is IllegalStateException && cause.message?.contains("Settings not loaded yet") == true) {
                    logger.debug("[observable] Suppressed expected 'Settings not loaded yet' error.")
                } else {
                    logger.error("Guild settings observable error", error as? Exception ?: Exception(error.toString()))
                }
            },
            { /* onCompleted, not used */ },
            { /* onTerminated, not used */ },
            { _: Any? ->
                logger.debug("[observable] Guild settings updated via observable, refreshing UI.")
                handler.post {
                    setActionBarTitle("Browse Channels")
                    setActionBarSubtitle(null)
                    root.removeAllViews()
                    val loadingView2 = ProgressBar(ctx).apply {
                        isIndeterminate = true
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                    buildUI(loadingView2, guildId, ctx)
                }
            }
        )
    }
}
