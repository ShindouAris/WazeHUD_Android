package com.chisadin.hudwz.ui.hud

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

@Composable
internal fun rememberHudNumberFont(): FontFamily {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            FontFamily(Typeface.createFromAsset(context.assets, "fonts/font_number.ttf"))
        }.getOrDefault(FontFamily.SansSerif)
    }
}

@Composable
internal fun rememberHudTextFont(): FontFamily {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            FontFamily(Typeface.createFromAsset(context.assets, "fonts/font_text.otf"))
        }.getOrDefault(FontFamily.SansSerif)
    }
}
