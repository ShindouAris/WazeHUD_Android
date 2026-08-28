package com.chisadin.hudwz.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LanAddressHelperTest {
    @Test
    fun validatesPrivateAndLoopbackAddresses() {
        assertTrue(LanAddressHelper.isPrivateAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(LanAddressHelper.isPrivateAddress(InetAddress.getByName("10.0.0.1")))
        assertTrue(LanAddressHelper.isPrivateAddress(InetAddress.getByName("10.254.0.1")))
        assertTrue(LanAddressHelper.isPrivateAddress(InetAddress.getByName("172.16.0.1")))
        assertTrue(LanAddressHelper.isPrivateAddress(InetAddress.getByName("172.24.10.5")))
        assertTrue(LanAddressHelper.isPrivateAddress(InetAddress.getByName("172.31.255.255")))
        assertTrue(LanAddressHelper.isPrivateAddress(InetAddress.getByName("192.168.1.1")))
        assertTrue(LanAddressHelper.isPrivateAddress(InetAddress.getByName("192.168.43.1")))
        assertTrue(LanAddressHelper.isPrivateAddress(InetAddress.getByName("169.254.1.1")))
    }

    @Test
    fun rejectsPublicInternetAddresses() {
        assertFalse(LanAddressHelper.isPrivateAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(LanAddressHelper.isPrivateAddress(InetAddress.getByName("1.1.1.1")))
        assertFalse(LanAddressHelper.isPrivateAddress(InetAddress.getByName("172.15.255.255")))
        assertFalse(LanAddressHelper.isPrivateAddress(InetAddress.getByName("172.32.0.1")))
        assertFalse(LanAddressHelper.isPrivateAddress(InetAddress.getByName("203.0.113.1")))
    }
}