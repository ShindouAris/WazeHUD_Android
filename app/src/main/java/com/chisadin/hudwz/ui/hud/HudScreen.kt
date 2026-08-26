package com.chisadin.hudwz.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.StayCurrentLandscape
import androidx.compose.material.icons.rounded.StayCurrentPortrait
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chisadin.hudwz.domain.HudOrientation
import com.chisadin.hudwz.domain.HudProfile
import com.chisadin.hudwz.domain.HudProfileOrientationMode
import com.chisadin.hudwz.domain.HudSettings
import com.chisadin.hudwz.domain.HudState

@Composable
fun HudScreen(
    state: HudState,
    profile: HudProfile,
    settings: HudSettings,
    onExit: () -> Unit,
    onMirrorChanged: (Boolean) -> Unit,
    onOrientationChanged: (HudOrientation) -> Unit = {},
) {
    var touchLocked by remember { mutableStateOf(settings.preventAccidentalTouches) }
    LaunchedEffect(settings.preventAccidentalTouches) {
        if (!settings.preventAccidentalTouches) touchLocked = false
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.displayCutout),
    ) {
        val forcePortrait = when (profile.effectiveOrientationMode) {
            HudProfileOrientationMode.PORTRAIT_ONLY -> true
            HudProfileOrientationMode.LANDSCAPE_ONLY -> false
            else -> when (settings.orientation) {
                HudOrientation.PORTRAIT -> true
                HudOrientation.LANDSCAPE -> false
                HudOrientation.SENSOR -> null
            }
        }
        HudRenderer(
            state = state,
            profile = profile,
            mirror = settings.mirrorMode,
            fontScale = settings.fontScale,
            modifier = Modifier.fillMaxSize(),
            forcePortrait = forcePortrait,
        )
        if (!touchLocked) {
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) {
                HudControlButton(
                    label = when {
                        profile.effectiveOrientationMode == HudProfileOrientationMode.PORTRAIT_ONLY -> "Giao diện: Chỉ dọc (Cố định theo hồ sơ)"
                        profile.effectiveOrientationMode == HudProfileOrientationMode.LANDSCAPE_ONLY -> "Giao diện: Chỉ ngang (Cố định theo hồ sơ)"
                        settings.orientation == HudOrientation.SENSOR -> "Xoay HUD: Cả ngang & dọc (Theo cảm biến)"
                        settings.orientation == HudOrientation.PORTRAIT -> "Xoay HUD: Dọc (Chạm để đổi)"
                        else -> "Xoay HUD: Ngang (Chạm để đổi)"
                    },
                    onClick = {
                        val next = when (settings.orientation) {
                            HudOrientation.SENSOR -> HudOrientation.PORTRAIT
                            HudOrientation.PORTRAIT -> HudOrientation.LANDSCAPE
                            HudOrientation.LANDSCAPE -> HudOrientation.SENSOR
                        }
                        onOrientationChanged(next)
                    },
                ) {
                    Icon(
                        when {
                            profile.effectiveOrientationMode == HudProfileOrientationMode.PORTRAIT_ONLY -> Icons.Rounded.StayCurrentPortrait
                            profile.effectiveOrientationMode == HudProfileOrientationMode.LANDSCAPE_ONLY -> Icons.Rounded.StayCurrentLandscape
                            settings.orientation == HudOrientation.SENSOR -> Icons.Rounded.ScreenRotation
                            settings.orientation == HudOrientation.PORTRAIT -> Icons.Rounded.StayCurrentPortrait
                            else -> Icons.Rounded.StayCurrentLandscape
                        },
                        contentDescription = null,
                    )
                }
                HudControlButton("Lật gương HUD", onClick = { onMirrorChanged(!settings.mirrorMode) }) {
                    Icon(Icons.Rounded.Flip, contentDescription = null)
                }
                if (settings.preventAccidentalTouches) {
                    HudControlButton("Khóa điều khiển HUD", onClick = { touchLocked = true }) {
                        Icon(Icons.Rounded.Lock, contentDescription = null)
                    }
                }
                HudControlButton("Thoát HUD", onClick = onExit) {
                    Icon(Icons.Rounded.Close, contentDescription = null)
                }
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = .72f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .semantics { contentDescription = "Nhấn giữ để mở khóa điều khiển HUD" }
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { touchLocked = false }) },
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LockOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(" Giữ để mở khóa", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun HudControlButton(
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = .72f),
        shape = CircleShape,
        modifier = Modifier.padding(start = 8.dp),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
            content()
        }
    }
}
