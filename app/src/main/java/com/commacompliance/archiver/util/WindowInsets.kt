package com.commacompliance.archiver.util

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Inset a screen's root view so its content clears the status bar, the navigation
 * bar, any display cutout, and the on-screen keyboard.
 *
 * This is required, not cosmetic. Android 15 began enforcing edge-to-edge for apps
 * targeting SDK 35+, and Android 16 removed the `windowOptOutEdgeToEdgeEnforcement`
 * escape hatch entirely for apps targeting SDK 36. An app that does not consume the
 * insets itself gets its content drawn UNDER the system bars: on these screens that
 * means the brand header sits behind the status bar and - the part that actually
 * breaks the product - the Connect / Disconnect / Self-test buttons at the bottom
 * sit behind the navigation bar where they cannot be tapped.
 *
 * The view's own padding from the layout XML is captured on first application and
 * the insets are ADDED to it, so calling this never discards the designed 24dp
 * gutter, and repeated calls (every inset dispatch) stay idempotent rather than
 * compounding.
 *
 * `systemBars() or displayCutout()` covers the bars plus a notch/punch-hole in
 * landscape; `ime()` keeps the focused field visible when the keyboard opens on the
 * organization-URL entry. The union is taken so the largest applicable inset on
 * each edge wins.
 */
fun View.applySystemWindowInsetsAsPadding() {
    val initial = Insets.of(paddingLeft, paddingTop, paddingRight, paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val bars = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime(),
        )
        view.setPadding(
            initial.left + bars.left,
            initial.top + bars.top,
            initial.right + bars.right,
            initial.bottom + bars.bottom,
        )
        // Return the insets unconsumed: these screens are a single scrolling root,
        // so nothing below competes for them, and passing them through keeps any
        // future child listener (or an included sub-layout) able to react.
        windowInsets
    }
    // The listener only fires on the next inset dispatch. A view that is already
    // attached (e.g. re-entering the screen) has had its dispatch, so ask for a
    // fresh one rather than waiting for an unrelated configuration change.
    ViewCompat.requestApplyInsets(this)
}
