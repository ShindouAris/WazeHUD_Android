package com.chisadin.hudwz.ui.hud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.GpsOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Image as ImageIcon
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.chisadin.hudwz.domain.HudAlert
import com.chisadin.hudwz.domain.HudElementConfig
import com.chisadin.hudwz.domain.HudElementOrientation
import com.chisadin.hudwz.domain.HudFontWeight
import com.chisadin.hudwz.domain.HudProfile
import com.chisadin.hudwz.domain.HudState
import com.chisadin.hudwz.domain.HudTextAlignment
import com.chisadin.hudwz.domain.HudWidgetType
import com.chisadin.hudwz.domain.LaneGuidance
import com.chisadin.hudwz.domain.TurnType
import com.chisadin.hudwz.domain.defaultHudElement
import com.chisadin.hudwz.domain.locksAspectRatio
import com.chisadin.hudwz.sensor.headingToDirectionText
import com.chisadin.hudwz.sensor.rememberDeviceHeading
import com.chisadin.hudwz.ui.theme.HudCyan
import com.chisadin.hudwz.ui.theme.HudMuted
import com.chisadin.hudwz.ui.theme.HudRed
import com.chisadin.hudwz.ui.theme.HudSurface
import com.chisadin.hudwz.ui.theme.HudSurfaceHigh
import com.chisadin.hudwz.ui.theme.HudText
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SnapCandidate(
    val target: Float,
    val guide: Float,
    val distance: Float,
)

@Composable
fun HudRenderer(
    state: HudState,
    profile: HudProfile,
    mirror: Boolean,
    fontScale: Float,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    selectedId: String? = null,
    showInactiveInEditor: Boolean = false,
    forcePortrait: Boolean? = null,
    onSelect: (String) -> Unit = {},
    onDoubleTap: (String) -> Unit = {},
    onDragStart: (() -> Unit)? = null,
    onElementChange: (HudElementConfig) -> Unit = {},
) {
    BoxWithConstraints(
        modifier = modifier
            .background(Color.Black)
            .graphicsLayer(scaleX = if (mirror && !editing) -1f else 1f),
    ) {
        val density = LocalDensity.current
        val canvasWidthPx = constraints.maxWidth.toFloat()
        val canvasHeightPx = constraints.maxHeight.toFloat()
        val canvasWidthDp = with(density) { canvasWidthPx.toDp().value }
        val canvasHeightDp = with(density) { canvasHeightPx.toDp().value }
        val profileScale = profile.hudScale
        val isPortrait = forcePortrait ?: (canvasHeightDp > canvasWidthDp)
        val activeElements = profile.elementsFor(isPortrait)
        var activeSnapX by remember { mutableStateOf<Float?>(null) }
        var activeSnapY by remember { mutableStateOf<Float?>(null) }
        activeElements
            .filter { it.visible || (editing && showInactiveInEditor) }
            .filter { editing || shouldRenderWidget(it.type, state, it) }
            .forEach { element ->
            val latestElement by rememberUpdatedState(element)
            val widthDp = element.widthDp * element.scale * profileScale
            val sourceHeightDp = if (element.type.locksAspectRatio) element.widthDp else element.heightDp
            val heightDp = sourceHeightDp * element.scale * profileScale
            val maxX = (canvasWidthDp - widthDp).coerceAtLeast(0f)
            val maxY = (canvasHeightDp - heightDp).coerceAtLeast(0f)
            val xPx = with(density) { element.x.coerceIn(0f, maxX).dp.toPx() }
            val yPx = with(density) { element.y.coerceIn(0f, maxY).dp.toPx() }
            var elementModifier = Modifier
                .requiredSize(widthDp.dp, heightDp.dp)
                .graphicsLayer(alpha = if (element.visible) element.opacity else .22f)
                .semantics { contentDescription = widgetDescription(element.type, state) }
            if (editing) {
                elementModifier = elementModifier
                    .combinedClickable(
                        onClick = { onSelect(element.id) },
                        onDoubleClick = { onDoubleTap(element.id) },
                    )
                    .pointerInput(element.id, maxX, maxY, element.locked) {
                        if (element.locked) return@pointerInput
                        var dragX = element.x
                        var dragY = element.y
                        detectDragGestures(
                            onDragStart = {
                                onDragStart?.invoke()
                                dragX = latestElement.x
                                dragY = latestElement.y
                                onSelect(latestElement.id)
                            },
                            onDragEnd = {
                                activeSnapX = null
                                activeSnapY = null
                            },
                            onDragCancel = {
                                activeSnapX = null
                                activeSnapY = null
                            },
                        ) { change, amount ->
                            change.consume()
                            var targetX = (dragX + amount.x / density.density).coerceIn(0f, maxX)
                            var targetY = (dragY + amount.y / density.density).coerceIn(0f, maxY)

                            val snapThreshold = 8.5f
                            val otherElements = activeElements.filter {
                                it.id != latestElement.id && (it.visible || (editing && showInactiveInEditor))
                            }

                            // 1. Dính cạnh & tâm theo trục X (Component-to-Component & Canvas)
                            val snapCandidatesX = mutableListOf<SnapCandidate>()
                            for (other in otherElements) {
                                val otherW = other.widthDp * other.scale * profileScale
                                val otherLeft = other.x
                                val otherRight = other.x + otherW
                                val otherCenterX = other.x + otherW / 2f

                                // Cạnh trái trùng cạnh trái
                                val dLeftLeft = kotlin.math.abs(targetX - otherLeft)
                                if (dLeftLeft <= snapThreshold) snapCandidatesX.add(SnapCandidate(otherLeft, otherLeft, dLeftLeft))

                                // Cạnh phải trùng cạnh phải
                                val dRightRight = kotlin.math.abs((targetX + widthDp) - otherRight)
                                if (dRightRight <= snapThreshold) snapCandidatesX.add(SnapCandidate(otherRight - widthDp, otherRight, dRightRight))

                                // Cạnh trái dính cạnh phải component khác (kề nhau)
                                val dLeftRight = kotlin.math.abs(targetX - otherRight)
                                if (dLeftRight <= snapThreshold) snapCandidatesX.add(SnapCandidate(otherRight, otherRight, dLeftRight))

                                // Cạnh phải dính cạnh trái component khác (kề nhau)
                                val dRightLeft = kotlin.math.abs((targetX + widthDp) - otherLeft)
                                if (dRightLeft <= snapThreshold) snapCandidatesX.add(SnapCandidate(otherLeft - widthDp, otherLeft, dRightLeft))

                                // Tâm dọc trùng tâm dọc
                                val dCenterCenter = kotlin.math.abs((targetX + widthDp / 2f) - otherCenterX)
                                if (dCenterCenter <= snapThreshold) snapCandidatesX.add(SnapCandidate(otherCenterX - widthDp / 2f, otherCenterX, dCenterCenter))
                            }

                            val bestX = snapCandidatesX.minByOrNull { it.distance }
                            if (bestX != null) {
                                targetX = bestX.target.coerceIn(0f, maxX)
                                activeSnapX = bestX.guide
                            } else {
                                val canvasCenterX = canvasWidthDp / 2f
                                val elemCenterX = targetX + widthDp / 2f
                                val dCanvasCenter = kotlin.math.abs(elemCenterX - canvasCenterX)
                                val dLeftEdge = kotlin.math.abs(targetX - 16f)
                                val dRightEdge = if (maxX >= 32f) kotlin.math.abs(targetX - (maxX - 16f)) else Float.MAX_VALUE

                                when {
                                    dCanvasCenter <= snapThreshold -> {
                                        targetX = (canvasCenterX - widthDp / 2f).coerceIn(0f, maxX)
                                        activeSnapX = canvasCenterX
                                    }
                                    dLeftEdge <= snapThreshold -> {
                                        targetX = 16f.coerceIn(0f, maxX)
                                        activeSnapX = 16f
                                    }
                                    dRightEdge <= snapThreshold -> {
                                        targetX = (maxX - 16f).coerceIn(0f, maxX)
                                        activeSnapX = canvasWidthDp - 16f
                                    }
                                    else -> {
                                        activeSnapX = null
                                    }
                                }
                            }

                            // 2. Dính cạnh & tâm theo trục Y (Component-to-Component & Canvas)
                            val snapCandidatesY = mutableListOf<SnapCandidate>()
                            for (other in otherElements) {
                                val otherSourceH = if (other.type.locksAspectRatio) other.widthDp else other.heightDp
                                val otherH = otherSourceH * other.scale * profileScale
                                val otherTop = other.y
                                val otherBottom = other.y + otherH
                                val otherCenterY = other.y + otherH / 2f

                                // Mép trên trùng mép trên
                                val dTopTop = kotlin.math.abs(targetY - otherTop)
                                if (dTopTop <= snapThreshold) snapCandidatesY.add(SnapCandidate(otherTop, otherTop, dTopTop))

                                // Mép dưới trùng mép dưới
                                val dBottomBottom = kotlin.math.abs((targetY + heightDp) - otherBottom)
                                if (dBottomBottom <= snapThreshold) snapCandidatesY.add(SnapCandidate(otherBottom - heightDp, otherBottom, dBottomBottom))

                                // Mép trên dính mép dưới component khác (xếp chồng)
                                val dTopBottom = kotlin.math.abs(targetY - otherBottom)
                                if (dTopBottom <= snapThreshold) snapCandidatesY.add(SnapCandidate(otherBottom, otherBottom, dTopBottom))

                                // Mép dưới dính mép trên component khác (xếp chồng)
                                val dBottomTop = kotlin.math.abs((targetY + heightDp) - otherTop)
                                if (dBottomTop <= snapThreshold) snapCandidatesY.add(SnapCandidate(otherTop - heightDp, otherTop, dBottomTop))

                                // Tâm ngang trùng tâm ngang
                                val dCenterCenter = kotlin.math.abs((targetY + heightDp / 2f) - otherCenterY)
                                if (dCenterCenter <= snapThreshold) snapCandidatesY.add(SnapCandidate(otherCenterY - heightDp / 2f, otherCenterY, dCenterCenter))
                            }

                            val bestY = snapCandidatesY.minByOrNull { it.distance }
                            if (bestY != null) {
                                targetY = bestY.target.coerceIn(0f, maxY)
                                activeSnapY = bestY.guide
                            } else {
                                val canvasCenterY = canvasHeightDp / 2f
                                val elemCenterY = targetY + heightDp / 2f
                                val dCanvasCenter = kotlin.math.abs(elemCenterY - canvasCenterY)
                                val dTopEdge = kotlin.math.abs(targetY - 16f)
                                val dBottomEdge = if (maxY >= 32f) kotlin.math.abs(targetY - (maxY - 16f)) else Float.MAX_VALUE

                                when {
                                    dCanvasCenter <= snapThreshold -> {
                                        targetY = (canvasCenterY - heightDp / 2f).coerceIn(0f, maxY)
                                        activeSnapY = canvasCenterY
                                    }
                                    dTopEdge <= snapThreshold -> {
                                        targetY = 16f.coerceIn(0f, maxY)
                                        activeSnapY = 16f
                                    }
                                    dBottomEdge <= snapThreshold -> {
                                        targetY = (maxY - 16f).coerceIn(0f, maxY)
                                        activeSnapY = canvasHeightDp - 16f
                                    }
                                    else -> {
                                        activeSnapY = null
                                    }
                                }
                            }

                            dragX = targetX
                            dragY = targetY
                            onElementChange(latestElement.copy(x = targetX, y = targetY))
                        }
                    }
            }
            Box(
                modifier = Modifier.offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) },
            ) {
                HudWidget(state, element, fontScale, elementModifier, editing)
                if (editing && selectedId == element.id) {
                    FocusFrame(
                        modifier = Modifier.requiredSize(widthDp.dp, heightDp.dp),
                        element = element,
                        densityScale = profileScale,
                        canvasWidthDp = canvasWidthDp,
                        canvasHeightDp = canvasHeightDp,
                        onDragStart = onDragStart,
                        onElementChange = onElementChange,
                    )
                }
            }
        }
        if (editing) {
            Canvas(Modifier.fillMaxSize()) {
                activeSnapX?.let { snapX ->
                    val xPx = snapX.dp.toPx()
                    drawLine(
                        color = HudCyan.copy(alpha = 0.75f),
                        start = Offset(xPx, 0f),
                        end = Offset(xPx, size.height),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                    )
                }
                activeSnapY?.let { snapY ->
                    val yPx = snapY.dp.toPx()
                    drawLine(
                        color = HudCyan.copy(alpha = 0.75f),
                        start = Offset(0f, yPx),
                        end = Offset(size.width, yPx),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                    )
                }
            }
        }
        if (state.overspeed && !editing) OverspeedVignette()
    }
}

