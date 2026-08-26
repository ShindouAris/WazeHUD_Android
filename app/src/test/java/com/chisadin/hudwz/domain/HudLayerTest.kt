package com.chisadin.hudwz.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudLayerTest {
    private val elements = listOf(
        defaultHudElement(HudWidgetType.SPEED, "a"),
        defaultHudElement(HudWidgetType.STREET, "b"),
        defaultHudElement(HudWidgetType.ALERTS, "c"),
    )

    @Test
    fun movesElementToFrontAndBack() {
        assertEquals(
            listOf("b", "c", "a"),
            reorderHudElements(elements, "a", HudLayerMove.BRING_TO_FRONT).map { it.id },
        )
        assertEquals(
            listOf("c", "a", "b"),
            reorderHudElements(elements, "c", HudLayerMove.SEND_TO_BACK).map { it.id },
        )
    }

    @Test
    fun movesOneLayerAndClampsAtEdges() {
        assertEquals(
            listOf("a", "c", "b"),
            reorderHudElements(elements, "b", HudLayerMove.MOVE_UP).map { it.id },
        )
        assertEquals(
            listOf("b", "a", "c"),
            reorderHudElements(elements, "b", HudLayerMove.MOVE_DOWN).map { it.id },
        )
        assertEquals(elements, reorderHudElements(elements, "a", HudLayerMove.MOVE_DOWN))
        assertEquals(elements, reorderHudElements(elements, "c", HudLayerMove.MOVE_UP))
    }

    @Test
    fun locksOnlySquareVisualWidgets() {
        assertTrue(HudWidgetType.SPEED.locksAspectRatio)
        assertTrue(HudWidgetType.SPEED_LIMIT.locksAspectRatio)
        assertTrue(HudWidgetType.TURN.locksAspectRatio)
        assertTrue(HudWidgetType.NEXT_TURN.locksAspectRatio)
        assertFalse(HudWidgetType.LANES.locksAspectRatio)
        assertFalse(HudWidgetType.ALERTS.locksAspectRatio)
        assertFalse(HudWidgetType.STREET.locksAspectRatio)
    }

    @Test
    fun migratesLegacyNormalizedPlacementToTopLeftDp() {
        val legacy = HudProfile(
            id = "legacy",
            name = "Legacy",
            layoutVersion = 1,
            elements = listOf(
                defaultHudElement(HudWidgetType.SPEED, "speed").copy(
                    x = .5f,
                    y = .25f,
                    widthDp = 100f,
                    heightDp = 100f,
                ),
            ),
        )
        val migrated = migrateHudProfile(legacy)
        assertEquals(4, migrated.layoutVersion)
        assertEquals(350f, migrated.elements.single().x, .001f)
        assertEquals(65f, migrated.elements.single().y, .001f)
        assertTrue(migrated.portraitElements.isNotEmpty())
    }

    @Test
    fun newProfileStartsAsEmptyLatestCanvas() {
        val profile = emptyHudProfile("new", "Custom")
        assertEquals(4, profile.layoutVersion)
        assertTrue(profile.elements.isEmpty())
        assertTrue(profile.portraitElements.isEmpty())
    }

    @Test
    fun mergesLegacyPhoneBatteryIntoBluetoothStatus() {
        val connection = defaultHudElement(HudWidgetType.CONNECTION, "connection")
        val battery = defaultHudElement(HudWidgetType.PHONE_BATTERY, "battery")
        val profile = HudProfile("legacy-status", "Legacy", 1f, 2, listOf(connection, battery))

        val migrated = migrateHudProfile(profile)

        assertEquals(4, migrated.layoutVersion)
        assertEquals(listOf("connection"), migrated.elements.map { it.id })
        assertEquals(HudWidgetType.CONNECTION, migrated.elements.single().type)
        assertTrue(migrated.portraitElements.isNotEmpty())
    }
}
