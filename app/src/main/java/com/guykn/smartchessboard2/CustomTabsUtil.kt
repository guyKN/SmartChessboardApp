package com.guykn.smartchessboard2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.res.ResourcesCompat


fun openCustomChromeTab(context: Context, url: String) {
    val backIconDrawable = ResourcesCompat.getDrawable(
        context.resources,
        R.drawable.ic_baseline_arrow_back_24,
        context.theme
    )

    val customTabsIntentBuilder = CustomTabsIntent.Builder()
        .setUrlBarHidingEnabled(true)
        .setShowTitle(false)
    backIconDrawable?.toBitmap()?.let { backIconBitmap ->
        customTabsIntentBuilder.setCloseButtonIcon(backIconBitmap)
    }
    val customTabsIntent = customTabsIntentBuilder.build()

    customTabsIntent.launchUrl(context, Uri.parse(url))
}

fun Drawable.toBitmap(): Bitmap? {
    if (this is BitmapDrawable) {
        return this.bitmap
    }
    val bitmap =
        Bitmap.createBitmap(
            intrinsicWidth,
            intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}
