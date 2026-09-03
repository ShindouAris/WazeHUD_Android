package com.chisadin.hudwz.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.chisadin.hudwz.domain.TransportType

object BluetoothPermissionPolicy {
    fun scanPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun connectionPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
    } else emptyArray()

    fun locationPermissions(): Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    fun receiverPermissions(type: TransportType): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (type) {
                TransportType.BLE -> {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                    add(Manifest.permission.BLUETOOTH_ADVERTISE)
                }
                TransportType.WIFI_WEBSOCKET -> {}
                else -> add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }.toTypedArray()

    fun notificationPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else emptyArray()

    /**
     * Every supported Bluetooth transport needs BLUETOOTH_CONNECT on Android 12+.
     * It is also a valid runtime prerequisite for a connectedDevice foreground
     * service on Android 14+, so checking it before service creation prevents a
     * SecurityException during Service.startForeground().
     */
    fun canStartConnectedDeviceService(context: Context): Boolean =
        has(context, connectionPermissions())

    fun has(context: Context, permissions: Array<String>): Boolean = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