@Composable
private fun FocusFrame(
    modifier: Modifier,
    element: HudElementConfig,
    densityScale: Float,
    canvasWidthDp: Float,
    canvasHeightDp: Float,
    onDragStart: (() -> Unit)? = null,
    onElementChange: (HudElementConfig) -> Unit,
) {
    val frameColor = if (element.locked) Color(0xFFFFB300) else HudCyan
    Box(modifier.semantics { contentDescription = "Đang chọn ${element.type.name}" }) {
        Canvas(Modifier.fillMaxSize()) {
            val thin = 1.dp.toPx()
            val strong = 2.5.dp.toPx()
            val corner = 15.dp.toPx().coerceAtMost(size.minDimension * .32f)
            val inset = strong / 2f
            drawRoundRect(
                color = frameColor.copy(alpha = if (element.locked) .6f else .42f),
                topLeft = Offset(inset, inset),
                size = Size((size.width - strong).coerceAtLeast(0f), (size.height - strong).coerceAtLeast(0f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()),
                style = Stroke(thin),
            )
            drawLine(frameColor, Offset(inset, inset), Offset(inset + corner, inset), strong, StrokeCap.Square)
            drawLine(frameColor, Offset(inset, inset), Offset(inset, inset + corner), strong, StrokeCap.Square)
            drawLine(frameColor, Offset(size.width - inset, inset), Offset(size.width - inset - corner, inset), strong, StrokeCap.Square)
            drawLine(frameColor, Offset(size.width - inset, inset), Offset(size.width - inset, inset + corner), strong, StrokeCap.Square)
            drawLine(frameColor, Offset(inset, size.height - inset), Offset(inset + corner, size.height - inset), strong, StrokeCap.Square)
            drawLine(frameColor, Offset(inset, size.height - inset), Offset(inset, size.height - inset - corner), strong, StrokeCap.Square)
            drawLine(frameColor, Offset(size.width - inset, size.height - inset), Offset(size.width - inset - corner, size.height - inset), strong, StrokeCap.Square)
            drawLine(frameColor, Offset(size.width - inset, size.height - inset), Offset(size.width - inset, size.height - inset - corner), strong, StrokeCap.Square)
        }
        if (element.locked) {
            Surface(
                color = Color(0xFFFFB300),
                shape = CircleShape,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp),
            ) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = "Đã khóa thành phần",
                    tint = Color.Black,
                    modifier = Modifier.padding(3.dp),
                )
            }
        } else {
            ResizeHandle(
                modifier = Modifier.align(Alignment.BottomEnd),
                element = element,
                densityScale = densityScale,
                aspectLocked = element.type.locksAspectRatio,
                canvasWidthDp = canvasWidthDp,
                canvasHeightDp = canvasHeightDp,
                onDragStart = onDragStart,
                onElementChange = onElementChange,
            )
        }
    }
}

