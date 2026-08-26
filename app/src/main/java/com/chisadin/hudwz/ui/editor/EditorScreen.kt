package com.chisadin.hudwz.ui.editor

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.chisadin.hudwz.domain.HudElementConfig
import com.chisadin.hudwz.domain.HudElementOrientation
import com.chisadin.hudwz.domain.HudFontWeight
import com.chisadin.hudwz.domain.HudProfile
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

@Composable
fun EditorScreen(
    profile: HudProfile,
    fontScale: Float,
    onBack: () -> Unit,
    onElementChange: (HudElementConfig) -> Unit,
    onScaleChange: (Float) -> Unit,
    onAddElement: (HudWidgetType, Float, Float) -> HudElementConfig,
    onRemoveElement: (String) -> Unit,
    onMoveElement: (String, HudLayerMove) -> Unit,
) {
    val density = LocalDensity.current
    var selectedId by remember(profile.id) { mutableStateOf(profile.elements.firstOrNull()?.id) }
    var workingProfile by remember(profile.id) { mutableStateOf(profile) }
    var libraryOpen by remember { mutableStateOf(false) }
    var inspectorOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var showInactiveItems by remember { mutableStateOf(false) }
    var canvasBounds by remember { mutableStateOf(Rect.Zero) }
    var libraryDrag by remember { mutableStateOf<LibraryDrag?>(null) }

    LaunchedEffect(profile.id) { selectedId = profile.elements.firstOrNull()?.id }
    LaunchedEffect(profile) {
        if (profile.id == workingProfile.id && profile != workingProfile) {
            workingProfile = profile
            if (selectedId != null && profile.elements.none { it.id == selectedId }) {
                selectedId = profile.elements.firstOrNull()?.id
            }
        }
    }
    val selected = workingProfile.elements.firstOrNull { it.id == selectedId }
    val previewState = PreviewHudState

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
        workingProfile = workingProfile.copy(
            elements = workingProfile.elements.map { if (it.id == clamped.id) clamped else it },
        )
        onElementChange(clamped)
    }

    fun addWidget(type: HudWidgetType, centerX: Float, centerY: Float) {
        val prototype = defaultHudElement(type, "drop-preview")
        val x = (centerX - prototype.widthDp / 2f).coerceAtLeast(0f)
        val y = (centerY - prototype.heightDp / 2f).coerceAtLeast(0f)
        val next = onAddElement(type, x, y)
        workingProfile = workingProfile.copy(elements = workingProfile.elements + next)
        selectedId = next.id
    }

    fun removeWidget(id: String) {
        workingProfile = workingProfile.copy(elements = workingProfile.elements.filterNot { it.id == id })
        onRemoveElement(id)
        selectedId = workingProfile.elements.lastOrNull()?.id
    }

    fun moveWidget(id: String, move: HudLayerMove) {
        workingProfile = workingProfile.copy(
            elements = reorderHudElements(workingProfile.elements, id, move),
        )
        onMoveElement(id, move)
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
            state = previewState,
            fontScale = fontScale,
            showInactiveItems = showInactiveItems,
            selectedId = selectedId,
            modifier = Modifier.fillMaxSize(),
            onCanvasBounds = { canvasBounds = it },
            onSelect = { selectedId = it },
            onClearFocus = {
                selectedId = null
                inspectorOpen = false
            },
            onElementChange = ::updateWorkingElement,
        )

        Row(
            Modifier.align(Alignment.TopStart).padding(8.dp).zIndex(10f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FloatingEditorButton("Back", onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
            FloatingEditorButton("Items", {
                libraryOpen = !libraryOpen
                inspectorOpen = false
                settingsOpen = false
            }, active = libraryOpen) {
                Icon(Icons.Rounded.Widgets, contentDescription = null)
            }
            FloatingEditorButton("Unfocus item", {
                selectedId = null
                inspectorOpen = false
            }, enabled = selected != null) {
                Icon(Icons.Rounded.Close, contentDescription = null)
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(8.dp).zIndex(10f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FloatingEditorButton("Item properties", {
                inspectorOpen = !inspectorOpen
                libraryOpen = false
                settingsOpen = false
            }, active = inspectorOpen, enabled = selected != null) {
                Icon(Icons.Rounded.Tune, contentDescription = null)
            }
            FloatingEditorButton("HUD settings", {
                settingsOpen = !settingsOpen
                libraryOpen = false
                inspectorOpen = false
            }, active = settingsOpen) {
                Icon(Icons.Rounded.Settings, contentDescription = null)
            }
        }

        if (libraryOpen) {
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

        if (inspectorOpen) {
            InspectorPanel(
                element = selected,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 62.dp).width(250.dp).fillMaxHeight().zIndex(5f),
                onChange = ::updateWorkingElement,
                onDelete = { selected?.id?.let(::removeWidget) },
                onLayerMove = { move -> selected?.id?.let { moveWidget(it, move) } },
                onClose = { inspectorOpen = false },
            )
        }

        if (settingsOpen) {
            EditorSettingsPanel(
                profileName = workingProfile.name,
                scale = workingProfile.hudScale,
                showInactiveItems = showInactiveItems,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 62.dp).width(238.dp).zIndex(5f),
                onScaleChange = {
                    workingProfile = workingProfile.copy(hudScale = it)
                    onScaleChange(it)
                },
                onShowInactiveChange = { showInactiveItems = it },
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
    state: HudState,
    fontScale: Float,
    showInactiveItems: Boolean,
    selectedId: String?,
    modifier: Modifier,
    onCanvasBounds: (Rect) -> Unit,
    onSelect: (String) -> Unit,
    onClearFocus: () -> Unit,
    onElementChange: (HudElementConfig) -> Unit,
) {
    Box(
        modifier
            .background(HudBlack)
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
                selectedId = selectedId,
                onSelect = onSelect,
                onElementChange = onElementChange,
            )
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
                    Text("Item library", fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("Tap to add · hold and drag", fontSize = 10.sp)
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close item library") }
            }
            HorizontalDivider(color = HudOutline)
            LazyColumn(
                Modifier.fillMaxSize().padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(HudWidgetType.entries, key = { it.name }) { type ->
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
            .semantics { contentDescription = "Add or drag ${widgetLabel(type)}" },
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
    modifier: Modifier,
    onChange: (HudElementConfig) -> Unit,
    onDelete: () -> Unit,
    onLayerMove: (HudLayerMove) -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        color = EditorPanel.copy(alpha = .97f),
        shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
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
                Text("Select an item", style = MaterialTheme.typography.titleLarge)
                Text("Open Items to add widgets, then select one on the canvas.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(widgetLabel(element.type), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Text("Item properties", fontSize = 10.sp, color = HudCyan)
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close item properties")
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete ${widgetLabel(element.type)}", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (element.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, contentDescription = null)
                        Text(" Visible", Modifier.weight(1f))
                        Switch(checked = element.visible, onCheckedChange = { onChange(element.copy(visible = it)) })
                    }
                }
                item { InspectorSection("Layers") }
                item { LayerControls(onLayerMove) }
                item { InspectorSection("Position") }
                item {
                    Text(
                        "x ${element.x.roundToInt()} dp · y ${element.y.roundToInt()} dp · top-left",
                        fontSize = 10.sp,
                        color = HudCyan,
                    )
                }
                item { PositionControls(element, onChange) }
                item { InspectorSection("Size") }
                if (element.type.locksAspectRatio) {
                    item {
                        Text("Aspect ratio locked · 1:1", fontSize = 10.sp, color = HudCyan)
                    }
                    item {
                        InspectorSlider("Size", element.widthDp, 28f..500f, "${element.widthDp.roundToInt()} dp") {
                            onChange(element.copy(widthDp = it, heightDp = it, iconSizeDp = it * .9f, scale = 1f))
                        }
                    }
                } else {
                    item { InspectorSlider("Width", element.widthDp, 32f..600f, "${element.widthDp.roundToInt()} dp") { onChange(element.copy(widthDp = it)) } }
                    item { InspectorSlider("Height", element.heightDp, 28f..500f, "${element.heightDp.roundToInt()} dp") { onChange(element.copy(heightDp = it)) } }
                    item { InspectorSlider("Item scale", element.scale, .25f..2f, "${(element.scale * 100).roundToInt()}%") { onChange(element.copy(scale = it)) } }
                    item { InspectorSlider("Icon", element.iconSizeDp, 4f..220f, "${element.iconSizeDp.roundToInt()} dp") { onChange(element.copy(iconSizeDp = it)) } }
                }
                item { InspectorSection("Appearance") }
                item { InspectorSlider("Opacity", element.opacity, .1f..1f, "${(element.opacity * 100).roundToInt()}%") { onChange(element.copy(opacity = it)) } }
                if (!element.type.locksAspectRatio || element.type == HudWidgetType.SPEED) {
                    item { InspectorSlider("Font", element.fontSizeSp, 6f..140f, "${element.fontSizeSp.roundToInt()} sp") { onChange(element.copy(fontSizeSp = it)) } }
                }
                item { InspectorSlider("Spacing", element.spacingDp, 0f..40f, "${element.spacingDp.roundToInt()} dp") { onChange(element.copy(spacingDp = it)) } }
                item { EnumChips("Weight", HudFontWeight.entries, element.fontWeight) { onChange(element.copy(fontWeight = it)) } }
                item { EnumChips("Align", HudTextAlignment.entries, element.textAlignment) { onChange(element.copy(textAlignment = it)) } }
                item { EnumChips("Direction", HudElementOrientation.entries, element.orientation) { onChange(element.copy(orientation = it)) } }
                item {
                    OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Delete, contentDescription = null)
                        Text(" Remove item")
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorSettingsPanel(
    profileName: String,
    scale: Float,
    showInactiveItems: Boolean,
    modifier: Modifier,
    onScaleChange: (Float) -> Unit,
    onShowInactiveChange: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        color = EditorPanel.copy(alpha = .97f),
        shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
        shadowElevation = 12.dp,
        modifier = modifier,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Settings, contentDescription = null, tint = HudCyan)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("HUD settings", fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(profileName, fontSize = 10.sp, color = HudCyan)
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close HUD settings") }
            }
            HorizontalDivider(color = HudOutline)
            Text("Preview scale", fontSize = 12.sp)
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
                    Text("Show inactive items", fontSize = 12.sp)
                    Text("Also show profile items marked hidden", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = showInactiveItems, onCheckedChange = onShowInactiveChange)
            }
            Text(
                "Preview fills the screen. Scale changes every HUD item proportionally.",
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
            ) { Text("Bring front", fontSize = 10.sp) }
            OutlinedButton(
                onClick = { onMove(HudLayerMove.MOVE_UP) },
                modifier = Modifier.weight(1f),
            ) { Text("Layer +1", fontSize = 10.sp) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = { onMove(HudLayerMove.MOVE_DOWN) },
                modifier = Modifier.weight(1f),
            ) { Text("Layer −1", fontSize = 10.sp) }
            OutlinedButton(
                onClick = { onMove(HudLayerMove.SEND_TO_BACK) },
                modifier = Modifier.weight(1f),
            ) { Text("Send back", fontSize = 10.sp) }
        }
    }
}

@Composable
private fun PositionControls(element: HudElementConfig, onChange: (HudElementConfig) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        OutlinedIconButton(onClick = { onChange(element.copy(y = (element.y - 4f).coerceAtLeast(0f))) }) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedIconButton(onClick = { onChange(element.copy(x = (element.x - 4f).coerceAtLeast(0f))) }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Move left")
            }
            OutlinedIconButton(onClick = { onChange(element.copy(y = element.y + 4f)) }) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move down")
            }
            OutlinedIconButton(onClick = { onChange(element.copy(x = element.x + 4f)) }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Move right")
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
    HudWidgetType.SPEED -> "Current speed"
    HudWidgetType.SPEED_LIMIT -> "Speed limit"
    HudWidgetType.TURN -> "Next maneuver"
    HudWidgetType.NEXT_TURN -> "Second maneuver"
    HudWidgetType.DISTANCE -> "Turn distance"
    HudWidgetType.STREET -> "Current street"
    HudWidgetType.NEXT_STREET -> "Next street"
    HudWidgetType.ETA -> "ETA"
    HudWidgetType.REMAINING -> "Remaining route"
    HudWidgetType.GPS -> "GPS status"
    HudWidgetType.CONNECTION -> "Bluetooth status"
    HudWidgetType.ALERTS -> "Upcoming alerts"
    HudWidgetType.LANES -> "Lane guidance"
}

private fun widgetHint(type: HudWidgetType): String = when (type) {
    HudWidgetType.SPEED, HudWidgetType.SPEED_LIMIT -> "Vehicle"
    HudWidgetType.TURN, HudWidgetType.NEXT_TURN, HudWidgetType.DISTANCE, HudWidgetType.LANES -> "Navigation"
    HudWidgetType.STREET, HudWidgetType.NEXT_STREET, HudWidgetType.ETA, HudWidgetType.REMAINING -> "Route"
    HudWidgetType.GPS, HudWidgetType.CONNECTION -> "Status"
    HudWidgetType.ALERTS -> "Warnings"
}

private fun shortEnum(name: String): String = when (name) {
    "HORIZONTAL" -> "H"
    "VERTICAL" -> "V"
    else -> name.lowercase().replaceFirstChar { it.uppercase() }
}

private val EditorBackground = Color(0xFF111518)
private val EditorPanel = Color(0xFF20272B)
