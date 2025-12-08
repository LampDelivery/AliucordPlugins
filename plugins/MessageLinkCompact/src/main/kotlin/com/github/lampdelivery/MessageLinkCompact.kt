package com.github.lampdelivery

import android.content.Context
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import com.aliucord.*
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.fragments.SettingsPage
import com.aliucord.patcher.Hook
import com.discord.stores.StoreChannels

@AliucordPlugin(requiresRestart = false)
class MessageLinkCompact : Plugin() {

    init {
        settingsTab = SettingsTab(
            MessageLinkCompactSettings::class.java,
            SettingsTab.Type.BOTTOM_SHEET
        )
    }

    override fun start(context: Context) {
        with(com.discord.api.message.Message::class.java) {
            patcher.patch(getDeclaredMethod("i"), Hook { callFrame ->
                try {
                    if (callFrame.result == null) return@Hook
                    var content = callFrame.result as String
                    if (content == "") return@Hook

                    val regex = Regex("""(?<!\]\()https://discord\.com/channels/(\d+|@me)/(\d+)/(\d+)""")
                    content = regex.replace(content) { match ->
                        val isDm = match.groupValues[1] == "@me"
                        val channelId = match.groupValues[2]
                        val channelName = getChannelName(channelId, isDm) ?: "unknown"
                        "[#$channelName > 💬](${match.value})"
                    }
                    callFrame.result = content
                } catch (_: Throwable) {}
            })
        }
    }

    override fun stop(context: Context) = patcher.unpatchAll()

    private fun getChannelName(channelId: String, isDm: Boolean = false): String? {
        return try {
            val storeStreamClass = Class.forName("com.discord.stores.StoreStream")
            val getChannelsMethod = storeStreamClass.getDeclaredMethod("getChannels")
            val channelsStore = getChannelsMethod.invoke(null)
            val getChannelMethod = channelsStore.javaClass.getDeclaredMethod("getChannel", Long::class.java)
            val channel = getChannelMethod.invoke(channelsStore, channelId.toLong()) ?: return null

            if (isDm) {
                // Try group DM name first
                try {
                    val nameField = channel.javaClass.getDeclaredField("name").apply { isAccessible = true }
                    val name = nameField.get(channel) as? String
                    if (!name.isNullOrBlank()) return name
                } catch (_: Throwable) {}
                // Try recipient username
                try {
                    val recipientsField = channel.javaClass.getDeclaredField("recipients").apply { isAccessible = true }
                    val recipients = recipientsField.get(channel) as? List<*>
                    val user = recipients?.firstOrNull()
                    val usernameField = user?.javaClass?.getDeclaredField("username")?.apply { isAccessible = true }
                    return usernameField?.get(user) as? String
                } catch (_: Throwable) {}
                return "DM"
            } else {
                // Guild channel
                val nameField = channel.javaClass.getDeclaredField("name").apply { isAccessible = true }
                return nameField.get(channel) as? String
            }
        } catch (_: Throwable) {
            null
        }
    }
}

class MessageLinkCompactSettings : SettingsPage() {
    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        linearLayout.addView(AppCompatTextView(requireContext()).apply {
            text = "Message links will be shown as #channel-name > 💬"
            textSize = 18f
            setPadding(0, 16, 0, 16)
        })
    }
}