@Composable
fun HudWidgetPreview(type: HudWidgetType, modifier: Modifier = Modifier) {
    val config = remember(type) {
        defaultHudElement(type, "preview").copy(
            iconSizeDp = 48f,
            fontSizeSp = when (type) {
                HudWidgetType.SPEED -> 34f
                HudWidgetType.SPEED_NUMBER -> 30f
                HudWidgetType.SPEED_LIMIT -> 24f
                HudWidgetType.SPEED_LIMIT_BAR, HudWidgetType.TRAFFIC_DELAY -> 13f
                else -> 15f
            },
            spacingDp = 2f,
        )
    }
    HudWidget(
        state = PreviewHudState,
        config = config,
        globalFontScale = .9f,
        modifier = modifier,
    )
}

@Composable
private fun ResizeHandle(
    modifier: Modifier,
    element: HudElementConfig,
    densityScale: Float,
    aspectLocked: Boolean,
    canvasWidthDp: Float,
    canvasHeightDp: Float,
    onDragStart: (() -> Unit)? = null,
    onElementChange: (HudElementConfig) -> Unit,
) {
    val density = LocalDensity.current
    val latestElement by rememberUpdatedState(element)
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(HudSurface)
            .border(2.dp, HudCyan, CircleShape)
            .pointerInput(element.id) {
                var width = latestElement.widthDp
                var height = if (aspectLocked) latestElement.widthDp else latestElement.heightDp
                detectDragGestures(
                    onDragStart = {
                        onDragStart?.invoke()
                        width = latestElement.widthDp
                        height = if (aspectLocked) latestElement.widthDp else latestElement.heightDp
                    },
                ) { change, amount ->
                    change.consume()
                    if (aspectLocked) {
                        val delta = with(density) { ((amount.x + amount.y) / 2f).toDp().value } / densityScale
                        width = (width + delta).coerceIn(28f, 500f)
                        height = width
                        onElementChange(
                            latestElement.copy(
                                widthDp = width,
                                heightDp = height,
                                iconSizeDp = width * .9f,
                                scale = 1f,
                                x = latestElement.x.coerceAtMost((canvasWidthDp - width * densityScale).coerceAtLeast(0f)),
                                y = latestElement.y.coerceAtMost((canvasHeightDp - height * densityScale).coerceAtLeast(0f)),
                            ),
                        )
                    } else {
                        width = (width + with(density) { amount.x.toDp().value } / densityScale).coerceIn(32f, 600f)
                        height = (height + with(density) { amount.y.toDp().value } / densityScale).coerceIn(28f, 500f)
                        onElementChange(
                            latestElement.copy(
                                widthDp = width,
                                heightDp = height,
                                x = latestElement.x.coerceAtMost((canvasWidthDp - width * latestElement.scale * densityScale).coerceAtLeast(0f)),
                                y = latestElement.y.coerceAtMost((canvasHeightDp - height * latestElement.scale * densityScale).coerceAtLeast(0f)),
                            ),
                        )
                    }
                }
            },
    )
}

@Composable
private fun HudWidget(
    state: HudState,
    config: HudElementConfig,
    globalFontScale: Float,
    modifier: Modifier,
    editing: Boolean = false,
) {
    val weight = when (config.fontWeight) {
        HudFontWeight.NORMAL -> FontWeight.Normal
        HudFontWeight.BOLD -> FontWeight.Bold
        HudFontWeight.BLACK -> FontWeight.Black
    }
    val align = when (config.textAlignment) {
        HudTextAlignment.START -> TextAlign.Start
        HudTextAlignment.CENTER -> TextAlign.Center
        HudTextAlignment.END -> TextAlign.End
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (config.type) {
            HudWidgetType.SPEED -> SpeedDial(state.speed ?: 0, state.speedLimit, state.overspeed, config, globalFontScale)
            HudWidgetType.SPEED_NUMBER -> SpeedNumber(state.speed ?: 0, state.overspeed, config, globalFontScale, weight)
            HudWidgetType.SPEED_LIMIT -> SpeedLimitSign(state.speedLimit, config, globalFontScale)
            HudWidgetType.SPEED_LIMIT_BAR -> SpeedToLimitBar(state.speed ?: 0, state.speedLimit, state.overspeed)
            HudWidgetType.TURN -> ManeuverWidget(state.turn, state.roundaboutExit, config)
            HudWidgetType.NEXT_TURN -> ManeuverWidget(state.nextTurn, null, config)
            HudWidgetType.DISTANCE -> HudTextWidget(formatDistance(state.distanceMeters), config, globalFontScale, weight, align)
            HudWidgetType.STREET -> HudTextWidget(
                state.street.orEmpty().ifBlank { "Cầu đường chưa đặt tên" },
                config,
                globalFontScale,
                weight,
                align,
                marquee = true,
            )
            HudWidgetType.NEXT_STREET -> HudTextWidget(
                state.nextStreet.orEmpty().ifBlank { "Cầu đường chưa đặt tên" },
                config,
                globalFontScale,
                weight,
                align,
                marquee = true,
            )
            HudWidgetType.ETA -> HudTextWidget(state.eta?.takeIf { it.isNotBlank() && it != "--:--" } ?: (if (editing) "09:32" else "--:--"), config, globalFontScale, weight, align)
            HudWidgetType.REMAINING -> HudTextWidget(remainingText(state).ifBlank { if (editing) "18.5 km  ·  24 phút  ·  Đến lúc 09:32" else "" }, config, globalFontScale, weight, align)
            HudWidgetType.GPS -> StatusIcon(state.gpsAvailable, true, config)
            HudWidgetType.CONNECTION -> ConnectivityBatteryWidget(state.connected, config)
            HudWidgetType.ALERTS -> AlertRail(state.alerts, config, editing)
            HudWidgetType.LANES -> LaneStrip(state.lanes)
            HudWidgetType.TRAFFIC_DELAY -> TrafficDelayWidget(state.trafficDelayMinutes, state.trafficSeverity, config, globalFontScale)
            HudWidgetType.CUSTOM_TEXT -> HudTextWidget(
                config.customText.ifBlank { "Chữ tùy chỉnh" },
                config,
                globalFontScale,
                weight,
                align,
            )
            HudWidgetType.CUSTOM_IMAGE -> CustomImageWidget(config.customImageUri, config.spacingDp, editing)
            HudWidgetType.PHONE_BATTERY -> ConnectivityBatteryWidget(state.connected, config)
            HudWidgetType.CLOCK -> ClockWidget(config, globalFontScale, weight, align)
            HudWidgetType.COMPASS -> CompassWidget(state.bearingDegrees, config, globalFontScale, weight)
            HudWidgetType.TRIP_PROGRESS -> TripProgressWidget(state, config, editing)
        }
    }
}

@Composable
private fun ClockWidget(
    config: HudElementConfig,
    fontScale: Float,
    weight: FontWeight,
    align: TextAlign,
) {
    val numberFont = rememberHudNumberFont()
    var timeText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        while (true) {
            timeText = LocalTime.now().format(formatter)
            val delayMs = 1000L - (System.currentTimeMillis() % 1000L)
            kotlinx.coroutines.delay(delayMs)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fitted = min(
            config.fontSizeSp * fontScale,
            min(maxWidth.value * 0.48f, maxHeight.value * 0.88f),
        ).coerceAtLeast(8f)
        Text(
            text = timeText.ifBlank { "00:00" },
            color = HudText,
            fontSize = fitted.sp,
            fontWeight = weight,
            fontFamily = numberFont,
            textAlign = align,
            maxLines = 1,
        )
    }
}

