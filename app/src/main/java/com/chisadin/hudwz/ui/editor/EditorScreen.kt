package com.chisadin.hudwz.ui.editor

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.automirrored.rounded.FormatAlignRight
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AlignHorizontalCenter
import androidx.compose.material.icons.rounded.AlignVerticalCenter
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.StayCurrentLandscape
import androidx.compose.material.icons.rounded.StayCurrentPortrait
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Widgets
import java.util.UUID
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.chisadin.hudwz.domain.HudElementConfig
import com.chisadin.hudwz.domain.HudElementOrientation
import com.chisadin.hudwz.domain.HudFontWeight
import com.chisadin.hudwz.domain.HudProfile
import com.chisadin.hudwz.domain.HudProfileOrientationMode
import com.chisadin.hudwz.domain.HudTextAlignment
import com.chisadin.hudwz.domain.HudWidgetType
import com.chisadin.hudwz.domain.HudState
import com.chisadin.hudwz.domain.HudLayerMove
import com.chisadin.hudwz.domain.locksAspectRatio
import com.chisadin.hudwz.domain.reorderHudElements
import com.chisadin.hudwz.domain.defaultHudElement
import com.chisadin.hudwz.ui.hud.HudRenderer
import com.chisadin.hudwz.ui.hud.HudWidgetPreview
import com.chisadin.hudwz.ui.hud.PreviewHudState
import com.chisadin.hudwz.ui.theme.HudBlack
import com.chisadin.hudwz.ui.theme.HudCyan
import com.chisadin.hudwz.ui.theme.HudOutline
import com.chisadin.hudwz.ui.theme.HudSurface
import com.chisadin.hudwz.ui.theme.HudSurfaceHigh
import kotlin.math.roundToInt

enum class AlignmentAction {
    CENTER_HORIZONTAL, CENTER_VERTICAL, ALIGN_LEFT, ALIGN_RIGHT, ALIGN_TOP, ALIGN_BOTTOM
}

