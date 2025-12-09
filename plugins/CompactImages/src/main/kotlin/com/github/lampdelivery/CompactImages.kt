package com.github.lampdelivery

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.graphics.Color
import androidx.recyclerview.widget.RecyclerView
import android.view.ViewTreeObserver
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook

@AliucordPlugin(requiresRestart = false)
class CompactImages : Plugin() {
    override fun start(context: Context) {
        logger.info("CompactImages start")
        tryBindChatAdapter()
        tryBindMessageView()
        tryBindChatRecycler()
        tryBindAppFragment()
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        logger.info("CompactImages stop")
    }

    private fun tryBindChatRecycler() {
        val candidates = listOf(
            "com.discord.widgets.chat.list.WidgetChatList",
            "com.discord.widgets.chat.ChatView"
        )
        for (name in candidates) {
            try {
                val cls = Class.forName(name)
                for (m in cls.declaredMethods) {
                    // Look for lifecycle-ish methods that provide a root view
                    if (m.name.contains("onView") || m.parameterTypes.any { View::class.java.isAssignableFrom(it) }) {
                        patcher.patch(m, Hook { cf ->
                            val viewArg = cf.args?.firstOrNull { it is View } as? View
                            val root = viewArg ?: getItemViewFromAny(cf.thisObject)
                            if (root != null) {
                                val rv = findRecyclerView(root)
                                if (rv != null) attachGlobalLayoutCompactor(rv)
                            }
                        })
                        logger.info("CompactImages: hooked ${name}#${m.name} for RecyclerView binding")
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun findRecyclerView(root: View): RecyclerView? {
        var found: RecyclerView? = null
        fun walk(v: View) {
            if (found != null) return
            if (v is RecyclerView) {
                found = v
                return
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return found
    }

    private fun attachGlobalLayoutCompactor(rv: RecyclerView) {
        try {
            rv.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    try {
                        for (i in 0 until rv.childCount) {
                            val child = rv.getChildAt(i)
                            compactImagesIfFound(child)
                        }
                    } catch (t: Throwable) {
                        logger.error("CompactImages: global layout compactor error", t)
                    }
                }
            })
            logger.info("CompactImages: global layout compactor attached to RecyclerView")
            // Visual banner to confirm attachment
            try {
                val banner = android.widget.TextView(rv.context).apply {
                    text = "CompactImages active"
                    setBackgroundColor(0x6633B5E5.toInt())
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(8, 8, 8, 8)
                }
                val parent = rv.parent as? ViewGroup
                parent?.addView(banner, 0)
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            logger.error("CompactImages: attachGlobalLayoutCompactor error", t)
        }
    }

    private fun tryBindAppFragment() {
        try {
            val cls = Class.forName("com.discord.app.AppFragment")
            for (m in cls.declaredMethods) {
                if (m.name == "onViewCreated") {
                    patcher.patch(m, Hook { cf ->
                        val viewArg = cf.args?.firstOrNull { it is View } as? View
                        val root = viewArg ?: getItemViewFromAny(cf.thisObject)
                        if (root != null) {
                            // Try to find a RecyclerView that contains our media preview ids
                            val rv = findMediaRecycler(root)
                            if (rv != null) attachGlobalLayoutCompactor(rv)
                        }
                    })
                    logger.info("CompactImages: hooked AppFragment#onViewCreated for RecyclerView detection")
                }
            }
        } catch (_: Throwable) {}
    }

    private fun findMediaRecycler(root: View): RecyclerView? {
        val ctx = root.context
        val idPreview1 = ctx.resources.getIdentifier("media_image_preview", "id", ctx.packageName)
        val idPreview2 = ctx.resources.getIdentifier("inline_media_image_preview", "id", ctx.packageName)
        var found: RecyclerView? = null
        fun walk(v: View) {
            if (found != null) return
            if (v is RecyclerView) {
                // Check a few children for media ids
                val childCount = v.childCount
                for (i in 0 until childCount) {
                    val child = v.getChildAt(i)
                    val has1 = idPreview1 != 0 && child.findViewById<View?>(idPreview1) != null
                    val has2 = idPreview2 != 0 && child.findViewById<View?>(idPreview2) != null
                    if (has1 || has2) {
                        found = v
                        return
                    }
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return found
    }

    private fun tryBindChatAdapter() {
        try {
            val cls = Class.forName("com.discord.widgets.chat.ChatListAdapter")
            // Patch all declared methods named onBindViewHolder
            for (m in cls.declaredMethods) {
                if (m.name == "onBindViewHolder") {
                    patcher.patch(m, Hook { cf ->
                        val holder = cf.args?.getOrNull(0)
                        val itemView = (holder as? RecyclerView.ViewHolder)?.itemView
                            ?: getItemViewFromAny(holder)
                        if (itemView != null) {
                            logger.info("CompactImages: onBindViewHolder itemView received")
                            compactImagesIfFound(itemView)
                        } else {
                            logger.info("CompactImages: onBindViewHolder itemView null")
                        }
                    })
                    logger.info("CompactImages: hooked ChatListAdapter#onBindViewHolder")
                }
            }
        } catch (t: Throwable) {
            logger.info("CompactImages: ChatListAdapter not found: ${t.javaClass.simpleName}")
        }
    }

    private fun tryBindMessageView() {
        val candidates = listOf(
            "com.discord.widgets.chat.list.adapter.WidgetChatListItem",
            "com.discord.widgets.chat.list.adapter.MessageViewHolder",
            "com.discord.widgets.chat.view.MessageView",
            "com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemAttachment",
            "com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEphemeralMessage"
        )
        for (name in candidates) {
            try {
                val cls = Class.forName(name)
                for (m in cls.declaredMethods) {
                    if (m.name == "bind" || m.name == "configure" || m.name == "onConfigure") {
                        patcher.patch(m, Hook { cf ->
                            val viewArg = cf.args?.firstOrNull { it is View } as? View
                            val itemView = viewArg ?: getItemViewFromAny(cf.thisObject)
                            if (itemView != null) {
                                logger.info("CompactImages: ${name}#${m.name} itemView resolved")
                                compactImagesIfFound(itemView)
                            } else {
                                logger.info("CompactImages: ${name}#${m.name} no itemView")
                            }
                        })
                        logger.info("CompactImages: hooked ${name}#${m.name}")
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun getItemViewFromAny(obj: Any?): View? {
        if (obj == null) return null
        if (obj is RecyclerView.ViewHolder) return obj.itemView
        return try {
            val f = obj.javaClass.getDeclaredField("itemView")
            f.isAccessible = true
            f.get(obj) as? View
        } catch (_: Throwable) {
            null
        }
    }

    private fun compactImagesIfFound(root: View) {
        try {
            // Also compact general message gutter similar to CompactMode
            compactMessageGutter(root)
            val groups = findAttachmentGroupsById(root)
            logger.info("CompactImages: groups found=${groups.size}")
            for (group in groups) {
                // Optionally force a single horizontal row container
                if (settings.getBool("forceSingleRow", true)) {
                    val wrapped = wrapFramesIntoSingleRow(group)
                    if (!wrapped) insertHeaderRowAboveFrames(group)
                }
                applyCompactLayout(group)
            }
            if (groups.isEmpty()) {
                // Aggressive fallback: compact any ImageViews in message root
                compactAllImagesFallback(root)
                // Brutal mode: forcibly reparent any ImageViews into one horizontal row
                if (settings.getBool("forceBrutalRow", true)) {
                    brutalRowFrom(root)
                }
            }
        } catch (t: Throwable) {
            logger.error("CompactImages: compactImagesIfFound error", t)
        }
    }

    private fun brutalRowFrom(root: View) {
        try {
            val container = (root.findViewById<ViewGroup?>(root.id) ?: (root as? ViewGroup)) ?: return
            val ctx = container.context
            val density = ctx.resources.displayMetrics.density
            val thumbSize = (96 * density).toInt()
            val margin = (4 * density).toInt()
            // Collect all direct and nested ImageViews
            val images = mutableListOf<ImageView>()
            fun walk(v: View) {
                if (v is ImageView) images.add(v)
                else if (v is ViewGroup) {
                    for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
            }
            walk(container)
            if (images.size < 2) return
            // Find a reasonable insertion parent near the first image
            val first = images.first()
            val parent = first.parent as? ViewGroup ?: return
            val insertIndex = parent.indexOfChild(first)
            if (insertIndex < 0) return
            // Create horizontal row and move images into it
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.clipToPadding = false
            for (img in images) {
                val p = img.parent as? ViewGroup ?: continue
                val lp = img.layoutParams
                p.removeView(img)
                // Size and margins
                lp?.width = thumbSize
                lp?.height = thumbSize
                if (lp is ViewGroup.MarginLayoutParams) lp.setMargins(margin, margin, margin, margin)
                row.addView(img, lp)
                img.scaleType = ImageView.ScaleType.CENTER_CROP
            }
            parent.addView(row, insertIndex)
            if (parent is LinearLayout) {
                parent.orientation = LinearLayout.VERTICAL
            }
            logger.info("CompactImages: brutalRow moved ${images.size} images into a single row")
        } catch (t: Throwable) {
            logger.error("CompactImages: brutalRowFrom error", t)
        }
    }

    private fun compactAllImagesFallback(root: View) {
        val ctx = root.context
        val messageRootId = ctx.resources.getIdentifier("widget_chat_list_adapter_item_text_root", "id", ctx.packageName)
        val container = if (root.id == messageRootId) root as? ViewGroup else root.findViewById<ViewGroup?>(messageRootId) ?: (root as? ViewGroup)
        if (container == null) return
        val density = container.resources.displayMetrics.density
        val thumbSize = (96 * density).toInt()
        val margin = (4 * density).toInt()
        var imageCount = 0
        fun walk(v: View) {
            if (v is ImageView) {
                v.scaleType = ImageView.ScaleType.CENTER_CROP
                val lp = v.layoutParams
                lp?.width = thumbSize
                lp?.height = thumbSize
                if (lp is ViewGroup.MarginLayoutParams) lp.setMargins(margin, margin, margin, margin)
                v.layoutParams = lp
                imageCount++
            } else if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(container)
        // If the immediate container is a LinearLayout and hosts multiple images, enforce horizontal
        if (container is LinearLayout && imageCount >= 2) {
            container.orientation = LinearLayout.HORIZONTAL
            container.weightSum = 0f
        }
        logger.info("CompactImages: applied fallback compacting to message images")
    }

    private fun compactMessageGutter(root: View) {
        try {
            val ctx = root.context
            val guidelineId = ctx.resources.getIdentifier("uikit_chat_guideline", "id", ctx.packageName)
            val messageRootId = ctx.resources.getIdentifier("widget_chat_list_adapter_item_text_root", "id", ctx.packageName)
            val replyIconViewId = ctx.resources.getIdentifier("chat_list_adapter_item_text_decorator_reply_link_icon", "id", ctx.packageName)
            val itemTextViewId = ctx.resources.getIdentifier("chat_list_adapter_item_text", "id", ctx.packageName)
            val loadingTextId = ctx.resources.getIdentifier("chat_list_adapter_item_text_loading", "id", ctx.packageName)
            val headerLayoutId = ctx.resources.getIdentifier("chat_list_adapter_item_text_header", "id", ctx.packageName)

            val contentMargin = (settings.getInt("contentMargin", 8) * ctx.resources.displayMetrics.density).toInt()
            val messagePadding = (settings.getInt("messagePadding", 10) * ctx.resources.displayMetrics.density).toInt()
            val hideReplyIcon = settings.getBool("hideReplyIcon", true)

            // Only attempt within a message item root or its subtree
            val messageRoot = if (root.id == messageRootId) root else root.findViewById<View?>(messageRootId)
            if (messageRoot != null) {
                // Adjust guideline to move content left
                if (guidelineId != 0) {
                    val guideline = (messageRoot as? ViewGroup)?.findViewById<View?>(guidelineId)
                    if (guideline is androidx.constraintlayout.widget.Guideline) {
                        guideline.setGuidelineBegin(contentMargin)
                    }
                }
                // Reset loading text extra margin
                val loadingView = (messageRoot as? ViewGroup)?.findViewById<View?>(loadingTextId)
                val lp = loadingView?.layoutParams
                if (lp is ViewGroup.MarginLayoutParams) lp.marginStart = 0
                // Apply top padding to condense
                messageRoot.setPadding(0, messagePadding, 0, 0)
                // Hide reply icon if desired
                if (hideReplyIcon) {
                    val replyIcon = (messageRoot as? ViewGroup)?.findViewById<View?>(replyIconViewId)
                    replyIcon?.visibility = View.GONE
                }
                // Nudge message text left margin
                val textView = (messageRoot as? ViewGroup)?.findViewById<View?>(itemTextViewId)
                val tlp = textView?.layoutParams
                if (tlp is ViewGroup.MarginLayoutParams) tlp.marginStart = contentMargin
                // Slight header start margin
                val headerView = (messageRoot as? ViewGroup)?.findViewById<View?>(headerLayoutId)
                val hlp = headerView?.layoutParams
                if (hlp is ViewGroup.MarginLayoutParams) hlp.marginStart = (4 * ctx.resources.displayMetrics.density).toInt()
            }
        } catch (t: Throwable) {
            logger.error("CompactImages: compactMessageGutter error", t)
        }
    }

    private fun findAttachmentGroupsById(root: View): List<ViewGroup> {
        val result = mutableListOf<ViewGroup>()
        val ctx = root.context
        val idInlineMedia = ctx.resources.getIdentifier("chat_list_item_attachment_inline_media", "id", ctx.packageName)
        val idPreview1 = ctx.resources.getIdentifier("media_image_preview", "id", ctx.packageName)
        val idPreview2 = ctx.resources.getIdentifier("inline_media_image_preview", "id", ctx.packageName)
        if (idInlineMedia == 0 && idPreview1 == 0 && idPreview2 == 0) return result
        // Prefer grouping under chat_list_adapter_item_* containers
        fun isAdapterItemContainer(v: ViewGroup): Boolean {
            val idName = try {
                val res = v.resources
                val entry = res.getResourceEntryName(v.id)
                entry
            } catch (_: Throwable) { null }
            return idName != null && (idName.startsWith("chat_list_adapter_item") || idName.startsWith("widget_chat_list_adapter_item"))
        }
        fun containsInlineImage(view: View): Boolean {
            // Detect known image previews or inline media containers
            return when (view) {
                is ImageView -> true // any ImageView inside our candidate containers counts
                is ViewGroup -> {
                    val hasInline = if (idInlineMedia != 0) view.findViewById<View>(idInlineMedia) != null else false
                    val hasPreview1 = if (idPreview1 != 0) view.findViewById<View>(idPreview1) != null else false
                    val hasPreview2 = if (idPreview2 != 0) view.findViewById<View>(idPreview2) != null else false
                    hasInline || hasPreview1 || hasPreview2
                }
                else -> false
            }
        }
        fun scan(v: View) {
            if (v is ViewGroup) {
                val isAdapterItem = isAdapterItemContainer(v)
                var inlineImageCount = 0
                for (i in 0 until v.childCount) {
                    val child = v.getChildAt(i)
                    if (child is android.widget.FrameLayout || child is ImageView || child is ViewGroup) {
                        if (containsInlineImage(child)) inlineImageCount++
                    }
                }
                if (inlineImageCount >= 2 && (isAdapterItem || v is LinearLayout)) {
                    logger.info("CompactImages: candidate group id=${try { v.resources.getResourceEntryName(v.id) } catch (_: Throwable) { "unknown" }} count=${inlineImageCount}")
                    result.add(v)
                }
                for (i in 0 until v.childCount) scan(v.getChildAt(i))
            }
        }
        scan(root)
        return result
    }

    private fun applyCompactLayout(group: ViewGroup) {
        // Try to set horizontal orientation for LinearLayout, otherwise adjust children sizes
        if (group is LinearLayout) {
            if (group.orientation != LinearLayout.HORIZONTAL) {
                group.orientation = LinearLayout.HORIZONTAL
            }
            // Prevent weights from causing vertical fill
            group.weightSum = 0f
        }
        val density = group.resources.displayMetrics.density
        val thumbSize = (96 * density).toInt()
        val margin = (4 * density).toInt()
        val debugVisual = settings.getBool("debugVisual", true)
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is android.widget.FrameLayout) {
                val lp = if (group is LinearLayout) {
                    LinearLayout.LayoutParams(thumbSize, thumbSize)
                } else {
                    child.layoutParams.apply {
                        width = thumbSize
                        height = thumbSize
                    }
                }
                if (lp is ViewGroup.MarginLayoutParams) {
                    lp.setMargins(margin, margin, margin, margin)
                }
                if (lp is LinearLayout.LayoutParams) {
                    lp.weight = 0f
                }
                child.layoutParams = lp
                if (debugVisual) {
                    child.setBackgroundColor(Color.parseColor("#334CAF50"))
                    child.setPadding(2, 2, 2, 2)
                }
                // Also adjust the inner media view if present
                val ctx = child.context
                val idInlineMedia = ctx.resources.getIdentifier("chat_list_item_attachment_inline_media", "id", ctx.packageName)
                val idPreview1 = ctx.resources.getIdentifier("media_image_preview", "id", ctx.packageName)
                val idPreview2 = ctx.resources.getIdentifier("inline_media_image_preview", "id", ctx.packageName)
                val media = when {
                    idPreview1 != 0 -> child.findViewById<ImageView>(idPreview1)
                    idPreview2 != 0 -> child.findViewById<ImageView>(idPreview2)
                    idInlineMedia != 0 -> child.findViewById<ImageView>(idInlineMedia)
                    else -> null
                }
                media?.scaleType = ImageView.ScaleType.CENTER_CROP
                media?.layoutParams?.apply {
                    width = thumbSize
                    height = thumbSize
                    if (this is ViewGroup.MarginLayoutParams) setMargins(0, 0, 0, 0)
                    if (this is LinearLayout.LayoutParams) this.weight = 0f
                }
                if (debugVisual) {
                    media?.setBackgroundColor(Color.parseColor("#33FF5722"))
                }
            } else if (child is ImageView) {
                val lp = if (group is LinearLayout) {
                    LinearLayout.LayoutParams(thumbSize, thumbSize)
                } else {
                    child.layoutParams.apply {
                        width = thumbSize
                        height = thumbSize
                    }
                }
                if (lp is ViewGroup.MarginLayoutParams) {
                    lp.setMargins(margin, margin, margin, margin)
                }
                if (lp is LinearLayout.LayoutParams) {
                    lp.weight = 0f
                }
                child.layoutParams = lp
                child.scaleType = ImageView.ScaleType.CENTER_CROP
                if (debugVisual) {
                    child.setBackgroundColor(Color.parseColor("#334CAF50"))
                    child.setPadding(2, 2, 2, 2)
                }
            }
        }
        // Allow children to draw outside padding if needed
        group.clipToPadding = false
        group.requestLayout()
    }

    private fun wrapFramesIntoSingleRow(group: ViewGroup): Boolean {
        try {
            val ctx = group.context
            val idInlineMedia = ctx.resources.getIdentifier("chat_list_item_attachment_inline_media", "id", ctx.packageName)
            val idPreview1 = ctx.resources.getIdentifier("media_image_preview", "id", ctx.packageName)
            val idPreview2 = ctx.resources.getIdentifier("inline_media_image_preview", "id", ctx.packageName)
            if (idInlineMedia == 0 && idPreview1 == 0 && idPreview2 == 0) return false
            // Collect FrameLayouts that contain inline media
            val frames = mutableListOf<android.widget.FrameLayout>()
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is android.widget.FrameLayout) {
                    val hasInline = if (idInlineMedia != 0) child.findViewById<View>(idInlineMedia) != null else false
                    val hasPreview1 = if (idPreview1 != 0) child.findViewById<View>(idPreview1) != null else false
                    val hasPreview2 = if (idPreview2 != 0) child.findViewById<View>(idPreview2) != null else false
                    if (hasInline || hasPreview1 || hasPreview2) frames.add(child)
                }
            }
            if (frames.size < 2) return false
            // If group is already a horizontal LinearLayout, no need to wrap
            if (group is LinearLayout && group.orientation == LinearLayout.HORIZONTAL) return true
            // Create a horizontal LinearLayout to host all frames at a single level
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.clipToPadding = false
            val insertIndex = group.indexOfChild(frames.first())
            if (insertIndex < 0) return false
            // Remove each frame from group and add to row
            for (f in frames) {
                val lp = f.layoutParams
                group.removeView(f)
                row.addView(f, lp)
            }
            // Insert the row back into the original position
            group.addView(row, insertIndex)
            logger.info("CompactImages: wrapped ${frames.size} frames into single horizontal row")
            return true
        } catch (t: Throwable) {
            logger.error("CompactImages: wrapFramesIntoSingleRow error", t)
            return false
        }
    }

    private fun insertHeaderRowAboveFrames(group: ViewGroup) {
        try {
            val ctx = group.context
            val idInlineMedia = ctx.resources.getIdentifier("chat_list_item_attachment_inline_media", "id", ctx.packageName)
            val idPreview1 = ctx.resources.getIdentifier("media_image_preview", "id", ctx.packageName)
            val idPreview2 = ctx.resources.getIdentifier("inline_media_image_preview", "id", ctx.packageName)
            if (idInlineMedia == 0 && idPreview1 == 0 && idPreview2 == 0) return
            val frames = mutableListOf<android.widget.FrameLayout>()
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is android.widget.FrameLayout) {
                    val hasInline = if (idInlineMedia != 0) child.findViewById<View>(idInlineMedia) != null else false
                    val hasPreview1 = if (idPreview1 != 0) child.findViewById<View>(idPreview1) != null else false
                    val hasPreview2 = if (idPreview2 != 0) child.findViewById<View>(idPreview2) != null else false
                    if (hasInline || hasPreview1 || hasPreview2) frames.add(child)
                }
            }
            if (frames.size < 2) return
            val firstIndex = group.indexOfChild(frames.first())
            if (firstIndex < 0) return
            val header = LinearLayout(ctx)
            header.orientation = LinearLayout.HORIZONTAL
            header.clipToPadding = false
            for (f in frames) {
                val lp = f.layoutParams
                group.removeView(f)
                header.addView(f, lp)
            }
            group.addView(header, firstIndex)
            logger.info("CompactImages: inserted header row above frames (${frames.size})")
        } catch (t: Throwable) {
            logger.error("CompactImages: insertHeaderRowAboveFrames error", t)
        }
    }
}