@Composable
private fun CompassWidget(
    bearingDegrees: Float?,
    config: HudElementConfig,
    fontScale: Float,
    weight: FontWeight,
) {
    val heading = rememberDeviceHeading(bearingDegrees)
    val dirCode = headingToDirectionText(heading)
    val textFont = rememberHudTextFont()
    val numberFont = rememberHudNumberFont()

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val iconSize = min(config.iconSizeDp, min(maxWidth.value * 0.45f, maxHeight.value * 0.75f)).coerceAtLeast(14f)
        val textSize = min(config.fontSizeSp * fontScale, maxHeight.value * 0.58f).coerceAtLeast(8f)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((config.spacingDp * 0.8f).dp),
        ) {
            Box(
                Modifier.size(iconSize.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val radius = size.minDimension / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(
                        color = HudSurfaceHigh,
                        radius = radius,
                        center = center,
                    )
                    drawCircle(
                        color = HudCyan.copy(alpha = 0.5f),
                        radius = radius,
                        center = center,
                        style = Stroke(1.2.dp.toPx()),
                    )
                    val rad = Math.toRadians((-heading + 90.0)).toFloat()
                    val pX = (kotlin.math.cos(rad) * radius * 0.72f)
                    val pY = (-kotlin.math.sin(rad) * radius * 0.72f)
                    drawLine(
                        color = Color(0xFFFF3D00),
                        start = center,
                        end = Offset(center.x + pX, center.y + pY),
                        strokeWidth = 2.6.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.75f),
                        start = center,
                        end = Offset(center.x - pX, center.y - pY),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawCircle(color = Color.White, radius = 2.dp.toPx(), center = center)
                }
            }
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = dirCode,
                    color = HudCyan,
                    fontSize = textSize.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = textFont,
                    maxLines = 1,
                )
                Text(
                    text = "${heading.roundToInt()}°",
                    color = HudMuted,
                    fontSize = (textSize * 0.65f).coerceAtLeast(7f).sp,
                    fontWeight = weight,
                    fontFamily = numberFont,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TripProgressWidget(
    state: HudState,
    config: HudElementConfig,
    editing: Boolean,
) {
    val currentRemaining = state.remainingMeters ?: state.remainingKm?.let { (it * 1000).toInt() } ?: 0
    var maxDistanceMeters by remember(state.sessionId) { mutableIntStateOf(currentRemaining) }
    if (currentRemaining > maxDistanceMeters) {
        maxDistanceMeters = currentRemaining
    }
    val progressRatio = when {
        editing -> 0.68f
        maxDistanceMeters > 0 -> (1f - (currentRemaining.toFloat() / maxDistanceMeters.toFloat())).coerceIn(0.05f, 1f)
        state.remainingMinutes != null -> 0.5f
        else -> 0.2f
    }
    val percentInt = (progressRatio * 100).roundToInt()
    val textFont = rememberHudTextFont()
    val numberFont = rememberHudNumberFont()

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Lộ trình", color = HudMuted, fontSize = 9.sp, fontFamily = textFont)
                Text("$percentInt%", color = HudCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = numberFont)
            }
            Box(
                Modifier.fillMaxWidth().height(14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF163B66)),
                )
                Box(
                    Modifier
                        .fillMaxWidth(progressRatio)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF00E5FF), Color(0xFF2979FF)),
                            ),
                        ),
                )
                Box(
                    Modifier
                        .fillMaxWidth(progressRatio)
                        .offset(x = (-6).dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Surface(
                        color = HudCyan,
                        shape = CircleShape,
                        shadowElevation = 4.dp,
                        modifier = Modifier.size(14.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.Navigation,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(9.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedNumber(
    speed: Int,
    overspeed: Boolean,
    config: HudElementConfig,
    fontScale: Float,
    weight: FontWeight,
) {
    val numberFont = rememberHudNumberFont()
    val textFont = rememberHudTextFont()
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fitted = min(
            config.fontSizeSp * fontScale * 1.18f,
            min(maxWidth.value * .78f, maxHeight.value * .69f),
        ).coerceAtLeast(7f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = speed.coerceAtLeast(0).toString(),
                color = if (overspeed) HudRed else HudText,
                fontSize = fitted.sp,
                fontWeight = weight,
                fontFamily = numberFont,
                maxLines = 1,
            )
            Text(
                text = "Km/h",
                color = HudMuted,
                fontSize = (fitted * .30f).coerceAtLeast(7f).sp,
                fontWeight = FontWeight.Bold,
                fontFamily = textFont,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SpeedToLimitBar(
    speed: Int,
    limit: Int?,
    overspeed: Boolean,
) {
    val legalLimit = limit?.takeIf { it > 0 }
    Canvas(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp)) {
            val y = size.height / 2f
            val stroke = (size.height * .46f).coerceIn(3.dp.toPx(), 12.dp.toPx())
            val ratio = if (legalLimit != null) speed.coerceAtLeast(0) / legalLimit.toFloat() else 0f
            drawLine(Color(0xFF163B66), Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
            drawLine(
                color = if (overspeed || ratio > 1f) HudRed else Color(0xFF15B8FF),
                start = Offset(0f, y),
                end = Offset(size.width * ratio.coerceIn(0f, 1f), y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
    }
}

@Composable
private fun TrafficDelayWidget(
    delayMinutes: Int?,
    severity: Int?,
    config: HudElementConfig,
    fontScale: Float,
) {
    val level = severity?.coerceIn(1, 4) ?: 1
    val bitmap = rememberAssetBitmap("alerts/bigpin_traffic_$level.png")
    val numberFont = rememberHudNumberFont()
    val textFont = rememberHudTextFont()
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val iconSize = min(config.iconSizeDp, min(maxHeight.value * .82f, maxWidth.value * .34f)).coerceAtLeast(6f)
        val textSize = min(config.fontSizeSp * fontScale, maxHeight.value * .36f).coerceAtLeast(7f)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(config.spacingDp.dp),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Mức độ kẹt xe $level",
                    modifier = Modifier.size(iconSize.dp),
                )
            } else {
                Icon(
                    Icons.Rounded.Warning,
                    contentDescription = "Kẹt xe",
                    tint = HudRed,
                    modifier = Modifier.size(iconSize.dp),
                )
            }
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "+${delayMinutes ?: 0}",
                        color = HudText,
                        fontSize = textSize.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = textFont,
                        maxLines = 1,
                    )
                    Text(
                        text = " phút",
                        color = HudText,
                        fontSize = (textSize * .58f).coerceAtLeast(6f).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = textFont,
                        maxLines = 1,
                    )
                }
                Text(
                    text = "chậm do kẹt xe",
                    color = HudMuted,
                    fontSize = (textSize * .48f).coerceAtLeast(6f).sp,
                    fontFamily = textFont,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SpeedDial(
    speed: Int,
    limit: Int?,
    overspeed: Boolean,
    config: HudElementConfig,
    fontScale: Float,
) {
    val numberFont = rememberHudNumberFont()
    val textFont = rememberHudTextFont()
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fittedFont = min(config.fontSizeSp * fontScale, min(maxWidth.value, maxHeight.value) * .42f)
            .coerceAtLeast(6f)
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val stroke = size.minDimension * .055f
            val bounds = Rect(stroke, stroke, size.width - stroke, size.height - stroke)
            drawOval(HudSurface, topLeft = bounds.topLeft, size = bounds.size)
            drawArc(
                color = Color(0xFF27303B),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = bounds.topLeft,
                size = bounds.size,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val progress = (speed / (limit?.takeIf { it >= 10 }?.toFloat() ?: 160f)).coerceIn(0f, 1f)
            drawArc(
                brush = if (overspeed) Brush.linearGradient(listOf(HudRed, HudRed))
                else Brush.linearGradient(listOf(HudCyan, Color(0xFF4F7CFF))),
                startAngle = 135f,
                sweepAngle = 270f * progress,
                useCenter = false,
                topLeft = bounds.topLeft,
                size = bounds.size,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = speed.toString(),
                color = if (overspeed) HudRed else HudText,
                fontSize = fittedFont.sp,
                fontWeight = FontWeight.Black,
                fontFamily = numberFont,
                maxLines = 1,
            )
            Text(
                "km/h",
                color = HudMuted,
                fontSize = (fittedFont * .22f).coerceAtLeast(5f).sp,
                fontFamily = textFont,
            )
        }
    }
}

@Composable
private fun SpeedLimitSign(limit: Int?, config: HudElementConfig, fontScale: Float) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val size = min(maxWidth.value, maxHeight.value).coerceAtLeast(4f)
        GeneratedSpeedLimitSign(
            limit = limit,
            fontSizeSp = min(config.fontSizeSp * fontScale * 1.65f, size * .80f).coerceAtLeast(8f),
            modifier = Modifier.size(size.dp),
        )
    }
}

@Composable
private fun GeneratedSpeedLimitSign(
    limit: Int?,
    fontSizeSp: Float,
    modifier: Modifier = Modifier,
) {
    val numberFont = rememberHudNumberFont()
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            val radius = size.minDimension / 2f
            val border = (radius * .20f).coerceAtLeast(1.5.dp.toPx())
            drawCircle(Color.White, radius = radius, center = center)
            drawCircle(
                color = HudRed,
                radius = (radius - border / 2f).coerceAtLeast(0f),
                center = center,
                style = Stroke(border),
            )
        }
        Text(
            text = if (limit != null && limit > 0) limit.toString() else "?",
            color = Color.Black,
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Black,
            fontFamily = if (limit != null && limit > 0) numberFont else FontFamily.SansSerif,
            maxLines = 1,
        )
    }
}

@Composable
private fun ManeuverWidget(turn: TurnType, exit: Int?, config: HudElementConfig) {
    val bitmap = rememberAssetBitmap(turnAsset(turn))
    val numberFont = rememberHudNumberFont()
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val iconSize = (min(maxWidth.value, maxHeight.value) * .9f).coerceAtLeast(4f)
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Hướng di chuyển ${turn.name}",
                modifier = Modifier.size(iconSize.dp),
            )
        } else {
            ManeuverArrow(turn, Modifier.size(iconSize.dp))
        }
        if (turn in setOf(TurnType.ROUNDABOUT, TurnType.ROUNDABOUT_LEFT, TurnType.ROUNDABOUT_RIGHT, TurnType.ROUNDABOUT_STRAIGHT, TurnType.ROUNDABOUT_U_TURN) && exit != null) {
            Text(
                exit.toString(),
                color = HudText,
                fontSize = (iconSize * .17f).coerceAtLeast(6f).sp,
                fontWeight = FontWeight.Black,
                fontFamily = numberFont,
            )
        }
    }
}

