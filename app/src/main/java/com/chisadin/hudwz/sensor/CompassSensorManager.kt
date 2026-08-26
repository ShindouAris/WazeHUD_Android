package com.chisadin.hudwz.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Lắng nghe góc hướng la bàn từ cảm biến thiết bị (Rotation Vector hoặc Orientation)
 * Tự động hủy đăng ký khi Composable rời khỏi cây giao diện.
 */
@Suppress("DEPRECATION")
@Composable
fun rememberDeviceHeading(fallbackDegrees: Float?): Float {
    if (fallbackDegrees != null) return fallbackDegrees
    val context = LocalContext.current
    var heading by remember { mutableFloatStateOf(45f) } // Mặc định 45 độ (NE)

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val orientationSensor = if (rotationSensor == null) {
            sensorManager?.getDefaultSensor(Sensor.TYPE_ORIENTATION)
        } else null

        val sensorToUse = rotationSensor ?: orientationSensor
        val listener = object : SensorEventListener {
            val rotationMatrix = FloatArray(9)
            val orientation = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    val degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    heading = (degrees + 360f) % 360f
                } else if (event.sensor.type == Sensor.TYPE_ORIENTATION) {
                    heading = (event.values[0] + 360f) % 360f
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorToUse?.let {
            sensorManager?.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }

        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }

    return heading
}

/**
 * Chuyển đổi góc độ (0° - 360°) sang ký hiệu chữ cái của hướng la bàn
 */
fun headingToDirectionText(degrees: Float): String {
    val normalized = (degrees % 360f + 360f) % 360f
    return when {
        normalized >= 337.5f || normalized < 22.5f -> "N"
        normalized < 67.5f -> "NE"
        normalized < 112.5f -> "E"
        normalized < 157.5f -> "SE"
        normalized < 202.5f -> "S"
        normalized < 247.5f -> "SW"
        normalized < 292.5f -> "W"
        else -> "NW"
    }
}

/**
 * Chuyển đổi góc độ sang tiếng Việt
 */
fun headingToDirectionVietnamese(degrees: Float): String {
    val normalized = (degrees % 360f + 360f) % 360f
    return when {
        normalized >= 337.5f || normalized < 22.5f -> "Bắc"
        normalized < 67.5f -> "Đông Bắc"
        normalized < 112.5f -> "Đông"
        normalized < 157.5f -> "Đông Nam"
        normalized < 202.5f -> "Nam"
        normalized < 247.5f -> "Tây Nam"
        normalized < 292.5f -> "Tây"
        else -> "Tây Bắc"
    }
}