@Composable
fun EditorScreen(
    profile: HudProfile,
    fontScale: Float,
    onBack: () -> Unit,
    onElementChange: (HudElementConfig, Boolean) -> Unit,
    onScaleChange: (Float) -> Unit,
    onAddElement: (HudWidgetType, Float, Float, Boolean) -> HudElementConfig,
    onRemoveElement: (String, Boolean) -> Unit,
    onMoveElement: (String, HudLayerMove, Boolean) -> Unit,
    onUpdateOrientationMode: ((HudProfileOrientationMode) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val activity = context as? Activity
    var isPortraitMode by rememberSaveable(profile.id) {
        mutableStateOf(profile.effectiveOrientationMode == HudProfileOrientationMode.PORTRAIT_ONLY)
    }

    LaunchedEffect(isPortraitMode) {
        activity?.requestedOrientation = if (isPortraitMode) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    var workingProfile by remember(profile.id) { mutableStateOf(profile) }
    var selectedId by remember(profile.id, isPortraitMode) {
        mutableStateOf(workingProfile.elementsFor(isPortraitMode).firstOrNull()?.id)
    }
    var libraryOpen by remember { mutableStateOf(false) }
    var inspectorOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var showInactiveItems by remember { mutableStateOf(false) }
    var canvasBounds by remember { mutableStateOf(Rect.Zero) }
    var libraryDrag by remember { mutableStateOf<LibraryDrag?>(null) }
    var zenMode by remember { mutableStateOf(false) }
    var undoStack by remember(profile.id, isPortraitMode) { mutableStateOf<List<List<HudElementConfig>>>(emptyList()) }
    var redoStack by remember(profile.id, isPortraitMode) { mutableStateOf<List<List<HudElementConfig>>>(emptyList()) }

    LaunchedEffect(profile.id, isPortraitMode) {
        selectedId = workingProfile.elementsFor(isPortraitMode).firstOrNull()?.id
    }
    LaunchedEffect(profile) {
        if (profile.id == workingProfile.id && profile != workingProfile) {
            workingProfile = profile
            val currentList = profile.elementsFor(isPortraitMode)
            if (selectedId != null && currentList.none { it.id == selectedId }) {
                selectedId = currentList.firstOrNull()?.id
            }
        }
    }
    val currentElements = workingProfile.elementsFor(isPortraitMode)
    val selected = currentElements.firstOrNull { it.id == selectedId }
    val previewState = PreviewHudState

    fun pushUndo(snapshot: List<HudElementConfig>) {
        undoStack = (undoStack + listOf(snapshot)).takeLast(30)
        redoStack = emptyList()
    }

    fun undo() {
        val previous = undoStack.lastOrNull() ?: return
        val current = workingProfile.elementsFor(isPortraitMode)
        undoStack = undoStack.dropLast(1)
        redoStack = (redoStack + listOf(current)).takeLast(30)
        if (isPortraitMode) {
            workingProfile = workingProfile.copy(portraitElements = previous)
            previous.forEach { onElementChange(it, true) }
        } else {
            workingProfile = workingProfile.copy(elements = previous)
            previous.forEach { onElementChange(it, false) }
        }
        if (selectedId != null && previous.none { it.id == selectedId }) {
            selectedId = previous.firstOrNull()?.id
        }
    }

    fun redo() {
        val next = redoStack.lastOrNull() ?: return
        val current = workingProfile.elementsFor(isPortraitMode)
        redoStack = redoStack.dropLast(1)
        undoStack = (undoStack + listOf(current)).takeLast(30)
        if (isPortraitMode) {
            workingProfile = workingProfile.copy(portraitElements = next)
            next.forEach { onElementChange(it, true) }
        } else {
            workingProfile = workingProfile.copy(elements = next)
            next.forEach { onElementChange(it, false) }
        }
        if (selectedId != null && next.none { it.id == selectedId }) {
            selectedId = next.firstOrNull()?.id
        }
    }

    fun updateWorkingElement(next: HudElementConfig) {
        val canvasWidthDp = with(density) { canvasBounds.width.toDp().value }
        val canvasHeightDp = with(density) { canvasBounds.height.toDp().value }
        val effectiveHeight = if (next.type.locksAspectRatio) next.widthDp else next.heightDp
        val clamped = if (canvasWidthDp > 0f && canvasHeightDp > 0f) {
            next.copy(
                x = next.x.coerceIn(
                    0f,
                    (canvasWidthDp - next.widthDp * next.scale * workingProfile.hudScale).coerceAtLeast(0f),
                ),
                y = next.y.coerceIn(
                    0f,
                    (canvasHeightDp - effectiveHeight * next.scale * workingProfile.hudScale).coerceAtLeast(0f),
                ),
            )
        } else next
        if (isPortraitMode) {
            val list = workingProfile.elementsFor(true).map { if (it.id == clamped.id) clamped else it }
            workingProfile = workingProfile.copy(portraitElements = list)
            onElementChange(clamped, true)
        } else {
            val list = workingProfile.elements.map { if (it.id == clamped.id) clamped else it }
            workingProfile = workingProfile.copy(elements = list)
            onElementChange(clamped, false)
        }
    }

    fun duplicateWidget(target: HudElementConfig) {
        val canvasWidthDp = with(density) { canvasBounds.width.toDp().value }
        val canvasHeightDp = with(density) { canvasBounds.height.toDp().value }
        val effectiveHeight = if (target.type.locksAspectRatio) target.widthDp else target.heightDp
        val maxX = (canvasWidthDp - target.widthDp * target.scale * workingProfile.hudScale).coerceAtLeast(0f)
        val maxY = (canvasHeightDp - effectiveHeight * target.scale * workingProfile.hudScale).coerceAtLeast(0f)
        val cloned = target.copy(
            id = UUID.randomUUID().toString(),
            x = (target.x + 16f).coerceIn(0f, maxX),
            y = (target.y + 16f).coerceIn(0f, maxY),
            locked = false,
        )
        pushUndo(currentElements)
        if (isPortraitMode) {
            val list = workingProfile.elementsFor(true) + cloned
            workingProfile = workingProfile.copy(portraitElements = list)
            onElementChange(cloned, true)
        } else {
            val list = workingProfile.elements + cloned
            workingProfile = workingProfile.copy(elements = list)
            onElementChange(cloned, false)
        }
        selectedId = cloned.id
        inspectorOpen = true
    }

    fun toggleLock(target: HudElementConfig) {
        pushUndo(currentElements)
        updateWorkingElement(target.copy(locked = !target.locked))
    }

    fun alignWidget(target: HudElementConfig, alignment: AlignmentAction) {
        val canvasWidthDp = with(density) { canvasBounds.width.toDp().value }
        val canvasHeightDp = with(density) { canvasBounds.height.toDp().value }
        val widthDp = target.widthDp * target.scale * workingProfile.hudScale
        val effectiveHeight = if (target.type.locksAspectRatio) target.widthDp else target.heightDp
        val heightDp = effectiveHeight * target.scale * workingProfile.hudScale
        val maxX = (canvasWidthDp - widthDp).coerceAtLeast(0f)
        val maxY = (canvasHeightDp - heightDp).coerceAtLeast(0f)

        pushUndo(currentElements)
        val updated = when (alignment) {
            AlignmentAction.CENTER_HORIZONTAL -> target.copy(x = ((canvasWidthDp - widthDp) / 2f).coerceIn(0f, maxX))
            AlignmentAction.CENTER_VERTICAL -> target.copy(y = ((canvasHeightDp - heightDp) / 2f).coerceIn(0f, maxY))
            AlignmentAction.ALIGN_LEFT -> target.copy(x = 16f.coerceIn(0f, maxX))
            AlignmentAction.ALIGN_RIGHT -> target.copy(x = (canvasWidthDp - widthDp - 16f).coerceIn(0f, maxX))
            AlignmentAction.ALIGN_TOP -> target.copy(y = 16f.coerceIn(0f, maxY))
            AlignmentAction.ALIGN_BOTTOM -> target.copy(y = (canvasHeightDp - heightDp - 16f).coerceIn(0f, maxY))
        }
        updateWorkingElement(updated)
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            selected?.takeIf { it.type == HudWidgetType.CUSTOM_IMAGE }?.let {
                pushUndo(currentElements)
                updateWorkingElement(it.copy(customImageUri = uri.toString()))
            }
        }
    }

    fun addWidget(type: HudWidgetType, centerX: Float, centerY: Float) {
        pushUndo(currentElements)
        val prototype = defaultHudElement(type, "drop-preview")
        val x = (centerX - prototype.widthDp / 2f).coerceAtLeast(0f)
        val y = (centerY - prototype.heightDp / 2f).coerceAtLeast(0f)
        val next = onAddElement(type, x, y, isPortraitMode)
        if (isPortraitMode) {
            val list = workingProfile.elementsFor(true) + next
            workingProfile = workingProfile.copy(portraitElements = list)
        } else {
            val list = workingProfile.elements + next
            workingProfile = workingProfile.copy(elements = list)
        }
        selectedId = next.id
        inspectorOpen = true
        libraryOpen = false
    }

    fun removeWidget(id: String) {
        pushUndo(currentElements)
        if (isPortraitMode) {
            val list = workingProfile.elementsFor(true).filterNot { it.id == id }
            workingProfile = workingProfile.copy(portraitElements = list)
            onRemoveElement(id, true)
            selectedId = list.lastOrNull()?.id
        } else {
            val list = workingProfile.elements.filterNot { it.id == id }
            workingProfile = workingProfile.copy(elements = list)
            onRemoveElement(id, false)
            selectedId = list.lastOrNull()?.id
        }
    }

    fun moveWidget(id: String, move: HudLayerMove) {
        pushUndo(currentElements)
        if (isPortraitMode) {
            val list = reorderHudElements(workingProfile.elementsFor(true), id, move)
            workingProfile = workingProfile.copy(portraitElements = list)
            onMoveElement(id, move, true)
        } else {
            val list = reorderHudElements(workingProfile.elements, id, move)
            workingProfile = workingProfile.copy(elements = list)
            onMoveElement(id, move, false)
        }
    }

    fun finishLibraryDrag() {
        val drag = libraryDrag ?: return
        if (canvasBounds.contains(drag.position)) {
            val x = with(density) { (drag.position.x - canvasBounds.left).toDp().value }
            val y = with(density) { (drag.position.y - canvasBounds.top).toDp().value }
            addWidget(drag.type, x, y)
            libraryOpen = false
        }
        libraryDrag = null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(EditorBackground)
            .windowInsetsPadding(WindowInsets.displayCutout),
    ) {
        CanvasWorkspace(
            profile = workingProfile,
            isPortrait = isPortraitMode,
            state = previewState,
            fontScale = fontScale,
            showInactiveItems = showInactiveItems,
            selectedId = if (zenMode) null else selectedId,
            modifier = Modifier.fillMaxSize(),
            onCanvasBounds = { canvasBounds = it },
            onSelect = {
                if (zenMode) {
                    zenMode = false
                } else {
                    selectedId = it
                    inspectorOpen = true
                    libraryOpen = false
                    settingsOpen = false
                }
            },
            onDoubleTap = {
                if (zenMode) {
                    zenMode = false
                } else {
                    selectedId = it
                    inspectorOpen = true
                    libraryOpen = false
                    settingsOpen = false
                }
            },
            onClearFocus = {
                if (zenMode) {
                    zenMode = false
                } else {
                    selectedId = null
                    inspectorOpen = false
                }
            },
            onDragStart = { pushUndo(currentElements) },
            onElementChange = ::updateWorkingElement,
        )

        if (!zenMode) {
            Row(
                Modifier.align(Alignment.TopStart).padding(8.dp).zIndex(10f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FloatingEditorButton("Quay lại", onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                }
                FloatingEditorButton("Thành phần", {
                    libraryOpen = !libraryOpen
                    inspectorOpen = false
                    settingsOpen = false
                }, active = libraryOpen) {
                    Icon(Icons.Rounded.Widgets, contentDescription = null)
                }
                FloatingEditorButton(
                    when {
                        workingProfile.effectiveOrientationMode == HudProfileOrientationMode.PORTRAIT_ONLY -> "Định hướng: Chỉ dọc (Cố định)"
                        workingProfile.effectiveOrientationMode == HudProfileOrientationMode.LANDSCAPE_ONLY -> "Định hướng: Chỉ ngang (Cố định)"
                        isPortraitMode -> "Đang chỉnh: Bố cục Dọc (Chạm để đổi)"
                        else -> "Đang chỉnh: Bố cục Ngang (Chạm để đổi)"
                    },
                    {
                        if (workingProfile.effectiveOrientationMode != HudProfileOrientationMode.PORTRAIT_ONLY &&
                            workingProfile.effectiveOrientationMode != HudProfileOrientationMode.LANDSCAPE_ONLY) {
                            isPortraitMode = !isPortraitMode
                            selectedId = null
                        }
                    },
                    active = isPortraitMode,
                ) {
                    Icon(
                        if (isPortraitMode) Icons.Rounded.StayCurrentPortrait else Icons.Rounded.StayCurrentLandscape,
                        contentDescription = null,
                    )
                }
                FloatingEditorButton("Hoàn tác", ::undo, enabled = undoStack.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Hoàn tác")
                }
                FloatingEditorButton("Làm lại", ::redo, enabled = redoStack.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = "Làm lại")
                }
                if (selected != null) {
                    FloatingEditorButton(
                        if (selected.locked) "Mở khóa thành phần" else "Khóa thành phần",
                        { toggleLock(selected) },
                        active = selected.locked,
                    ) {
                        Icon(
                            if (selected.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                            contentDescription = null,
                            tint = if (selected.locked) Color(0xFFFFB300) else Color.Unspecified,
                        )
                    }
                    FloatingEditorButton("Nhân bản thành phần", { duplicateWidget(selected) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                    }
                    FloatingEditorButton("Bỏ chọn thành phần", {
                        selectedId = null
                        inspectorOpen = false
                    }) {
                        Icon(Icons.Rounded.Close, contentDescription = null)
                    }
                }
            }

            Row(
                Modifier.align(Alignment.TopEnd).padding(8.dp).zIndex(10f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FloatingEditorButton("Xem trước toàn màn hình", {
                    zenMode = true
                    libraryOpen = false
                    inspectorOpen = false
                    settingsOpen = false
                }) {
                    Icon(Icons.Rounded.Fullscreen, contentDescription = "Xem trước sạch")
                }
                FloatingEditorButton("Thuộc tính thành phần", {
                    inspectorOpen = !inspectorOpen
                    libraryOpen = false
                    settingsOpen = false
                }, active = inspectorOpen, enabled = selected != null) {
                    Icon(Icons.Rounded.Tune, contentDescription = null)
                }
                FloatingEditorButton("Cài đặt HUD", {
                    settingsOpen = !settingsOpen
                    libraryOpen = false
                    inspectorOpen = false
                }, active = settingsOpen) {
                    Icon(Icons.Rounded.Settings, contentDescription = null)
                }
            }
        } else {
            Surface(
                color = HudSurfaceHigh.copy(alpha = 0.88f),
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .clickable { zenMode = false }
                    .zIndex(10f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.FullscreenExit, contentDescription = null, tint = HudCyan, modifier = Modifier.size(16.dp))
                    Text("Chế độ xem trước HUD · Chạm để hiện công cụ", fontSize = 11.sp, color = HudCyan)
                }
            }
        }

        if (!zenMode && libraryOpen) {
            Box(Modifier.align(Alignment.TopStart).padding(top = 62.dp).zIndex(4f)) {
                WidgetLibrary(
                    onClose = { libraryOpen = false },
                    onAdd = {
                        val centerX = with(density) { canvasBounds.width.toDp().value } / 2f
                        val centerY = with(density) { canvasBounds.height.toDp().value } / 2f
                        addWidget(it, centerX, centerY)
                        libraryOpen = false
                    },
                    onDragStart = { type, position -> libraryDrag = LibraryDrag(type, position) },
                    onDrag = { delta -> libraryDrag = libraryDrag?.copy(position = libraryDrag!!.position + delta) },
                    onDrop = ::finishLibraryDrag,
                    onCancel = { libraryDrag = null },
                )
            }
        }

        if (!zenMode && inspectorOpen) {
            InspectorPanel(
                element = selected,
                isBottomSheet = isPortraitMode,
                modifier = if (isPortraitMode) {
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.50f).zIndex(5f)
                } else {
                    Modifier.align(Alignment.TopEnd).padding(top = 62.dp).width(270.dp).fillMaxHeight().zIndex(5f)
                },
                canvasWidthDp = with(density) { canvasBounds.width.toDp().value },
                canvasHeightDp = with(density) { canvasBounds.height.toDp().value },
                onAlign = { alignWidget(selected!!, it) },
                onToggleLock = { selected?.let(::toggleLock) },
                onDuplicate = { selected?.let(::duplicateWidget) },
                onChange = ::updateWorkingElement,
                onDelete = { selected?.id?.let(::removeWidget) },
                onLayerMove = { move -> selected?.id?.let { moveWidget(it, move) } },
                onPickImage = { imagePicker.launch(arrayOf("image/*")) },
                onClose = { inspectorOpen = false },
            )
        }

        if (!zenMode && settingsOpen) {
            EditorSettingsPanel(
                profile = workingProfile,
                scale = workingProfile.hudScale,
                showInactiveItems = showInactiveItems,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 62.dp).width(270.dp).zIndex(5f),
                onScaleChange = {
                    workingProfile = workingProfile.copy(hudScale = it)
                    onScaleChange(it)
                },
                onShowInactiveChange = { showInactiveItems = it },
                onOrientationModeChange = { mode ->
                    workingProfile = workingProfile.copy(orientationMode = mode)
                    if (mode == HudProfileOrientationMode.PORTRAIT_ONLY) isPortraitMode = true
                    else if (mode == HudProfileOrientationMode.LANDSCAPE_ONLY) isPortraitMode = false
                    onUpdateOrientationMode?.invoke(mode)
                },
                onClose = { settingsOpen = false },
            )
        }

        libraryDrag?.let { drag ->
            Surface(
                color = HudSurfaceHigh.copy(alpha = .94f),
                shape = RoundedCornerShape(10.dp),
                shadowElevation = 10.dp,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (drag.position.x - with(density) { 60.dp.toPx() }).roundToInt(),
                            (drag.position.y - with(density) { 32.dp.toPx() }).roundToInt(),
                        )
                    }
                    .size(120.dp, 64.dp)
                    .border(2.dp, HudCyan, RoundedCornerShape(10.dp))
                    .zIndex(12f),
            ) {
                HudWidgetPreview(drag.type, Modifier.fillMaxSize().padding(6.dp))
            }
        }
    }
}

