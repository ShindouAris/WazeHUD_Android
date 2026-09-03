package com.chisadin.hudwz.bubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.chisadin.hudwz.domain.LaneGuidance
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class LaneGuidanceStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val SELECTED = Color.WHITE
        private const val ALTERNATIVE = 0xFF6F7278.toInt()
        private const val SEPARATOR = 0x66FFFFFF

        private fun laneAngle(directionBit: Int): Int = when (directionBit) {
            0 -> 0       // straight
            1 -> -90     // left
            2 -> 90      // right
            3 -> -45     // slight left
            4 -> 45      // slight right
            5 -> -135    // sharp left
            6 -> 135     // sharp right
            7 -> 180     // u-turn
            else -> 0
        }
    }

    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val separator = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SEPARATOR
    }
    private var lanes: List<LaneGuidance> = emptyList()

    init {
        visibility = GONE
    }

    fun setLanes(newVal: List<LaneGuidance>): Boolean {
        if (newVal == lanes) return false
        lanes = newVal
        val hasLanes = lanes.isNotEmpty()
        visibility = if (hasLanes) VISIBLE else GONE
        invalidate()
        requestLayout()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = lanes.size
        if (count <= 0 || width <= 0 || height <= 0) return

        val laneWidth = width.toFloat() / count.toFloat()
        val h = height.toFloat()
        separator.strokeWidth = max(1f, h * 0.025f)

        for (i in 0 until count) {
            if (i > 0) {
                val x = i * laneWidth
                canvas.drawLine(x, h * 0.24f, x, h * 0.78f, separator)
            }
            val left = i * laneWidth
            val lane = lanes[i]
            val bits = (0..7).filter { lane.directionsMask and (1 shl it) != 0 }

            // Draw unselected first so selected arrows render on top
            bits.forEach { bit ->
                val selected = (lane.selectedMask and (1 shl bit)) != 0
                if (!selected) {
                    drawArrow(canvas, left, laneWidth, h, laneAngle(bit), false)
                }
            }
            bits.forEach { bit ->
                val selected = (lane.selectedMask and (1 shl bit)) != 0
                if (selected) {
                    drawArrow(canvas, left, laneWidth, h, laneAngle(bit), true)
                }
            }
        }
    }

    private fun drawArrow(
        canvas: Canvas,
        left: Float,
        width: Float,
        height: Float,
        angleDegrees: Int,
        selected: Boolean,
    ) {
        val color = if (selected) SELECTED else ALTERNATIVE
        val stroke = max(2f, min(width, height) * (if (selected) 0.115f else 0.10f))
        arrow.color = color
        arrow.strokeWidth = stroke
        head.color = color

        val cx = left + width * 0.5f
        val startY = height * 0.83f
        val bendY = height * 0.53f
        val length = min(width * 0.35f, height * 0.30f)
        val degrees = max(-180, min(180, angleDegrees))

        if (abs(degrees) >= 150) {
            drawUTurn(canvas, cx, startY, width, height, degrees < 0, stroke)
            return
        }

        val radians = Math.toRadians(degrees.toDouble())
        val dx = sin(radians).toFloat()
        val dy = (-cos(radians)).toFloat()
        val endX = cx + dx * length
        val endY = bendY + dy * length
        val headLength = stroke * 2.25f
        val shaftEndX = endX - dx * headLength * 0.52f
        val shaftEndY = endY - dy * headLength * 0.52f

        val body = Path().apply {
            moveTo(cx, startY)
            lineTo(cx, bendY + max(0f, dy) * length * 0.18f)
            quadTo(cx, bendY, shaftEndX, shaftEndY)
        }
        canvas.drawPath(body, arrow)
        drawHead(canvas, endX, endY, dx, dy, stroke)
    }

    private fun drawUTurn(
        canvas: Canvas,
        cx: Float,
        startY: Float,
        width: Float,
        height: Float,
        leftTurn: Boolean,
        stroke: Float,
    ) {
        val side = if (leftTurn) -1f else 1f
        val radius = min(width * 0.24f, height * 0.18f)
        val top = height * 0.27f
        val body = Path().apply {
            moveTo(cx, startY)
            lineTo(cx, top + radius)
            cubicTo(
                cx, top - radius * 0.25f,
                cx + side * radius * 2f, top - radius * 0.25f,
                cx + side * radius * 2f, top + radius - stroke * 1.17f,
            )
        }
        canvas.drawPath(body, arrow)
        drawHead(canvas, cx + side * radius * 2f, top + radius, 0f, 1f, stroke)
    }

    private fun drawHead(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float, stroke: Float) {
        val length = stroke * 2.25f
        val halfWidth = stroke * 1.25f
        val px = -dy
        val py = dx
        val triangle = Path().apply {
            moveTo(x, y)
            lineTo(x - dx * length + px * halfWidth, y - dy * length + py * halfWidth)
            lineTo(x - dx * length - px * halfWidth, y - dy * length - py * halfWidth)
            close()
        }
        canvas.drawPath(triangle, head)
    }
}