@Composable
private fun ManeuverArrow(turn: TurnType, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (turn == TurnType.NONE) return@Canvas
        val stroke = size.minDimension * .10f
        val color = HudText
        if (turn in setOf(TurnType.ROUNDABOUT, TurnType.ROUNDABOUT_LEFT, TurnType.ROUNDABOUT_RIGHT, TurnType.ROUNDABOUT_STRAIGHT, TurnType.ROUNDABOUT_U_TURN)) {
            drawArc(
                color = color,
                startAngle = 30f,
                sweepAngle = 300f,
                useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round),
                topLeft = Offset(stroke, stroke),
                size = Size(size.width - stroke * 2, size.height - stroke * 2),
            )
            return@Canvas
        }
        val direction = when (turn) {
            TurnType.LEFT, TurnType.KEEP_LEFT, TurnType.EXIT_LEFT -> Offset(-1f, 0f)
            TurnType.RIGHT, TurnType.KEEP_RIGHT, TurnType.EXIT_RIGHT -> Offset(1f, 0f)
            TurnType.SLIGHT_LEFT -> Offset(-.65f, -.75f)
            TurnType.SLIGHT_RIGHT -> Offset(.65f, -.75f)
            TurnType.SHARP_LEFT -> Offset(-.85f, .45f)
            TurnType.SHARP_RIGHT -> Offset(.85f, .45f)
            TurnType.U_TURN, TurnType.U_TURN_RIGHT -> Offset(-.8f, .6f)
            else -> Offset(0f, -1f)
        }
        val start = Offset(size.width / 2f, size.height * .86f)
        val bend = Offset(size.width / 2f, size.height * .46f)
        val end = Offset(
            bend.x + direction.x * size.width * .32f,
            bend.y + direction.y * size.height * .32f,
        )
        val path = Path().apply {
            moveTo(start.x, start.y)
            lineTo(bend.x, bend.y)
            lineTo(end.x, end.y)
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val angle = kotlin.math.atan2(direction.y, direction.x)
        val head = stroke * 2.2f
        val left = Offset(
            end.x - cos(angle - PI.toFloat() / 6f) * head,
            end.y - sin(angle - PI.toFloat() / 6f) * head,
        )
        val right = Offset(
            end.x - cos(angle + PI.toFloat() / 6f) * head,
            end.y - sin(angle + PI.toFloat() / 6f) * head,
        )
        drawPath(Path().apply { moveTo(end.x, end.y); lineTo(left.x, left.y); lineTo(right.x, right.y); close() }, color)
    }
}

@Composable
private fun HudTextWidget(
    text: String,
    config: HudElementConfig,
    fontScale: Float,
    weight: FontWeight,
    align: TextAlign,
    marquee: Boolean = false,
) {
    val textModifier = Modifier
        .fillMaxSize()
        .padding(config.spacingDp.dp)
        .then(if (marquee) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier)
    Text(
        text = text,
        color = HudText,
        fontSize = (config.fontSizeSp * fontScale).sp,
        fontWeight = weight,
        textAlign = align,
        maxLines = if (marquee) 1 else 2,
        softWrap = !marquee,
        modifier = textModifier,
    )
}