@Composable
private fun FloatingEditorButton(
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        color = if (active) HudCyan else EditorPanel.copy(alpha = .88f),
        contentColor = if (active) HudBlack else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 6.dp,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(48.dp).semantics { contentDescription = label },
        ) {
            content()
        }
    }
}

@Composable
private fun CanvasWorkspace(
    profile: HudProfile,
    isPortrait: Boolean,
    state: HudState,
    fontScale: Float,
    showInactiveItems: Boolean,
    selectedId: String?,
    modifier: Modifier,
    onCanvasBounds: (Rect) -> Unit,
    onSelect: (String) -> Unit,
    onDoubleTap: (String) -> Unit,
    onClearFocus: () -> Unit,
    onDragStart: () -> Unit,
    onElementChange: (HudElementConfig) -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(HudBlack)
                .border(1.dp, HudCyan.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClearFocus)
                .onGloballyPositioned { onCanvasBounds(it.boundsInRoot()) },
        ) {
            HudRenderer(
                state = state,
                profile = profile,
                mirror = false,
                fontScale = fontScale,
                modifier = Modifier.fillMaxSize(),
                editing = true,
                showInactiveInEditor = showInactiveItems,
                forcePortrait = isPortrait,
                selectedId = selectedId,
                onSelect = onSelect,
                onDoubleTap = onDoubleTap,
                onDragStart = onDragStart,
                onElementChange = onElementChange,
            )
        }
    }
}

