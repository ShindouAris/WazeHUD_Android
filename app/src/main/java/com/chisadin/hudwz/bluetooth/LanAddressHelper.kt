package com.chisadin.hudwz.bluetooth

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Helpers for detecting the device current LAN IPv4 address (Wi-Fi STA or hotspot)
 * and for validating that an incoming WebSocket client IP is inside a private/LAN range.
 */
object LanAddressHelper {

    /**
     * Returns the best LAN IPv4 address. Prefers non-link-local private addresses.
     * Returns null when no usable interface is available.
     */
    fun getLanIp(context: Context): String? {
        // 1. NetworkInterface enumeration — covers Wi-Fi STA and hotspot (tethering).
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                val candidates = mutableListOf<Pair<Inet4Address, Boolean>>() // addr, isLinkLocal
                for (iface in interfaces.asSequence()) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (addr in iface.inetAddresses.asSequence()) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress && isPrivateAddress(addr)) {
                            candidates += Pair(addr, addr.isLinkLocalAddress)
                        }
                    }
                }
                // Prefer non-link-local first
                (candidates.firstOrNull { !it.second } ?: candidates.firstOrNull())
                    ?.first?.hostAddress?.let { return it }
            }
        } catch (_: Exception) {}

        // 2. Legacy WifiManager fallback (STA only).
        try {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wm?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Returns true if [addr] is a private / link-local / loopback address.
     * Used to reject WebSocket connections from public internet.
     *
     * Accepted: 127/8, 10/8, 172.16/12, 192.168/16, 169.254/16.
     */
    fun isPrivateAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress) return true
        if (addr is Inet4Address) {
            val b = addr.address
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            if (b0 == 10) return true                          // 10.x.x.x
            if (b0 == 172 && b1 in 16..31) return true        // 172.16-31.x.x
            if (b0 == 192 && b1 == 168) return true           // 192.168.x.x
            if (b0 == 169 && b1 == 254) return true           // 169.254.x.x
        }
        return false
    }

    /** True when the device has an active Wi-Fi or Ethernet connection. */
    fun hasWifiConnectivity(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
