package com.chisadin.hudwz.bluetooth

import java.util.UUID

data class GattProfile(
    val serviceUuid: UUID,
    val writeUuid: UUID,
    val notifyUuid: UUID,
    val cccdUuid: UUID = CCCD_UUID,
    val requestedMtu: Int = 247,
    val advertisedName: String,
    val id: String,
) {
    companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val CLASSIC_SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

        val HLP = GattProfile(
            serviceUuid = BleTransport.SERVICE_UUID,
            writeUuid = BleTransport.TX_UUID,
            notifyUuid = BleTransport.RX_UUID,
            cccdUuid = CCCD_UUID,
            requestedMtu = 247,
            advertisedName = "Waze HUD",
            id = "hlp1",
        )

        val VIETMAP_H1 = GattProfile(
            serviceUuid = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
            writeUuid = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"),
            notifyUuid = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
            cccdUuid = CCCD_UUID,
            requestedMtu = 247,
            advertisedName = "VIETMAP_HUD_WZ",
            id = "vietmap_hud_h1",
        )
    }
}