@Composable
private fun WidgetLibrary(
    onClose: () -> Unit,
    onAdd: (HudWidgetType) -> Unit,
    onDragStart: (HudWidgetType, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        color = EditorPanel.copy(alpha = .97f),
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp),
        modifier = Modifier.width(226.dp).fillMaxHeight(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(start = 10.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Widgets, contentDescription = null, tint = HudCyan)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("Thư viện thành phần", fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("Chạm để thêm · giữ để kéo", fontSize = 10.sp)
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Đóng thư viện thành phần") }
            }
            HorizontalDivider(color = HudOutline)
            LazyColumn(
                Modifier.fillMaxSize().padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(HudWidgetType.entries.filterNot { it == HudWidgetType.PHONE_BATTERY }, key = { it.name }) { type ->
                    WidgetLibraryItem(type, onAdd, onDragStart, onDrag, onDrop, onCancel)
                }
            }
        }
    }
}

@Composable
private fun WidgetLibraryItem(
    type: HudWidgetType,
    onAdd: (HudWidgetType) -> Unit,
    onDragStart: (HudWidgetType, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Card(
        colors = CardDefaults.cardColors(containerColor = HudSurfaceHigh),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clickable { onAdd(type) }
            .pointerInput(type) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { local -> onDragStart(type, origin + local) },
                    onDrag = { change, amount -> change.consume(); onDrag(amount) },
                    onDragEnd = onDrop,
                    onDragCancel = onCancel,
                )
            }
            .semantics { contentDescription = "Thêm hoặc kéo ${widgetLabel(type)}" },
    ) {
        Row(Modifier.fillMaxSize().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp, 50.dp).background(Color.Black, RoundedCornerShape(7.dp)).padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                HudWidgetPreview(type, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(widgetLabel(type), fontSize = 13.sp, maxLines = 1)
                Text(widgetHint(type), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.DragIndicator, contentDescription = null, tint = HudOutline)
        }
    }
}

