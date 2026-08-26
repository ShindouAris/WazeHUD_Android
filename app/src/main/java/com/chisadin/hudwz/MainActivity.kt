package com.chisadin.hudwz

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chisadin.hudwz.domain.HudOrientation
import com.chisadin.hudwz.domain.HudSettings
import com.chisadin.hudwz.domain.HudThemeMode
import com.chisadin.hudwz.ui.HudApp
import com.chisadin.hudwz.ui.theme.HudwzTheme
import com.chisadin.hudwz.viewmodel.HudViewModel

import com.chisadin.hudwz.service.HudBluetoothService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: HudViewModel = viewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val darkTheme = when (settings.themeMode) {
                HudThemeMode.DAY -> false
                HudThemeMode.NIGHT -> true
                HudThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            HudwzTheme(darkTheme = darkTheme) {
                HudApp(viewModel) { hudActive, editorActive ->
                    ApplyWindowBehavior(hudActive, editorActive, settings)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { HudBluetoothService.restore(this) }
    }
}

@Composable
private fun ComponentActivity.ApplyWindowBehavior(
    hudActive: Boolean,
    editorActive: Boolean,
    settings: HudSettings,
) {
    val view = LocalView.current
    DisposableEffect(hudActive, editorActive, settings.keepScreenAwake, settings.immersiveMode, settings.brightness, settings.orientation) {
        val controller = WindowCompat.getInsetsController(window, view)
        if (hudActive) {
            if (settings.keepScreenAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            requestedOrientation = when (settings.orientation) {
                HudOrientation.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                HudOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                HudOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            if (settings.immersiveMode) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            window.attributes = window.attributes.apply { screenBrightness = settings.brightness.coerceIn(.1f, 1f) }
        } else if (editorActive) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (settings.immersiveMode) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
        onDispose { }
    }
}
