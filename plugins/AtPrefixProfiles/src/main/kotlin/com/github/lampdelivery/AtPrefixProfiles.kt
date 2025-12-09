package com.github.lampdelivery

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook

@AliucordPlugin(requiresRestart = false)
class AtPrefixProfiles : Plugin() {
    private val enableMemberList = true
    private val enableMiniProfile = true

    override fun start(context: Context) {
        val hookConfigs = listOf(
            HookConfig(
                classes = listOf(
                    "com.discord.widgets.user.profile.UserProfileHeaderView",
                    "com.discord.widgets.user.profile.WidgetUserProfile",
                    "com.discord.widgets.user.usersheet.WidgetUserSheet",
                    "com.discord.widgets.user.profile.UserProfileHeaderViewV2"
                ),
                methodNames = listOf("onViewCreated"),
                idCandidates = listOf(
                    "user_profile_header_primary_name",
                    "user_profile_header_secondary_name",
                    "user_profile_header_name",
                    "profile_header_username"
                )
            ),
            HookConfig(
                enabled = enableMemberList,
                classes = listOf(
                    "com.discord.widgets.user.list.adapter.UserListItem",
                    "com.discord.widgets.user.list.adapter.WidgetUserListAdapterItem",
                    "com.discord.widgets.user.list.adapter.WidgetUserListAdapterItemMember"
                ),
                methodNames = listOf("bind", "configure", "onConfigure"),
                idCandidates = listOf(
                    "user_list_item_name",
                    "member_list_item_name",
                    "widget_user_list_item_name"
                )
            ),
            HookConfig(
                enabled = enableMiniProfile,
                classes = listOf(
                    "com.discord.widgets.user.usersheet.WidgetUserSheet",
                    "com.discord.widgets.user.profile.UserProfileHeaderViewV2"
                ),
                methodNames = listOf("onViewCreated", "onResume", "bind"),
                idCandidates = listOf(
                    "user_sheet_header_name",
                    "user_profile_header_primary_name",
                    "user_profile_header_name"
                )
            )
        )

        hookConfigs.forEach { config ->
            if (!config.enabled) return@forEach
            config.classes.forEach { clsName ->
                try {
                    val cls = Class.forName(clsName)
                    cls.declaredMethods
                        .filter { it.name in config.methodNames }
                        .forEach { method ->
                            patcher.patch(method, Hook { cf ->
                                val root = extractRootView(cf) ?: return@Hook
                                val tv = findByIdNames(root, config.idCandidates, getPkgCandidates(root))
                                    ?: findUsernameTextView(root)
                                tv?.let {
                                    applyAtPrefixSafely(it)
                                    attachReapplyGuards(it)
                                }
                            })
                        }
                } catch (_: Throwable) {}
            }
        }

        try {
            val appFragment = Class.forName("com.discord.app.AppFragment")
            appFragment.declaredMethods
                .filter { it.name == "onViewCreated" }
                .forEach { method ->
                    patcher.patch(method, Hook { cf ->
                        val root = cf.args?.getOrNull(0) as? View ?: return@Hook
                        val idCandidates = listOf(
                            "user_profile_header_primary_name",
                            "user_profile_header_secondary_name",
                            "user_profile_header_name",
                            "profile_header_username",
                            "settings_user_profile_header_name",
                            "settings_profile_preview_name",
                            "user_profile_header_name_wrap"
                        )
                        val tv = findByIdNames(root, idCandidates, getPkgCandidates(root))
                            ?: (findWrappedProfilePreviewUsername(root) ?: findUsernameTextView(root))
                        tv?.let {
                            applyAtPrefixSafely(it)
                            attachReapplyGuards(it)
                        }
                    })
                }
        } catch (_: Throwable) {}
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    data class HookConfig(
        val enabled: Boolean = true,
        val classes: List<String>,
        val methodNames: List<String>,
        val idCandidates: List<String>
    )

    private fun extractRootView(cf: com.aliucord.patcher.HookedMethodCall): View? {
        return cf.args?.firstOrNull { it is View } as? View
            ?: (cf.thisObject?.let { owner ->
                owner.javaClass.declaredFields.firstOrNull { f ->
                    View::class.java.isAssignableFrom(f.type)
                }?.apply { isAccessible = true }?.get(owner) as? View
            })
    }

    private fun applyAtPrefixSafely(tv: TextView) {
        try {
            applyAtPrefix(tv)
        } catch (_: Throwable) {
            val text = tv.text?.toString() ?: return
            if (text.isBlank() || text.startsWith("@") || text.contains("#") || text.contains('\n')) return
            tv.post { tv.text = "@" + text }
        }
    }

    private fun applyAtPrefix(tv: TextView) {
        val text = tv.text?.toString() ?: return
        if (text.isEmpty() || text.startsWith("@") || text.contains("#") || text.contains("\n")) return
        tv.text = if (tv.text is android.text.Spannable) {
            android.text.SpannableStringBuilder().append("@").append(tv.text)
        } else {
            "@$text"
        }
    }

    private fun attachReapplyGuards(tv: TextView) {
        try {
            tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyAtPrefixSafely(tv) }
        } catch (_: Throwable) {}
        try {
            tv.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = applyAtPrefixSafely(tv)
            })
        } catch (_: Throwable) {}
    }

    private fun findByIdName(root: View, idName: String, packageName: String? = null): TextView? {
        return try {
            val ctx = root.context
            val pkg = packageName ?: ctx.packageName
            val id = ctx.resources.getIdentifier(idName, "id", pkg)
            if (id != 0) root.findViewById<TextView>(id) else null
        } catch (_: Throwable) { null }
    }

    private fun findByIdNames(root: View, idNames: List<String>, packageNames: List<String>): TextView? {
        for (pkg in packageNames) for (id in idNames) {
            findByIdName(root, id, pkg)?.let { return it }
        }
        return null
    }

    private fun findUsernameTextView(root: View): TextView? =
        findFirstTextView(root) { tv ->
            val text = tv.text?.toString() ?: return@findFirstTextView false
            text.length in 2..32 && !text.startsWith("@") && !text.contains("#") && text.isNotBlank()
        }

    private fun findFirstTextView(root: View, predicate: (TextView) -> Boolean): TextView? {
        if (root is TextView && predicate(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount)
            findFirstTextView(root.getChildAt(i), predicate)?.let { return it }
        return null
    }

    private fun getPkgCandidates(root: View) = listOf(
        "com.discord", "com.discord.app", root.context.packageName
    )

    private fun findWrappedProfilePreviewUsername(root: View): TextView? {
        val ctx = root.context
        val wrapId = ctx.resources.getIdentifier("user_profile_header_name_wrap", "id", ctx.packageName)
        val wrap = if (wrapId != 0) root.findViewById<ViewGroup?>(wrapId) else null
        return wrap?.let {
            findFirstTextView(it) { candidate ->
                val text = candidate.text?.toString() ?: return@findFirstTextView false
                text.isNotBlank() && !text.startsWith("@") && !text.contains("#")
            }
        }
    }
}