@Composable
private fun CustomImageWidget(uri: String?, spacingDp: Float, editing: Boolean = false) {
    val bitmap = rememberCustomImageBitmap(uri)
    Box(
        Modifier
            .fillMaxSize()
            .padding(spacingDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Ảnh tùy chỉnh",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (editing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(HudSurface.copy(alpha = 0.7f))
                    .border(1.dp, HudCyan.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Rounded.ImageIcon,
                        contentDescription = "Chưa chọn ảnh tùy chỉnh",
                        tint = HudCyan,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = "Chọn ảnh",
                        color = HudText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectivityBatteryWidget(connected: Boolean, config: HudElementConfig) {
    val battery = rememberPhoneBatteryState()
    val numberFont = rememberHudNumberFont()
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val compact = maxWidth.value < 105f || maxHeight.value < 32f
        val availableHeight = maxHeight.value
        val iconHeight = min(config.iconSizeDp, maxHeight.value * .62f).coerceAtLeast(8f)
        val batteryColor = if (battery.percent <= 20 && !battery.charging) HudRed else HudCyan
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(maxHeight / 2))
                .background(HudSurface.copy(alpha = .92f))
                .border(1.dp, HudMuted.copy(alpha = .22f), RoundedCornerShape(maxHeight / 2))
                .padding(horizontal = if (compact) 5.dp else 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        ) {
            BluetoothSignalBars(
                active = connected,
                modifier = Modifier.size(iconHeight.dp),
            )
            Box(
                Modifier
                    .size(1.dp, (iconHeight * .86f).dp)
                    .background(HudMuted.copy(alpha = .42f)),
            )
            BatteryGauge(
                percent = battery.percent,
                charging = battery.charging,
                color = batteryColor,
                modifier = Modifier.size((iconHeight * 1.48f).dp, iconHeight.dp),
            )
            if (!compact) {
                Text(
                    text = "${battery.percent}%",
                    color = HudText,
                    fontSize = min(config.fontSizeSp, availableHeight * .36f).coerceAtLeast(7f).sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = numberFont,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun BatteryGauge(
    percent: Int,
    charging: Boolean,
    color: Color,
    modifier: Modifier,
) {
    Canvas(modifier) {
        val stroke = (size.minDimension * .09f).coerceAtLeast(1f)
        val terminalWidth = size.width * .08f
        val gap = size.width * .035f
        val bodyWidth = size.width - terminalWidth - gap
        val radius = androidx.compose.ui.geometry.CornerRadius(size.height * .16f)
        drawRoundRect(
            color = HudMuted,
            size = Size(bodyWidth, size.height),
            cornerRadius = radius,
            style = Stroke(stroke),
        )
        drawRoundRect(
            color = HudMuted,
            topLeft = Offset(bodyWidth + gap, size.height * .27f),
            size = Size(terminalWidth, size.height * .46f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(terminalWidth * .3f),
        )
        val inset = stroke * 1.8f
        val fillWidth = ((bodyWidth - inset * 2f) * (percent.coerceIn(0, 100) / 100f)).coerceAtLeast(0f)
        if (fillWidth > 0f) {
            drawRoundRect(
                color = color,
                topLeft = Offset(inset, inset),
                size = Size(fillWidth, (size.height - inset * 2f).coerceAtLeast(0f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * .09f),
            )
        }
        if (charging) {
            val centerX = bodyWidth * .5f
            val path = Path().apply {
                moveTo(centerX + size.height * .05f, size.height * .15f)
                lineTo(centerX - size.height * .18f, size.height * .52f)
                lineTo(centerX, size.height * .52f)
                lineTo(centerX - size.height * .05f, size.height * .86f)
                lineTo(centerX + size.height * .2f, size.height * .43f)
                lineTo(centerX + size.height * .02f, size.height * .43f)
                close()
            }
            drawPath(path, Color.Black.copy(alpha = .78f))
        }
    }
}

@Composable
private fun StatusIcon(active: Boolean, gps: Boolean, config: HudElementConfig) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val compact = maxWidth.value < 72f || maxHeight.value < 32f
        val availableHeight = maxHeight.value
        val iconSize = min(config.iconSizeDp, min(maxHeight.value * .72f, maxWidth.value * if (compact) .72f else .28f))
            .coerceAtLeast(4f)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(maxHeight / 2))
                .background(HudSurface.copy(alpha = .92f))
                .padding(horizontal = if (compact) 2.dp else 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 6.dp),
        ) {
            if (gps) {
                Icon(
                    imageVector = if (active) Icons.Rounded.GpsFixed else Icons.Rounded.GpsOff,
                    contentDescription = null,
                    tint = if (active) HudCyan else HudRed,
                    modifier = Modifier.size(iconSize.dp),
                )
            } else {
                BluetoothSignalBars(
                    active = active,
                    modifier = Modifier.size(iconSize.dp),
                )
            }
            if (!compact) {
                Text(
                    if (gps) "GPS" else if (active) "KẾT NỐI" else "MẤT KẾT NỐI",
                    color = HudText,
                    fontWeight = FontWeight.Bold,
                    fontSize = min(config.fontSizeSp, availableHeight * .3f).coerceAtLeast(6f).sp,
                )
            }
        }
    }
}

@Composable
private fun BluetoothSignalBars(active: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val heights = listOf(.28f, .48f, .70f, .94f)
        val gap = size.width * .08f
        val barWidth = ((size.width - gap * (heights.size - 1)) / heights.size).coerceAtLeast(1f)
        heights.forEachIndexed { index, fraction ->
            val height = size.height * fraction
            drawRoundRect(
                color = if (active) HudCyan else if (index == 0) HudRed else HudMuted.copy(alpha = .2f),
                topLeft = Offset(index * (barWidth + gap), size.height - height),
                size = Size(barWidth, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * .36f),
            )
        }
    }
}

@Composable
private fun AlertRail(alerts: List<HudAlert>, config: HudElementConfig, editing: Boolean = false) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val isHorizontal = when (config.orientation) {
            HudElementOrientation.HORIZONTAL -> true
            HudElementOrientation.VERTICAL -> false
            HudElementOrientation.AUTO -> maxWidth.value > maxHeight.value
        }
        val length = if (isHorizontal) maxWidth.value else maxHeight.value
        val crossAxis = if (isHorizontal) maxHeight.value else maxWidth.value
        val maxBadgeHeight = (crossAxis - 18f).coerceAtLeast(14f)
        val estItemSize = if (isHorizontal) {
            (config.iconSizeDp * 0.9f + config.spacingDp).coerceAtLeast(28f)
        } else {
            (config.iconSizeDp * 0.82f + 18f + config.spacingDp).coerceAtLeast(32f)
        }
        val maxFit = (length / estItemSize).toInt().coerceIn(1, if (isHorizontal) 8 else 5)
        val effectiveAlerts = if (alerts.isEmpty() && editing) {
            listOf(HudAlert(2, 300), HudAlert(8, 600, 60), HudAlert(3, 1200))
        } else alerts
        val displayAlerts = effectiveAlerts.sortedBy { it.distanceMeters }.take(maxFit)
        val count = displayAlerts.size.coerceAtLeast(1)
        val availablePerItem = ((length - (count - 1) * config.spacingDp) / count).coerceAtLeast(10f)
        val iconSize = min(
            config.iconSizeDp,
            min(availablePerItem * 0.82f, maxBadgeHeight),
        ).coerceAtLeast(8f)

        val content: @Composable () -> Unit = {
            displayAlerts.forEach { alert -> AlertBadge(alert, iconSize) }
            if (displayAlerts.isEmpty()) {
                Icon(
                    Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = HudMuted.copy(alpha = .25f),
                    modifier = Modifier.size(iconSize.dp),
                )
            }
        }
        if (isHorizontal) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(config.spacingDp.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { content() }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(config.spacingDp.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { content() }
        }
    }
}

@Composable
private fun AlertBadge(alert: HudAlert, iconSize: Float) {
    val generatedSpeedSign = alert.type == 8 && alert.value != null
    val bitmap = rememberAssetBitmap(if (generatedSpeedSign) null else alertAsset(alert))
    val numberFont = rememberHudNumberFont()
    val textFont = rememberHudTextFont()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(iconSize.dp).clip(CircleShape).background(Color(0xFF202733)),
            contentAlignment = Alignment.Center,
        ) {
            if (generatedSpeedSign) {
                GeneratedSpeedLimitSign(
                    limit = alert.value,
                    fontSizeSp = (iconSize * .70f).coerceAtLeast(9f),
                    modifier = Modifier.size((iconSize * .90f).dp),
                )
            } else if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Cảnh báo ${alert.type}",
                    modifier = Modifier.size((iconSize * .86f).dp),
                )
            } else {
                Icon(
                    imageVector = if (alert.type == 2 || alert.type in 40..46) Icons.Rounded.Speed else Icons.Rounded.Warning,
                    contentDescription = "Cảnh báo ${alert.type}",
                    tint = if (alert.type == 2 || alert.type in 40..46) HudCyan else HudRed,
                    modifier = Modifier.size((iconSize * .62f).dp),
                )
            }
            if (!generatedSpeedSign) {
                alert.value?.let {
                    Text(
                        it.toString(),
                        color = HudText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = numberFont,
                    )
                }
            }
        }
        Text(
            formatDistance(alert.distanceMeters),
            color = HudText,
            fontSize = (iconSize * .24f).coerceIn(5f, 13f).sp,
            fontWeight = FontWeight.Bold,
            fontFamily = textFont,
        )
    }
}

