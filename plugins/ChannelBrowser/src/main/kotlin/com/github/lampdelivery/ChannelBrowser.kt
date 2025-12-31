package com.github.lampdelivery

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import com.discord.utilities.color.ColorCompat
import androidx.cardview.widget.CardView
import androidx.core.content.res.ResourcesCompat
import com.aliucord.Constants
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.SettingsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.aliucord.settings.delegate
import com.aliucord.utils.MDUtils
import com.aliucord.utils.ReflectUtils
import com.aliucord.utils.ViewUtils.addTo
import com.aliucord.wrappers.ChannelWrapper.Companion.name
import com.discord.models.guild.Guild
import com.discord.utilities.permissions.PermissionUtils
import com.discord.widgets.channels.list.`WidgetChannelListModel$Companion$guildListBuilder$$inlined$forEach$lambda$3`
import com.discord.widgets.guilds.profile.WidgetGuildProfileSheet
import com.aliucord.utils.ViewUtils.findViewById
import com.discord.databinding.WidgetGuildProfileSheetBinding
import com.discord.stores.StoreStream
import com.discord.widgets.guilds.profile.WidgetGuildProfileSheetViewModel
import com.lytefast.flexinput.R
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.discord.api.channel.Channel as ApiChannel


@AliucordPlugin
class ChannelBrowser : Plugin() {
    private var SettingsAPI.channels by settings.delegate(mutableListOf<String>())

