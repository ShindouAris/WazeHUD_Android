package com.chisadin.hudwz.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.chisadin.hudwz.data.HudRepository
import kotlin.math.roundToInt

class GpsSpeedTracker(
    private val context: Context,
    private val repository: HudRepository,
) {
    private val locationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    @Volatile
    var isTracking: Boolean = false
        private set

    private var lastLocation: Location? = null
    private var lastSpeedUpdateMs = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val now = SystemClock.elapsedRealtime()
            val speedKmh = if (location.hasSpeed() && location.speed >= 0f) {
                (location.speed * 3.6f).roundToInt().coerceAtLeast(0)
            } else {
                calculateSpeedFromDelta(location)
            }

            val bearing = if (location.hasBearing()) location.bearing else null
            lastSpeedUpdateMs = now
            lastLocation = location

            if (speedKmh != null) {
                repository.updateGpsSpeed(speedKmh, bearing)
            }
        }

        override fun onProviderEnabled(provider: String) {
            repository.updateHudState { it.copy(gpsAvailable = true) }
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                repository.updateHudState { it.copy(gpsAvailable = false) }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun calculateSpeedFromDelta(location: Location): Int? {
        val last = lastLocation ?: return null
        val dtSec = (location.time - last.time) / 1000.0
        if (dtSec !in 0.3..5.0) return null
        val distM = location.distanceTo(last)
        return ((distM / dtSec) * 3.6).roundToInt().coerceAtLeast(0)
    }

    fun start(): Boolean {
        if (isTracking) return true
        val lm = locationManager ?: return false

        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            Log.w("GpsSpeedTracker", "Chưa có quyền vị trí (ACCESS_FINE_LOCATION) để đo tốc độ GPS")
            return false
        }

        try {
            val providers = lm.allProviders
            val minTimeMs = 500L
            val minDistanceM = 0f

            var registered = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && providers.contains(LocationManager.FUSED_PROVIDER)) {
                try {
                    lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, minTimeMs, minDistanceM, locationListener, Looper.getMainLooper())
                    registered = true
                } catch (_: Throwable) {}
            }
            if (!registered && providers.contains(LocationManager.GPS_PROVIDER)) {
                try {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTimeMs, minDistanceM, locationListener, Looper.getMainLooper())
                    registered = true
                } catch (_: Throwable) {}
            }
            if (!registered && providers.contains(LocationManager.NETWORK_PROVIDER)) {
                try {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, minTimeMs, minDistanceM, locationListener, Looper.getMainLooper())
                    registered = true
                } catch (_: Throwable) {}
            }

            if (!registered) {
                Log.w("GpsSpeedTracker", "Không tìm thấy provider GPS phù hợp")
                return false
            }

            val lastKnown = (if (hasFine && providers.contains(LocationManager.GPS_PROVIDER)) {
                runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
            } else null) ?: (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && providers.contains(LocationManager.FUSED_PROVIDER)) {
                runCatching { lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER) }.getOrNull()
            } else null) ?: runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()

            lastKnown?.let { loc ->
                val ageMs = System.currentTimeMillis() - loc.time
                if (ageMs < 60_000L) {
                    val spd = if (loc.hasSpeed() && loc.speed >= 0f) {
                        (loc.speed * 3.6f).roundToInt().coerceAtLeast(0)
                    } else 0
                    val bearing = if (loc.hasBearing()) loc.bearing else null
                    repository.updateGpsSpeed(spd, bearing)
                }
            }

            isTracking = true
            repository.log("GPS", "Đã bật theo dõi tốc độ GPS hệ thống")
            return true
        } catch (e: SecurityException) {
            Log.e("GpsSpeedTracker", "Lỗi quyền khi đăng ký GPS: ${e.message}")
            return false
        } catch (e: Throwable) {
            Log.e("GpsSpeedTracker", "Lỗi khởi động GPS tracker: ${e.message}")
            return false
        }
    }

    fun stop() {
        if (!isTracking) return
        try {
            locationManager?.removeUpdates(locationListener)
            repository.log("GPS", "Đã dừng theo dõi tốc độ GPS hệ thống")
        } catch (_: Throwable) {}
        isTracking = false
        lastLocation = null
    }
}