@Composable
private fun LaneStrip(lanes: List<LaneGuidance>) {
    val arrowHead = rememberAssetBitmap("Waze/direction_arrow_head.png")
    Canvas(Modifier.fillMaxSize().padding(4.dp)) {
        if (lanes.isEmpty()) return@Canvas
        val laneWidth = size.width / lanes.size
        lanes.forEachIndexed { index, lane ->
            if (index > 0) drawLine(HudMuted.copy(alpha = .35f), Offset(index * laneWidth, size.height * .2f), Offset(index * laneWidth, size.height * .82f), 1.dp.toPx())
            val bits = (0..7).filter { lane.directionsMask and (1 shl it) != 0 }
            bits.forEach { bit ->
                val selected = lane.selectedMask and (1 shl bit) != 0
                val angle = laneAngle(bit)
                drawLaneArrow(
                    center = Offset(index * laneWidth + laneWidth / 2f, size.height * .82f),
                    angleDegrees = angle,
                    color = if (selected) HudText else HudMuted.copy(alpha = .55f),
                    stroke = if (selected) size.height * .075f else size.height * .055f,
                    length = size.height * .48f,
                    arrowHead = arrowHead,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLaneArrow(
    center: Offset,
    angleDegrees: Float,
    color: Color,
    stroke: Float,
    length: Float,
    arrowHead: ImageBitmap?,
) {
    val radians = angleDegrees / 180f * PI.toFloat()
    val direction = Offset(sin(radians), -cos(radians))
    val bend = Offset(center.x, center.y - length * .46f)
    val tip = if (kotlin.math.abs(angleDegrees) >= 150f) {
        Offset(center.x + if (angleDegrees < 0) -length * .42f else length * .42f, center.y - length * .30f)
    } else bend + direction * length * .54f
    val headWidth = (stroke * 2.8f).coerceAtLeast(4f)
    val headHeight = headWidth * 41f / 63f
    val shaftEnd = tip - direction * headHeight * .72f
    val body = Path().apply {
        moveTo(center.x, center.y)
        if (kotlin.math.abs(angleDegrees) >= 150f) {
            val side = if (angleDegrees < 0) -1f else 1f
            lineTo(center.x, center.y - length * .62f)
            cubicTo(
                center.x, center.y - length * .82f,
                center.x + side * length * .42f, center.y - length * .82f,
                shaftEnd.x, shaftEnd.y,
            )
        } else {
            lineTo(center.x, bend.y + if (direction.y > 0f) length * .10f else 0f)
            quadraticTo(bend.x, bend.y, shaftEnd.x, shaftEnd.y)
        }
    }
    drawPath(body, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (arrowHead != null) {
        val dstWidth = headWidth.roundToInt().coerceAtLeast(1)
        val dstHeight = headHeight.roundToInt().coerceAtLeast(1)
        withTransform({ rotate(angleDegrees, pivot = tip) }) {
            drawImage(
                image = arrowHead,
                dstOffset = IntOffset((tip.x - dstWidth / 2f).roundToInt(), tip.y.roundToInt()),
                dstSize = IntSize(dstWidth, dstHeight),
                colorFilter = ColorFilter.tint(color),
            )
        }
    } else {
        val perpendicular = Offset(-direction.y, direction.x)
        val back = tip - direction * stroke * 2f
        drawPath(Path().apply {
            moveTo(tip.x, tip.y)
            lineTo((back + perpendicular * stroke).x, (back + perpendicular * stroke).y)
            lineTo((back - perpendicular * stroke).x, (back - perpendicular * stroke).y)
            close()
        }, color)
    }
}

@Composable
private fun OverspeedVignette() {
    val context = LocalContext.current
    val animationsEnabled = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
            .getOrDefault(true)
    }
    val alpha = if (animationsEnabled) {
        val transition = rememberInfiniteTransition(label = "overspeed")
        val animated by transition.animateFloat(
            initialValue = .28f,
            targetValue = .72f,
            animationSpec = infiniteRepeatable(tween(680), RepeatMode.Reverse),
            label = "overspeedAlpha",
        )
        animated
    } else .55f
    Box(Modifier.fillMaxSize().alpha(alpha)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(HudRed.copy(.55f), Color.Transparent, Color.Transparent, HudRed.copy(.55f)))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(HudRed.copy(.45f), Color.Transparent, Color.Transparent, HudRed.copy(.45f)))))
    }
}

private fun widgetDescription(type: HudWidgetType, state: HudState): String = when (type) {
    HudWidgetType.SPEED -> "Tốc độ hiện tại ${state.speed ?: 0} ki-lô-mét trên giờ"
    HudWidgetType.SPEED_NUMBER -> "Số tốc độ hiện tại ${state.speed ?: 0}"
    HudWidgetType.SPEED_LIMIT -> "Giới hạn tốc độ ${state.speedLimit ?: "chưa xác định"}"
    HudWidgetType.SPEED_LIMIT_BAR -> "Tốc độ hiện tại ${state.speed ?: 0}, giới hạn ${state.speedLimit ?: "chưa xác định"}"
    HudWidgetType.TURN -> "Hướng rẽ tiếp theo ${state.turn.name}"
    HudWidgetType.NEXT_TURN -> "Hướng rẽ kế tiếp ${state.nextTurn.name}"
    HudWidgetType.DISTANCE -> "Khoảng cách tới chỗ rẽ ${formatDistance(state.distanceMeters)}"
    HudWidgetType.STREET -> "Đường hiện tại ${state.street.orEmpty()}"
    HudWidgetType.NEXT_STREET -> "Đường tiếp theo ${state.nextStreet.orEmpty()}"
    HudWidgetType.ETA -> "Giờ đến dự kiến ${state.eta.orEmpty()}"
    HudWidgetType.REMAINING -> remainingText(state)
    HudWidgetType.GPS -> if (state.gpsAvailable) "GPS khả dụng" else "Không có GPS"
    HudWidgetType.CONNECTION -> "Bluetooth ${if (state.connected) "đã kết nối" else "đã ngắt kết nối"} và pin điện thoại"
    HudWidgetType.ALERTS -> "${state.alerts.size} cảnh báo sắp tới"
    HudWidgetType.LANES -> "Chỉ dẫn làn đường, ${state.lanes.size} làn"
    HudWidgetType.TRAFFIC_DELAY -> "Chậm ${state.trafficDelayMinutes ?: 0} phút do kẹt xe"
    HudWidgetType.CUSTOM_TEXT -> "Chữ tùy chỉnh"
    HudWidgetType.CUSTOM_IMAGE -> "Ảnh tùy chỉnh"
    HudWidgetType.PHONE_BATTERY -> "Bluetooth và pin điện thoại"
    HudWidgetType.CLOCK -> "Đồng hồ thời gian thực"
    HudWidgetType.COMPASS -> "La bàn số, hướng ${state.bearingDegrees?.roundToInt() ?: 0}°"
    HudWidgetType.TRIP_PROGRESS -> "Thanh tiến độ hành trình"
}

private fun shouldRenderWidget(type: HudWidgetType, state: HudState, config: HudElementConfig): Boolean = when (type) {
    HudWidgetType.SPEED, HudWidgetType.SPEED_NUMBER,
    HudWidgetType.SPEED_LIMIT,
    HudWidgetType.GPS, HudWidgetType.CONNECTION,
    HudWidgetType.CLOCK, HudWidgetType.COMPASS -> true
    HudWidgetType.SPEED_LIMIT_BAR -> state.speedLimit != null
    HudWidgetType.TURN -> state.navigating && state.turn != TurnType.NONE
    HudWidgetType.NEXT_TURN -> state.navigating && state.nextTurn != TurnType.NONE
    HudWidgetType.DISTANCE -> state.navigating && state.turn != TurnType.NONE && state.distanceMeters != null
    HudWidgetType.STREET -> !state.street.isNullOrBlank()
    HudWidgetType.NEXT_STREET -> state.navigating && state.turn != TurnType.NONE && !state.nextStreet.isNullOrBlank()
    HudWidgetType.ETA -> state.navigating && !state.eta.isNullOrBlank() && state.eta != "--:--"
    HudWidgetType.REMAINING -> state.navigating && (state.remainingMeters != null || state.remainingKm != null || (state.remainingMinutes != null && state.remainingMinutes > 0))
    HudWidgetType.ALERTS -> state.alerts.isNotEmpty()
    HudWidgetType.LANES -> state.navigating && state.lanes.isNotEmpty()
    HudWidgetType.TRAFFIC_DELAY -> (state.trafficDelayMinutes ?: 0) > 0
    HudWidgetType.TRIP_PROGRESS -> state.navigating
    HudWidgetType.CUSTOM_TEXT, HudWidgetType.PHONE_BATTERY -> true
    HudWidgetType.CUSTOM_IMAGE -> !config.customImageUri.isNullOrBlank()
}

private fun formatDistance(meters: Int?): String = when {
    meters == null -> "—"
    meters < 1_000 -> "$meters m"
    meters < 10_000 -> "%.1f km".format(meters / 1_000.0)
    else -> "${(meters / 1_000.0).roundToInt()} km"
}

private fun remainingText(state: HudState): String {
    if (!state.navigating) return ""
    val distance = state.remainingMeters?.takeIf { it > 0 }?.let(::formatDistance)
        ?: state.remainingKm?.takeIf { it > 0.0 }?.let { "%.1f km".format(it) }
    val minutes = state.remainingMinutes?.takeIf { it > 0 }?.let { if (it < 60) "$it phút" else "${it / 60} giờ ${it % 60} phút" }
    val eta = state.eta?.takeIf { it.isNotBlank() && it != "--:--" }?.let { "Đến lúc $it" }
    return listOfNotNull(distance, minutes, eta).joinToString("  ·  ").ifBlank { "" }
}

private fun laneAngle(bit: Int): Float = when (bit) {
    0 -> 0f
    1 -> -35f
    2 -> -80f
    3 -> -125f
    4 -> 35f
    5 -> 80f
    6 -> 125f
    else -> 180f
}

@Composable
private fun rememberAssetBitmap(path: String?): ImageBitmap? {
    val context = LocalContext.current
    return remember(path) {
        path?.let { assetPath ->
            runCatching {
                context.assets.open(assetPath).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        }
    }
}

@Composable
private fun rememberCustomImageBitmap(uriValue: String?): ImageBitmap? {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = uriValue) {
        value = withContext(Dispatchers.IO) {
            uriValue?.let { value -> decodeSampledBitmap(context, Uri.parse(value)) }
        }
    }
    return bitmap
}

private fun decodeSampledBitmap(context: Context, uri: Uri, maxDimension: Int = 2048): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)?.asImageBitmap()
    }
}.getOrNull()

