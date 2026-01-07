package com.github.lampdelivery

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.api.channel.Channel
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed
import com.discord.stores.StoreStream
import com.discord.utilities.user.UserUtils

@AliucordPlugin
class CustomNameFormat : Plugin() {
    enum class Format {
        NICKNAME_USERNAME,
        NICKNAME_TAG,
        DISPLAYNAME_USERNAME,
        DISPLAYNAME_TAG,
        USERNAME,
        USERNAME_NICKNAME,
        USERNAME_DISPLAYNAME
    }

    override fun start(context: Context) {
        // Patch getNickOrUsername
        patcher.patch(
            GuildMember::class.java.getDeclaredMethod("getNickOrUsername", User::class.java, GuildMember::class.java, Channel::class.java, List::class.java),
            Hook { param ->
                val user = param.args[0] as User
                val member = param.args[1] as? GuildMember
                val nickname = member?.nick
                val displayName = try {
                    user.javaClass.getMethod("getGlobalName").invoke(user) as? String ?: user.username
                } catch (_: Throwable) {
                    user.username
                }
                val username = user.username
                val res = param.result as String
                if (res == username) {
                    param.result = getFormatted(nickname, displayName, username, res, user)
                }
            }
        )

        // Patch embeds
        patcher.patch(
            WidgetChatListAdapterItemEmbed::class.java.getDeclaredMethod("getModel", Any::class.java, Any::class.java),
            Hook { param ->
                val mapResult = param.result
                if (mapResult !is MutableMap<*, *>) return@Hook
                if (mapResult.isEmpty()) return@Hook
                val users = StoreStream.getUsers().users
                for ((idAny, valueAny) in mapResult) {
                    val id = idAny as? Long ?: continue
                    val value = valueAny as? String ?: continue
                    val user = users[id]
                    if (user != null) {
                        val displayName = try {
                            user.javaClass.getMethod("getGlobalName").invoke(user) as? String ?: user.username
                        } catch (_: Throwable) {
                            user.username
                        }
                        @Suppress("UNCHECKED_CAST")
                        (mapResult as MutableMap<Any?, Any?>)[id] = getFormatted(null, displayName, user.username, value, user)
                    }
                }
            }
        )

        // Patch UserNameFormatterKt.getSpannableForUserNameWithDiscrim
        try {
            val userNameFormatterClass = Class.forName("com.discord.widgets.user.UserNameFormatterKt")
            val getSpannableMethod = userNameFormatterClass.getDeclaredMethod(
                "getSpannableForUserNameWithDiscrim",
                Class.forName("com.discord.models.user.ModelUser"),
                String::class.java,
                Context::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java
            )
            patcher.patch(getSpannableMethod, Hook { param ->
                val user = param.args[0]
                val username = user?.javaClass?.getMethod("getUsername")?.invoke(user) as? String ?: return@Hook
                val res = param.args[1] as? String ?: username
                param.args[1] = getFormatted(null, username, username, res, user as User)
            })
        } catch (_: Throwable) {}

        // Patch UserProfileHeaderView.getSecondaryNameTextForUser
        try {
            val userProfileHeaderViewClass = Class.forName("com.discord.widgets.user.profile.UserProfileHeaderView")
            val getSecondaryNameMethod = userProfileHeaderViewClass.getDeclaredMethod(
                "getSecondaryNameTextForUser",
                Class.forName("com.discord.models.user.ModelUser"),
                Class.forName("com.discord.models.member.GuildMember")
            )
            patcher.patch(getSecondaryNameMethod, Hook { param ->
                val user = param.args[0]
                val username = user?.javaClass?.getMethod("getUsername")?.invoke(user) as? String ?: return@Hook
                val res = param.result as? String ?: username
                param.result = getFormatted(null, username, username, res, user as User)
            })
        } catch (_: Throwable) {}

        // Patch UserProfileHeaderView.configureSecondaryName
        try {
            val userProfileHeaderViewClass = Class.forName("com.discord.widgets.user.profile.UserProfileHeaderView")
            val headerViewModelClass = Class.forName("com.discord.widgets.user.profile.UserProfileHeaderViewModel\$ViewState\$Loaded")
            val configureSecondaryNameMethod = userProfileHeaderViewClass.getDeclaredMethod(
                "configureSecondaryName",
                headerViewModelClass
            )
            patcher.patch(configureSecondaryNameMethod, Hook { param ->
                // No-op, prevents Discord from overriding the secondary name
            })
        } catch (_: Throwable) {}
    }



    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    private fun getFormatted(nickname: String?, displayName: String, username: String, res: String, user: User): String {
        val format = Format.valueOf(settings.getString("format", Format.NICKNAME_USERNAME.name))
        return when (format) {
            Format.NICKNAME_USERNAME -> "${nickname ?: displayName} ($username)"
            Format.NICKNAME_TAG -> "${nickname ?: displayName} ($username${UserUtils.INSTANCE.getDiscriminatorWithPadding(user)})"
            Format.DISPLAYNAME_USERNAME -> "$displayName ($username)"
            Format.DISPLAYNAME_TAG -> "$displayName ($username${UserUtils.INSTANCE.getDiscriminatorWithPadding(user)})"
            Format.USERNAME -> username
            Format.USERNAME_NICKNAME -> "$username (${nickname ?: displayName})"
            Format.USERNAME_DISPLAYNAME -> "$username ($displayName)"
        }
    }
}