@Composable
private fun InspectorPanel(
    element: HudElementConfig?,
    isBottomSheet: Boolean,
    modifier: Modifier,
    canvasWidthDp: Float,
    canvasHeightDp: Float,
    onAlign: (AlignmentAction) -> Unit,
    onToggleLock: () -> Unit,
    onDuplicate: () -> Unit,
    onChange: (HudElementConfig) -> Unit,
    onDelete: () -> Unit,
    onLayerMove: (HudLayerMove) -> Unit,
    onPickImage: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        color = EditorPanel.copy(alpha = .97f),
        shape = if (isBottomSheet) RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
                else RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
        shadowElevation = 12.dp,
        modifier = modifier,
    ) {
        if (element == null) {
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.Tune, contentDescription = null, tint = HudOutline, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(10.dp))
                Text("Chọn một thành phần", style = MaterialTheme.typography.titleLarge)
                Text("Mở Thư viện để thêm thành phần, sau đó chọn trên vùng xem trước.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isBottomSheet) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(bottom = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(36.dp, 4.dp)
                                    .background(HudOutline, RoundedCornerShape(2.dp)),
                            )
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(widgetLabel(element.type), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Text("Thuộc tính thành phần", fontSize = 10.sp, color = HudCyan)
                        }
                        IconButton(onClick = onToggleLock) {
                            Icon(
                                if (element.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                contentDescription = if (element.locked) "Mở khóa thành phần" else "Khóa thành phần",
                                tint = if (element.locked) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = onDuplicate) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = "Nhân bản thành phần")
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Rounded.Close, contentDescription = "Đóng thuộc tính thành phần")
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Xóa ${widgetLabel(element.type)}", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (element.type == HudWidgetType.CUSTOM_TEXT) {
                    item {
                        OutlinedTextField(
                            value = element.customText,
                            onValueChange = { onChange(element.copy(customText = it)) },
                            label = { Text("Nội dung") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                        )
                    }
                }
                if (element.type == HudWidgetType.CUSTOM_IMAGE) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalButton(onClick = onPickImage, modifier = Modifier.weight(1f)) {
                                Text(if (element.customImageUri == null) "Chọn ảnh" else "Đổi ảnh")
                            }
                            if (element.customImageUri != null) {
                                OutlinedButton(
                                    onClick = { onChange(element.copy(customImageUri = null)) },
                                ) { Text("Xóa ảnh") }
                            }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (element.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, contentDescription = null)
                        Text(" Hiển thị", Modifier.weight(1f))
                        Switch(checked = element.visible, onCheckedChange = { onChange(element.copy(visible = it)) })
                    }
                }
                item { InspectorSection("Căn lề tự động") }
                item { AlignmentControls(onAlign = onAlign) }
                item { InspectorSection("Lớp hiển thị") }
                item { LayerControls(onLayerMove) }
                item { InspectorSection("Vị trí") }
                item {
                    Text(
                        "x ${element.x.roundToInt()} dp · y ${element.y.roundToInt()} dp · tính từ góc trên trái",
                        fontSize = 10.sp,
                        color = HudCyan,
                    )
                }
                item { PositionControls(element, onChange) }
                item { InspectorSection("Kích thước") }
                if (element.type.locksAspectRatio) {
                    item {
                        Text("Đã khóa tỷ lệ · 1:1", fontSize = 10.sp, color = HudCyan)
                    }
                    item {
                        InspectorSlider("Kích thước", element.widthDp, 28f..500f, "${element.widthDp.roundToInt()} dp") {
                            onChange(element.copy(widthDp = it, heightDp = it, iconSizeDp = it * .9f, scale = 1f))
                        }
                    }
                } else {
                    item { InspectorSlider("Chiều rộng", element.widthDp, 32f..600f, "${element.widthDp.roundToInt()} dp") { onChange(element.copy(widthDp = it)) } }
                    item { InspectorSlider("Chiều cao", element.heightDp, 28f..500f, "${element.heightDp.roundToInt()} dp") { onChange(element.copy(heightDp = it)) } }
                    item { InspectorSlider("Tỷ lệ thành phần", element.scale, .25f..2f, "${(element.scale * 100).roundToInt()}%") { onChange(element.copy(scale = it)) } }
                    if (element.type != HudWidgetType.CUSTOM_TEXT && element.type != HudWidgetType.CUSTOM_IMAGE) {
                        item { InspectorSlider("Biểu tượng", element.iconSizeDp, 4f..220f, "${element.iconSizeDp.roundToInt()} dp") { onChange(element.copy(iconSizeDp = it)) } }
                    }
                }
                item { InspectorSection("Giao diện") }
                item { InspectorSlider("Độ mờ", element.opacity, .1f..1f, "${(element.opacity * 100).roundToInt()}%") { onChange(element.copy(opacity = it)) } }
                if (element.type != HudWidgetType.CUSTOM_IMAGE && (!element.type.locksAspectRatio || element.type == HudWidgetType.SPEED)) {
                    item { InspectorSlider("Cỡ chữ", element.fontSizeSp, 6f..140f, "${element.fontSizeSp.roundToInt()} sp") { onChange(element.copy(fontSizeSp = it)) } }
                }
                item { InspectorSlider("Khoảng cách", element.spacingDp, 0f..40f, "${element.spacingDp.roundToInt()} dp") { onChange(element.copy(spacingDp = it)) } }
                if (element.type != HudWidgetType.CUSTOM_IMAGE && element.type != HudWidgetType.ALERTS) {
                    item { EnumChips("Độ đậm", HudFontWeight.entries, element.fontWeight) { onChange(element.copy(fontWeight = it)) } }
                    item { EnumChips("Căn chỉnh", HudTextAlignment.entries, element.textAlignment) { onChange(element.copy(textAlignment = it)) } }
                }
                if (element.type != HudWidgetType.CUSTOM_IMAGE) {
                    item {
                        EnumChips("Hướng", HudElementOrientation.entries, element.orientation) { nextOrientation ->
                            val updated = if (element.type == HudWidgetType.ALERTS) {
                                if (nextOrientation == HudElementOrientation.HORIZONTAL && element.widthDp < element.heightDp) {
                                    element.copy(
                                        orientation = nextOrientation,
                                        widthDp = maxOf(element.widthDp, element.heightDp, 240f),
                                        heightDp = minOf(element.widthDp, element.heightDp).coerceIn(40f, 90f),
                                    )
                                } else if (nextOrientation == HudElementOrientation.VERTICAL && element.widthDp > element.heightDp) {
                                    element.copy(
                                        orientation = nextOrientation,
                                        widthDp = minOf(element.widthDp, element.heightDp).coerceIn(50f, 90f),
                                        heightDp = maxOf(element.widthDp, element.heightDp, 180f),
                                    )
                                } else {
                                    element.copy(orientation = nextOrientation)
                                }
                            } else {
                                element.copy(orientation = nextOrientation)
                            }
                            onChange(updated)
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Delete, contentDescription = null)
                        Text(" Xóa thành phần")
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorSettingsPanel(
    profile: HudProfile,
    scale: Float,
    showInactiveItems: Boolean,
    modifier: Modifier,
    onScaleChange: (Float) -> Unit,
    onShowInactiveChange: (Boolean) -> Unit,
    onOrientationModeChange: (HudProfileOrientationMode) -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        color = EditorPanel.copy(alpha = .97f),
        shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
        shadowElevation = 12.dp,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Settings, contentDescription = null, tint = HudCyan)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("Cài đặt HUD", fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(profile.name, fontSize = 10.sp, color = HudCyan)
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Đóng cài đặt HUD") }
            }
            HorizontalDivider(color = HudOutline)
            Text("Định hướng giao diện", fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HudProfileOrientationMode.entries.forEach { mode ->
                    val selected = profile.orientationMode == mode
                    FilterChip(
                        selected = selected,
                        onClick = { onOrientationModeChange(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    HudProfileOrientationMode.AUTO -> "Tự động"
                                    HudProfileOrientationMode.BOTH -> "Cả 2"
                                    HudProfileOrientationMode.PORTRAIT_ONLY -> "Chỉ dọc"
                                    HudProfileOrientationMode.LANDSCAPE_ONLY -> "Chỉ ngang"
                                },
                                fontSize = 9.sp,
                            )
                        },
                    )
                }
            }
            Text(
                when (profile.effectiveOrientationMode) {
                    HudProfileOrientationMode.PORTRAIT_ONLY -> "Hồ sơ này là giao diện Dọc. HUD sẽ tự động khóa xoay dọc."
                    HudProfileOrientationMode.LANDSCAPE_ONLY -> "Hồ sơ này là giao diện Ngang. HUD sẽ tự động khóa xoay ngang."
                    HudProfileOrientationMode.BOTH -> "Hồ sơ này hỗ trợ cả Ngang và Dọc. HUD sẽ tự xoay linh hoạt theo cảm biến."
                    HudProfileOrientationMode.AUTO -> "Đang tự nhận diện loại UI dựa trên các widget."
                },
                fontSize = 10.sp,
                color = HudCyan,
            )
            HorizontalDivider(color = HudOutline)
            Text("Tỷ lệ xem trước", fontSize = 12.sp)
            Text("${(scale * 100).roundToInt()}%", fontSize = 22.sp, color = HudCyan)
            Slider(value = scale, onValueChange = onScaleChange, valueRange = .5f..1.8f)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(.75f, 1f, 1.25f).forEach { preset ->
                    FilterChip(
                        selected = kotlin.math.abs(scale - preset) < .01f,
                        onClick = { onScaleChange(preset) },
                        label = { Text("${(preset * 100).roundToInt()}%", fontSize = 10.sp) },
                    )
                }
            }
            HorizontalDivider(color = HudOutline)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hiện thành phần đang ẩn", fontSize = 12.sp)
                    Text("Hiện cả thành phần bị ẩn trong hồ sơ", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = showInactiveItems, onCheckedChange = onShowInactiveChange)
            }
            Text(
                "Bản xem trước lấp đầy màn hình. Tỷ lệ này thay đổi đồng đều mọi thành phần HUD.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LayerControls(onMove: (HudLayerMove) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = { onMove(HudLayerMove.BRING_TO_FRONT) },
                modifier = Modifier.weight(1f),
            ) { Text("Đưa lên đầu", fontSize = 10.sp) }
            OutlinedButton(
                onClick = { onMove(HudLayerMove.MOVE_UP) },
                modifier = Modifier.weight(1f),
            ) { Text("Lên 1 lớp", fontSize = 10.sp) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = { onMove(HudLayerMove.MOVE_DOWN) },
                modifier = Modifier.weight(1f),
            ) { Text("Xuống 1 lớp", fontSize = 10.sp) }
            OutlinedButton(
                onClick = { onMove(HudLayerMove.SEND_TO_BACK) },
                modifier = Modifier.weight(1f),
            ) { Text("Đưa xuống cuối", fontSize = 10.sp) }
        }
    }
}