    override fun start(context: Context) {
        patcher.after<`WidgetChannelListModel$Companion$guildListBuilder$$inlined$forEach$lambda$3`>("invoke") {
            val ret = it.result
            val channel = `$channel`
            val channelName = channel.name

            val guildId = try { com.discord.stores.StoreStream.getGuildSelected().selectedGuildId } catch (_: Throwable) { null }
            logger.info("ChannelBrowser: selectedGuildId = $guildId")

            if (!settings.channels.contains("$channelName-$guildId")) return@after

            it.result = null

            try {
                val chanIdField = try { ApiChannel::class.java.getDeclaredField("id").apply { isAccessible = true } } catch (_: Throwable) { null }
                val nameField = try { ApiChannel::class.java.getDeclaredField("name").apply { isAccessible = true } } catch (_: Throwable) { null }
                val chanId = try { chanIdField?.get(channel) as? Long } catch (_: Throwable) { null }
                val localNameMap = settings.getObject("names", MutableMap::class.java) as? MutableMap<Long, String>
                val newName = if (chanId != null) localNameMap?.get(chanId) else null
                if (newName != null && nameField != null) {
                    nameField.set(channel, newName)
                }
            } catch (_: Throwable) {}
        }

        try {
            val candidates = listOf(
                "com.discord.widgets.channels.list.WidgetChannelsList",
                "com.discord.widgets.channels.list.WidgetChannelList"
            )
            var clazz: Class<*>? = null
            for (name in candidates) {
                try { clazz = Class.forName(name); break } catch (_: Throwable) {}
            }
            if (clazz != null) {
                for (m in clazz!!.declaredMethods) {
                    if (m.name == "onViewBound") {
                        patcher.patch(m, Hook { cf ->
                            try {
                                val boundView = cf.args?.getOrNull(0) as? View ?: return@Hook
                                val container = (boundView as? ViewGroup) ?: return@Hook

                                val rv = findFirstVerticalRecyclerView(container) ?: return@Hook
                                val adapter = rv.adapter ?: return@Hook
                                val guildId = try { com.discord.stores.StoreStream.getGuildSelected().selectedGuildId } catch (_: Throwable) { null }

                                if (guildId == null) {
                                    if (adapter is ConcatAdapter) {
                                        val filtered = adapter.adapters.filterNot { it is ChannelBrowserHeaderAdapter }
                                        rv.adapter = ConcatAdapter(filtered)
                                    }
                                    return@Hook
                                }

                                if (adapter is ConcatAdapter && adapter.adapters.any { it is ChannelBrowserHeaderAdapter }) return@Hook

                                val headerAdapter = ChannelBrowserHeaderAdapter {
                                    Utils.openPageWithProxy(rv.context, ChannelBrowserPage(settings, settings.channels))
                                }
                                rv.adapter = when (adapter) {
                                    is ConcatAdapter -> ConcatAdapter(listOf(headerAdapter) + adapter.adapters)
                                    else -> ConcatAdapter(headerAdapter, adapter)
                                }
                            } catch (_: Throwable) {}
                        })
                        break
                    }
                }
            }
        } catch (_: Throwable) {}

        patcher.after<WidgetGuildProfileSheet>("configureTabItems", Long::class.java,
            WidgetGuildProfileSheetViewModel.TabItems::class.java, Boolean::class.java) { param ->
            val bindingMethod = ReflectUtils.getMethodByArgs(WidgetGuildProfileSheet::class.java, "getBinding")
            val binding = bindingMethod.invoke(this) as WidgetGuildProfileSheetBinding

            val layout = binding.f.getRootView() as ViewGroup
            val primaryActions = layout.findViewById<CardView>("guild_profile_sheet_secondary_actions")
            val lay = primaryActions.getChildAt(0) as LinearLayout

            val guildId = try { com.discord.stores.StoreStream.getGuildSelected().selectedGuildId } catch (_: Throwable) { null }
            if (guildId == null) return@after

            val alreadyHasBrowse = (0 until lay.childCount).any {
                val v = lay.getChildAt(it)
                v is TextView && v.text?.toString()?.contains("Browse Channels") == true
            }
            val alreadyHasSettings = (0 until lay.childCount).any {
                val v = lay.getChildAt(it)
                v is TextView && v.text?.toString()?.contains("Channel Browser Settings") == true
            }
            if (!alreadyHasBrowse) {
                TextView(lay.context, null, 0, R.i.UiKit_Settings_Item).apply {
                    text = MDUtils.render("Browse Channels")
                    typeface = ResourcesCompat.getFont(context, Constants.Fonts.whitney_medium)
                    textSize = 16f
                    
                    setOnClickListener {
                        Utils.openPageWithProxy(lay.context, ChannelBrowserPage(settings, settings.channels))
                    }
                }.addTo(lay)
            }
            if (!alreadyHasSettings) {
                TextView(lay.context, null, 0, R.i.UiKit_Settings_Item).apply {
                    text = MDUtils.render("Channel Browser Settings")
                    typeface = ResourcesCompat.getFont(context, Constants.Fonts.whitney_medium)
                    textSize = 16f
                    setOnClickListener {
                        Utils.openPageWithProxy(lay.context, ChannelBrowserSettings(settings))
                    }
                }.addTo(lay)
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    private fun findFirstVerticalRecyclerView(root: ViewGroup): androidx.recyclerview.widget.RecyclerView? {
        fun scan(v: View): androidx.recyclerview.widget.RecyclerView? {
            if (v is androidx.recyclerview.widget.RecyclerView) {
                val lm = v.layoutManager
                if (lm is androidx.recyclerview.widget.LinearLayoutManager && lm.orientation == androidx.recyclerview.widget.RecyclerView.VERTICAL) {
                    return v
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val r = scan(v.getChildAt(i))
                    if (r != null) return r
                }
            }
            return null
        }
        return scan(root)
    }

    private fun dp(view: View, dp: Float): Int {
        val d = view.resources.displayMetrics
        return (dp * d.density + 0.5f).toInt()
    }

    private class ChannelBrowserHeaderAdapter(
        val onClick: () -> Unit
    ) : RecyclerView.Adapter<ChannelBrowserHeaderAdapter.VH>() {
        class VH(val row: LinearLayout) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val scale = ctx.resources.displayMetrics.density
            val ph = 0
            val pv = 0
            val minH = 0

            val lp = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 0
                rightMargin = 0
                topMargin = 0
                bottomMargin = 0
            }

            val row = LinearLayout(ctx).apply {
                layoutParams = lp
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(ph, pv, ph, pv)
                minimumHeight = minH
                val attrs = intArrayOf(android.R.attr.selectableItemBackground)
                val typedArray = ctx.obtainStyledAttributes(attrs)
                background = typedArray.getDrawable(0)
                typedArray.recycle()
            }

            val icon = ImageView(ctx).apply {
                val resId = try { com.lytefast.flexinput.R.e.ic_menu_24dp } catch (_: Throwable) { android.R.drawable.ic_menu_sort_by_size }
                val drawable = androidx.core.content.ContextCompat.getDrawable(ctx, resId)?.mutate()
                try {
                    val color = ColorCompat.getThemedColor(ctx, com.lytefast.flexinput.R.b.colorInteractiveNormal)
                    drawable?.setTint(color)
                } catch (_: Throwable) {}
                setImageDrawable(drawable)
                val size = (24 * scale).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = 0
                    marginEnd = (2 * scale).toInt()
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
            }
            row.addView(icon)

            val tv = TextView(ctx, null, 0, com.lytefast.flexinput.R.i.UiKit_Settings_Item).apply {
                text = "Browse Channels"
                typeface = ResourcesCompat.getFont(ctx, Constants.Fonts.whitney_medium)
                textSize = 16f
                val colorRes = try { com.lytefast.flexinput.R.c.primary_dark } catch (_: Throwable) { android.R.color.black }
                val color = try { androidx.core.content.ContextCompat.getColor(ctx, colorRes) } catch (_: Throwable) { 0xFF000000.toInt() }
                setTextColor(color)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tv)
            return VH(row)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.row.setOnClickListener { onClick() }
        }

        override fun getItemCount(): Int = 1
        override fun getItemViewType(position: Int): Int = 10001
    }
}
