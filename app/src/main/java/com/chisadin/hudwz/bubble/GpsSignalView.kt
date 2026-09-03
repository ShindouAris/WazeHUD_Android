package com.chisadin.hudwz.bubble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class GpsSignalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        val LEVEL_COLORS = intArrayOf(
            0xFF737B88.toInt(), // no/stale fix
            0xFFFF5964.toInt(), // very weak
            0xFFFFC247.toInt(), // weak
            0xFF37D5FF.toInt(), // good
            0xFF45E6B1.toInt(), // excellent
        )
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val target = RectF()
    private var fixIcon: Bitmap? = null
    private var lossIcon: Bitmap? = null
    var level: Int = 0
        private set

    init {
        loadIcons()
        setLevel(0)
    }

    private fun loadIcons() {
        fixIcon = Signs.loadAsset(context, "wz_ui_icon/gps.png")
        lossIcon = Signs.loadAsset(context, "wz_ui_icon/gps_signal_loss.png")
    }

    fun setLevel(value: Int) {
        val next = max(0, min(4, value))
        if (next == level && iconPaint.colorFilter != null) return
        level = next
        val color = LEVEL_COLORS[level]
        iconPaint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        contentDescription = if (level == 0) "Không có tín hiệu GPS" else "Chất lượng GPS $level trên 4"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val artwork = (if (level == 0) lossIcon else fixIcon) ?: fixIcon ?: lossIcon ?: return

        val scale = min(w / artwork.width.toFloat(), h / artwork.height.toFloat())
        val drawWidth = artwork.width * scale
        val drawHeight = artwork.height * scale
        val left = (w - drawWidth) * 0.5f
        val top = (h - drawHeight) * 0.5f
        target.set(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(artwork, null, target, iconPaint)
    }
}
