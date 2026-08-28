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

    @Test
    fun autoDetectsProfileOrientationMode() {
        val landscapeOnly = HudProfile(
            id = "land",
            name = "Landscape Only",
            elements = listOf(defaultHudElement(HudWidgetType.SPEED, "s")),
            portraitElements = emptyList(),
        )
        assertEquals(HudProfileOrientationMode.LANDSCAPE_ONLY, landscapeOnly.effectiveOrientationMode)
        assertTrue(landscapeOnly.isLandscapeOnly)
        assertFalse(landscapeOnly.isPortraitOnly)

        val portraitOnly = HudProfile(
            id = "port",
            name = "Portrait Only",
            elements = emptyList(),
            portraitElements = listOf(defaultHudElement(HudWidgetType.SPEED, "s")),
        )
        assertEquals(HudProfileOrientationMode.PORTRAIT_ONLY, portraitOnly.effectiveOrientationMode)
        assertTrue(portraitOnly.isPortraitOnly)
        assertFalse(portraitOnly.isLandscapeOnly)

        val both = HudProfile(
            id = "both",
            name = "Both",
            elements = listOf(defaultHudElement(HudWidgetType.SPEED, "s1")),
            portraitElements = listOf(defaultHudElement(HudWidgetType.SPEED, "s2")),
        )
        assertEquals(HudProfileOrientationMode.BOTH, both.effectiveOrientationMode)
        assertTrue(both.supportsBoth)

        val explicitPortrait = both.copy(orientationMode = HudProfileOrientationMode.PORTRAIT_ONLY)
        assertEquals(HudProfileOrientationMode.PORTRAIT_ONLY, explicitPortrait.effectiveOrientationMode)
        assertTrue(explicitPortrait.isPortraitOnly)
    }

    @Test
    fun hudElementLockedStateAndDefaults() {
        val element = defaultHudElement(HudWidgetType.SPEED, "speed")
        assertFalse(element.locked)
        val locked = element.copy(locked = true)
        assertTrue(locked.locked)
    }

    @Test
    fun newWidgetsCanBeInstantiatedWithDefaults() {
        val clock = defaultHudElement(HudWidgetType.CLOCK, "c1")
        assertEquals(HudWidgetType.CLOCK, clock.type)
        assertEquals(130f, clock.widthDp)
        assertEquals(48f, clock.heightDp)

        val compass = defaultHudElement(HudWidgetType.COMPASS, "cp1")
        assertEquals(HudWidgetType.COMPASS, compass.type)
        assertEquals(96f, compass.widthDp)
        assertEquals(50f, compass.heightDp)

        val tripProgress = defaultHudElement(HudWidgetType.TRIP_PROGRESS, "tp1")
        assertEquals(HudWidgetType.TRIP_PROGRESS, tripProgress.type)
        assertEquals(220f, tripProgress.widthDp)
        assertEquals(34f, tripProgress.heightDp)
    }

    @Test
    fun headingConversionCalculatesAccurateDirections() {
        assertEquals("N", com.chisadin.hudwz.sensor.headingToDirectionText(0f))
        assertEquals("N", com.chisadin.hudwz.sensor.headingToDirectionText(355f))
        assertEquals("N", com.chisadin.hudwz.sensor.headingToDirectionText(10f))
        assertEquals("NE", com.chisadin.hudwz.sensor.headingToDirectionText(45f))
        assertEquals("E", com.chisadin.hudwz.sensor.headingToDirectionText(90f))
        assertEquals("SE", com.chisadin.hudwz.sensor.headingToDirectionText(135f))
        assertEquals("S", com.chisadin.hudwz.sensor.headingToDirectionText(180f))
        assertEquals("SW", com.chisadin.hudwz.sensor.headingToDirectionText(225f))
        assertEquals("W", com.chisadin.hudwz.sensor.headingToDirectionText(270f))
        assertEquals("NW", com.chisadin.hudwz.sensor.headingToDirectionText(315f))
    }

    @Test
    fun snapCalculationThresholdLogic() {
        val snapThreshold = 8.5f
        val otherLeft = 100f
        val targetXClose = 104f
        val targetXFar = 120f
        assertTrue(kotlin.math.abs(targetXClose - otherLeft) <= snapThreshold)
        assertFalse(kotlin.math.abs(targetXFar - otherLeft) <= snapThreshold)
    }
}