private data class PhoneBatteryState(val percent: Int, val charging: Boolean)

@Composable
private fun rememberPhoneBatteryState(): PhoneBatteryState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(PhoneBatteryState(0, false)) }
    DisposableEffect(context) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent != null) state = intent.toPhoneBatteryState()
            }
        }
        val sticky = ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        if (sticky != null) state = sticky.toPhoneBatteryState()
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return state
}

private fun Intent.toPhoneBatteryState(): PhoneBatteryState {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
    val status = getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    return PhoneBatteryState(
        percent = (level * 100f / scale).roundToInt().coerceIn(0, 100),
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
    )
}

private fun turnAsset(turn: TurnType): String? = when (turn) {
    TurnType.NONE -> null
    TurnType.CONTINUE -> "Waze/car_big_trans_direction_forward.png"
    TurnType.LEFT, TurnType.SHARP_LEFT -> "Waze/car_big_trans_direction_left.png"
    TurnType.RIGHT, TurnType.SHARP_RIGHT -> "Waze/car_big_trans_direction_right.png"
    TurnType.SLIGHT_LEFT, TurnType.KEEP_LEFT, TurnType.EXIT_LEFT -> "Waze/car_big_trans_direction_exit_left.png"
    TurnType.SLIGHT_RIGHT, TurnType.KEEP_RIGHT, TurnType.EXIT_RIGHT -> "Waze/car_big_trans_direction_exit_right.png"
    TurnType.U_TURN -> "Waze/car_big_trans_direction_u_turn.png"
    TurnType.U_TURN_RIGHT -> "Waze/car_big_trans_direction_u_turn_lhs.png"
    TurnType.ROUNDABOUT -> "Waze/car_big_trans_directions_roundabout.png"
    TurnType.ROUNDABOUT_LEFT -> "Waze/car_big_trans_directions_roundabout_l.png"
    TurnType.ROUNDABOUT_RIGHT -> "Waze/car_big_trans_directions_roundabout_r.png"
    TurnType.ROUNDABOUT_STRAIGHT -> "Waze/car_big_trans_directions_roundabout_s.png"
    TurnType.ROUNDABOUT_U_TURN -> "Waze/car_big_trans_directions_roundabout_u.png"
    TurnType.ARRIVE -> "Waze/car_big_trans_direction_end.png"
    TurnType.FERRY -> null
}

private fun alertAsset(alert: HudAlert): String? {
    val file = when (alert.type) {
        1 -> "bigpin_police.png"
        2 -> "bigpin_speed_camera.png"
        3 -> "bigpin_red_light_camera.png"
        4 -> "bigpin_hazard.png"
        5 -> "bigpin_accident.png"
        6 -> "bigpin_traffic_${alert.severity?.coerceIn(1, 4) ?: 1}.png"
        7, 38 -> "bigpin_closure.png"
        8 -> null
        9 -> "no_passing_in.png"
        10 -> "no_passing_out.png"
        11 -> "bigpin_railroad.png"
        12 -> "bigpin_permanent_hazard_toll_booth.png"
        13 -> "bigpin_hazard_stopped.png"
        14 -> "bigpin_hazard_construction.png"
        15 -> "bigpin_hazard_pothole.png"
        16 -> "bigpin_bad_weather.png"
        17 -> "bigpin_blocked_lane.png"
        18 -> "bigpin_permanent_hazard_intersection.png"
        19 -> "loi_ra.png"
        20, 21 -> "bigpin_parking.png"
        22, 25 -> "end_of_previous_prohibitions.png"
        23 -> "residential_area_start.png"
        24 -> "residential_area_end.png"
        26, 35 -> "cam_oto.png"
        27, 36 -> "cam_xe_may.png"
        28, 67, 68, 72 -> "no_left_turn.png"
        29, 65, 73 -> "no_right_turn.png"
        30, 74 -> "no_u_turn.png"
        31, 32 -> "only_go_straight.png"
        33 -> "only_turn_right.png"
        34 -> "only_turn_left.png"
        37, 60 -> "bigpin_permanent_hazard_fork.png"
        39, 66, 69 -> "no_left_and_u_turn.png"
        40 -> "bigpin_phone_camera.png"
        41 -> "bigpin_dummy_camera.png"
        42 -> "bigpin_seatbelt_camera.png"
        43 -> "bigpin_distance_between_vehicles_camera.png"
        44 -> "bigpin_bus_lane_cam.png"
        45 -> "bigpin_noise_camera.png"
        46 -> "bigpin_stop_sign_camera.png"
        47 -> "bigpin_animal.png"
        48 -> "bigpin_hazard_object_on_road.png"
        49 -> "bigpin_hazard_roadkill.png"
        50 -> "bigpin_hazard_weather_flood.png"
        51 -> "bigpin_hazard_weather_fog.png"
        52 -> "bigpin_hazard_weather_hail.png"
        53 -> "bigpin_hazard_weather_snow.png"
        54 -> "bigpin_hazard_weather_ice.png"
        55 -> "bigpin_slippery_road.png"
        56 -> "bigpin_permanent_hazard_speed_bumps.png"
        57 -> "bigpin_permanent_hazard_school_zone.png"
        58 -> "bigpin_permanent_hazard_lanes_merging.png"
        59 -> "bigpin_permanent_hazard_dangerous_curves.png"
        61 -> "bigpin_hazard_broken_light.png"
        62 -> "bigpin_cyclist.png"
        63 -> "bigpin_emergency_vehicle.png"
        64 -> "bigpin_personal_safety_a.png"
        70, 71 -> "no_right_and_u_turn.png"
        else -> null
    }
    return file?.let { "alerts/$it" }
}

val PreviewHudState = HudState(
    navigating = true,
    speed = 85,
    speedLimit = 80,
    overspeed = true,
    distanceMeters = 350,
    turn = TurnType.CONTINUE,
    nextTurn = TurnType.LEFT,
    lanes = listOf(LaneGuidance(5, 4), LaneGuidance(1, 1), LaneGuidance(17, 16)),
    street = "Đường Võ Nguyên Giáp, phường Long Bình",
    nextStreet = "Đường Nguyễn Ái Quốc",
    eta = "09:32",
    remainingMinutes = 24,
    remainingKm = 18.5,
    gpsAvailable = true,
    connected = true,
    alerts = listOf(HudAlert(2, 300), HudAlert(8, 800, 60)),
    trafficDelayMinutes = 12,
    trafficSeverity = 3,
    bearingDegrees = 45f,
)
