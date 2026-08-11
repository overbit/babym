package com.localbabymonitor.app

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import kotlin.math.max

/**
 * Keeps important UI outside status/navigation bars and physical display cutouts.
 * The app targets API 36, where edge-to-edge is enforced on recent Android versions.
 */
fun Activity.applySafeArea(root: View) {
    // Before Android 15 this app uses the platform default non-edge-to-edge window,
    // so the decor already keeps content below system bars and cutouts. Adding the
    // same inset again would create an oversized top/bottom gap.
    if (Build.VERSION.SDK_INT < 35) return

    val initialLeft = root.paddingLeft
    val initialTop = root.paddingTop
    val initialRight = root.paddingRight
    val initialBottom = root.paddingBottom

    root.setOnApplyWindowInsetsListener { view, insets ->
        val safe = if (Build.VERSION.SDK_INT >= 30) {
            val values = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            SafeInsets(values.left, values.top, values.right, values.bottom)
        } else {
            @Suppress("DEPRECATION")
            var left = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            var top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            var right = insets.systemWindowInsetRight
            @Suppress("DEPRECATION")
            var bottom = insets.systemWindowInsetBottom

            if (Build.VERSION.SDK_INT >= 28) {
                insets.displayCutout?.let { cutout ->
                    left = max(left, cutout.safeInsetLeft)
                    top = max(top, cutout.safeInsetTop)
                    right = max(right, cutout.safeInsetRight)
                    bottom = max(bottom, cutout.safeInsetBottom)
                }
            }
            SafeInsets(left, top, right, bottom)
        }

        view.setPadding(
            initialLeft + safe.left,
            initialTop + safe.top,
            initialRight + safe.right,
            initialBottom + safe.bottom
        )
        insets
    }
    root.requestApplyInsets()
}

private data class SafeInsets(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)
