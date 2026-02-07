package io.github.juby210.acplugins

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.view.ViewGroup
import androidx.cardview.widget.CardView
import androidx.core.widget.NestedScrollView
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.rn.user.RNUserProfile
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.discord.stores.StoreStream
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.user.usersheet.WidgetUserSheetViewModel

@Suppress("unused")
@AliucordPlugin
class NewProfiles : Plugin() {
    override fun start(context: Context) {
        val userSettings = StoreStream.getUserSettingsSystem()
        patcher.after<WidgetUserSheet>("configureDeveloperSection", WidgetUserSheetViewModel.ViewState.Loaded::class.java) {
            val model = it.args[0] as WidgetUserSheetViewModel.ViewState.Loaded
            val profile = model.userProfile
            if (profile is RNUserProfile) {
                val themeColors = profile.guildMemberProfile?.run { themeColors ?: accentColor?.let { c -> intArrayOf(c, c) } }
                    ?: profile.userProfile?.run { themeColors ?: accentColor?.let { c -> intArrayOf(c, c) } } ?: return@after
                val binding = WidgetUserSheet.`access$getBinding$p`(this)
                val actionsContainer = binding.D
                val root = actionsContainer.parent.parent.parent as NestedScrollView

                // Use semi-transparent overlays for backgrounds, fully opaque for cards/buttons
                actionsContainer.setBackgroundColor(0)
                binding.J.apply { // header
                    setBackgroundColor(0)
                    (parent as View).setBackgroundColor(0)
                }

                // Find a CardView ancestor that contains both buttons and make it transparent/flat
                fun findAncestorCard(v: View): CardView? {
                    var p: Any? = v.parent
                    while (p is View) {
                        if (p is CardView) return p
                        p = p.parent
                    }
                    return null
                }

                fun isDescendant(parent: ViewGroup, child: View): Boolean {
                    var p: Any? = child.parent
                    while (p is View) {
                        if (p === parent) return true
                        p = p.parent
                    }
                    return false
                }

                // Helper to add alpha to color
                fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

                // Use more muted overlays to match RN
                val cardAlpha = 0xD0 // ~82% opaque
                val cardColor = withAlpha(themeColors[0], cardAlpha)
                val ancestorCard = findAncestorCard(binding.h)

                val cardViews = listOf(binding.b, binding.R, binding.j, (binding.n.parent as CardView), (binding.B.parent as CardView))
                cardViews.forEach { card ->
                    // Skip styling the ancestor card that contains the two profile buttons
                    if (ancestorCard != null && card === ancestorCard) return@forEach
                    card.setCardBackgroundColor(cardColor)
                    card.radius = 32f // More RN-like rounded corners
                    card.cardElevation = 0f // Flat
                    card.maxCardElevation = 0f
                }

                // Find the container that holds the profile edit buttons more robustly and stack only those buttons
                fun findEditButtonsContainer(start: ViewGroup): ViewGroup? {
                    val q = ArrayDeque<ViewGroup>()
                    q.add(start)
                    while (q.isNotEmpty()) {
                        val vg = q.removeFirst()
                        var btnCount = 0
                        for (i in 0 until vg.childCount) {
                            val c = vg.getChildAt(i)
                            if (c is Button && c.visibility == View.VISIBLE) btnCount++
                        }
                        if (btnCount >= 2) {
                            // check if at least one button has an id entry name hinting it's profile edit
                            var hasEditHint = false
                            for (i in 0 until vg.childCount) {
                                val c = vg.getChildAt(i)
                                if (c is Button && c.id != View.NO_ID) {
                                    try {
                                        val name = vg.context.resources.getResourceEntryName(c.id)
                                        if (name.contains("profile_edit") || name.contains("profile_identity") || name.contains("profile_actions") || name.contains("edit_profile")) {
                                            hasEditHint = true
                                            break
                                        }
                                    } catch (_: Throwable) { }
                                }
                            }
                            if (hasEditHint) return vg
                        }
                        for (i in 0 until vg.childCount) {
                            val c = vg.getChildAt(i)
                            if (c is ViewGroup) q.add(c)
                        }
                    }
                    return null
                }

                val container = findEditButtonsContainer(root as ViewGroup) ?: findEditButtonsContainer(actionsContainer)
                logger.info("[NewProfiles] found edit container=${container?.javaClass?.name}")
                if (container != null) {
                    container.post {
                        val buttons = mutableListOf<Button>()
                        for (i in 0 until container.childCount) {
                            val c = container.getChildAt(i)
                            if (c is Button && c.visibility == View.VISIBLE) buttons.add(c)
                        }
                        if (buttons.size >= 2) {
                            // keep only the first two visible buttons (edit default, edit server)
                            val btn1 = buttons[0]
                            val btn2 = buttons[1]
                            val parent = btn1.parent as? ViewGroup
                            if (parent != null) {
                                val idx1 = parent.indexOfChild(btn1)
                                val idx2 = parent.indexOfChild(btn2)
                                val minIdx = kotlin.math.min(idx1, idx2)
                                val maxIdx = kotlin.math.max(idx1, idx2)
                                // remove higher index first
                                if (maxIdx >= 0) parent.removeViewAt(maxIdx)
                                if (minIdx >= 0) parent.removeViewAt(minIdx)
                                val newLinear = android.widget.LinearLayout(btn1.context)
                                newLinear.orientation = android.widget.LinearLayout.VERTICAL
                                newLinear.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                parent.addView(newLinear, minIdx)
                                val lp = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                val gap = (btn1.context.resources.displayMetrics.density * 8).toInt()
                                lp.topMargin = 0
                                newLinear.addView(btn1, lp)
                                val lp2 = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                lp2.topMargin = gap
                                newLinear.addView(btn2, lp2)
                                logger.info("[NewProfiles] stacked two buttons into newLinear; children=${newLinear.childCount}")
                                // Style the actual buttons we moved so they use the card color and rounded corners
                                try {
                                    val density = btn1.context.resources.displayMetrics.density
                                    val corner = (36f * density)
                                    val drawable1 = GradientDrawable().apply {
                                        cornerRadius = corner
                                        setColor(cardColor)
                                    }
                                    val drawable2 = GradientDrawable().apply {
                                        cornerRadius = corner
                                        setColor(cardColor)
                                    }
                                    btn1.background = drawable1
                                    btn2.background = drawable2
                                    btn1.setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
                                    btn2.setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
                                    (btn1 as? android.widget.TextView)?.setTextColor(android.graphics.Color.WHITE)
                                    (btn2 as? android.widget.TextView)?.setTextColor(android.graphics.Color.WHITE)
                                } catch (_: Throwable) {}
                            }
                        } else {
                            logger.info("[NewProfiles] not enough buttons in container: ${buttons.size}")
                        }
                    }
                }

                // Style the two profile buttons directly, stacked with spacing
                // Use the same color as the cardColor so they match the parent card visually
                val buttonColor = cardColor
                val buttonList = listOf(binding.h, binding.I)
                buttonList.forEachIndexed { idx, btn ->
                    val density = btn.context.resources.displayMetrics.density
                    val corner = (36f * density)
                    val drawable = GradientDrawable().apply {
                        cornerRadius = corner
                        setColor(buttonColor)
                    }
                    // Apply drawable and tint (defensive against Material backgrounds)
                    btn.background = drawable
                    try {
                        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(buttonColor)
                    } catch (_: Throwable) {}
                    // Padding to make buttons look RN-like
                    val vert = (8 * density).toInt()
                    val hor = (12 * density).toInt()
                    btn.setPadding(hor, vert, hor, vert)
                    // Ensure buttons fill width in their new vertical container and add top margin for spacing
                    val lp = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = if (idx == 0) 0 else (btn.context.resources.displayMetrics.density * 8).toInt()
                    btn.layoutParams = lp
                    // Set readable text color based on background luminance
                    try {
                        val c = buttonColor
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        val lum = 0.299 * r + 0.587 * g + 0.114 * b
                        (btn as? android.widget.TextView)?.setTextColor(if (lum < 128) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                    } catch (_: Throwable) {}
                }

                // Make the original parent card background transparent (do not change its corners/elevation)
                try {
                    if (ancestorCard != null && isDescendant(ancestorCard as ViewGroup, binding.I)) {
                        ancestorCard.setCardBackgroundColor(0)
                    }
                } catch (_: Throwable) {}

                // Note field: muted
                binding.B.apply {
                    boxBackgroundColor = cardColor
                    (parent as CardView).setCardBackgroundColor(cardColor)
                }
                binding.A.setBackgroundColor(0)

                // Connections: muted
                val connAlpha = 0xC0 // ~75% opaque
                val connColor = withAlpha(themeColors[1], connAlpha)
                binding.n.apply {
                    setBackgroundColor(connColor)
                    (parent as CardView).setCardBackgroundColor(connColor)
                }

                // Muted, RN-style gradient background
                val gradAlpha = 0xB0 // ~69% opaque
                val gradColors = intArrayOf(withAlpha(themeColors[0], gradAlpha), withAlpha(themeColors[0], gradAlpha), withAlpha(themeColors[1], gradAlpha))
                root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, gradColors).apply {
                    cornerRadius = 0f
                }
            }
        }
    }

    override fun stop(context: Context) = patcher.unpatchAll()
}