@Composable
private fun AlignmentControls(onAlign: (AlignmentAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = { onAlign(AlignmentAction.CENTER_HORIZONTAL) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.AlignHorizontalCenter, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(" Giữa ngang", fontSize = 10.sp)
            }
            OutlinedButton(
                onClick = { onAlign(AlignmentAction.CENTER_VERTICAL) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.AlignVerticalCenter, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(" Giữa dọc", fontSize = 10.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = { onAlign(AlignmentAction.ALIGN_LEFT) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.AutoMirrored.Rounded.FormatAlignLeft, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(" Lề trái", fontSize = 10.sp)
            }
            OutlinedButton(
                onClick = { onAlign(AlignmentAction.ALIGN_RIGHT) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.AutoMirrored.Rounded.FormatAlignRight, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(" Lề phải", fontSize = 10.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = { onAlign(AlignmentAction.ALIGN_TOP) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.VerticalAlignTop, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(" Sát đỉnh", fontSize = 10.sp)
            }
            OutlinedButton(
                onClick = { onAlign(AlignmentAction.ALIGN_BOTTOM) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.VerticalAlignBottom, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(" Sát đáy", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun PositionControls(element: HudElementConfig, onChange: (HudElementConfig) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        OutlinedIconButton(onClick = { onChange(element.copy(y = (element.y - 4f).coerceAtLeast(0f))) }) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Di chuyển lên")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedIconButton(onClick = { onChange(element.copy(x = (element.x - 4f).coerceAtLeast(0f))) }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Di chuyển sang trái")
            }
            OutlinedIconButton(onClick = { onChange(element.copy(y = element.y + 4f)) }) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Di chuyển xuống")
            }
            OutlinedIconButton(onClick = { onChange(element.copy(x = element.x + 4f)) }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Di chuyển sang phải")
            }
        }
    }
}

@Composable
private fun InspectorSection(title: String) {
    Column {
        HorizontalDivider(color = HudOutline)
        Text(title, color = HudCyan, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun InspectorSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text(display, style = MaterialTheme.typography.labelSmall, color = HudCyan)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun <T : Enum<T>> EnumChips(label: String, values: List<T>, selected: T, onSelect: (T) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { value ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(shortEnum(value.name)) })
            }
        }
    }
}

private data class LibraryDrag(val type: HudWidgetType, val position: Offset)

private fun widgetLabel(type: HudWidgetType): String = when (type) {
    HudWidgetType.SPEED -> "Tốc độ hiện tại"
    HudWidgetType.SPEED_NUMBER -> "Số tốc độ"
    HudWidgetType.SPEED_LIMIT -> "Giới hạn tốc độ"
    HudWidgetType.SPEED_LIMIT_BAR -> "Thanh tốc độ → giới hạn"
    HudWidgetType.TURN -> "Hướng rẽ tiếp theo"
    HudWidgetType.NEXT_TURN -> "Hướng rẽ thứ hai"
    HudWidgetType.DISTANCE -> "Khoảng cách tới chỗ rẽ"
    HudWidgetType.STREET -> "Đường hiện tại"
    HudWidgetType.NEXT_STREET -> "Đường tiếp theo"
    HudWidgetType.ETA -> "Giờ đến dự kiến"
    HudWidgetType.REMAINING -> "Quãng đường còn lại"
    HudWidgetType.GPS -> "Trạng thái GPS"
    HudWidgetType.CONNECTION -> "Bluetooth + pin"
    HudWidgetType.ALERTS -> "Cảnh báo sắp tới"
    HudWidgetType.LANES -> "Chỉ dẫn làn đường"
    HudWidgetType.TRAFFIC_DELAY -> "Chậm do kẹt xe"
    HudWidgetType.CUSTOM_TEXT -> "Chữ tùy chỉnh"
    HudWidgetType.CUSTOM_IMAGE -> "Ảnh tùy chỉnh"
    HudWidgetType.PHONE_BATTERY -> "Pin điện thoại (cũ)"
    HudWidgetType.CLOCK -> "Đồng hồ số"
    HudWidgetType.COMPASS -> "La bàn số"
    HudWidgetType.TRIP_PROGRESS -> "Tiến độ hành trình"
}

private fun widgetHint(type: HudWidgetType): String = when (type) {
    HudWidgetType.SPEED, HudWidgetType.SPEED_NUMBER,
    HudWidgetType.SPEED_LIMIT, HudWidgetType.SPEED_LIMIT_BAR -> "Phương tiện"
    HudWidgetType.TURN, HudWidgetType.NEXT_TURN, HudWidgetType.DISTANCE, HudWidgetType.LANES, HudWidgetType.COMPASS -> "Điều hướng"
    HudWidgetType.STREET, HudWidgetType.NEXT_STREET, HudWidgetType.ETA, HudWidgetType.REMAINING, HudWidgetType.TRIP_PROGRESS -> "Lộ trình"
    HudWidgetType.GPS, HudWidgetType.CONNECTION, HudWidgetType.CLOCK -> "Thời gian & Trạng thái"
    HudWidgetType.ALERTS -> "Cảnh báo"
    HudWidgetType.TRAFFIC_DELAY -> "Giao thông"
    HudWidgetType.CUSTOM_TEXT -> "Cá nhân hóa"
    HudWidgetType.CUSTOM_IMAGE -> "Cá nhân hóa"
    HudWidgetType.PHONE_BATTERY -> "Trạng thái"
}

private fun shortEnum(name: String): String = when (name) {
    "HORIZONTAL" -> "Ngang"
    "VERTICAL" -> "Dọc"
    "AUTO" -> "Tự động"
    "NORMAL" -> "Thường"
    "BOLD" -> "Đậm"
    "BLACK" -> "Rất đậm"
    "START" -> "Đầu"
    "CENTER" -> "Giữa"
    "END" -> "Cuối"
    else -> name.lowercase().replaceFirstChar { it.uppercase() }
}

private val EditorBackground = Color(0xFF111518)
private val EditorPanel = Color(0xFF20272B)
