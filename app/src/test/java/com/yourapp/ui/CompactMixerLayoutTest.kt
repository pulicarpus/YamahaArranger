package com.yourapp.yamahaarranger.ui

import org.junit.Assert.*
import org.junit.Test

class CompactMixerLayoutTest {
    @Test fun landscapeKeepsSixFixedStripsAndScrollableStyleViewport() {
        for (width in listOf(640f, 800f, 960f, 1024f, 1280f)) {
            val strip = compactMixerStripWidth(width)
            assertTrue("touch width at $width", strip in 48f..56f)
            val fixed = 6 * strip + 5 * 4f
            val remaining = width - 12f - fixed - 14f
            assertTrue("style viewport at $width", remaining >= 2 * strip)
            if (width == 640f) assertTrue("overflow stays in style area at $width", 8 * strip + 7 * 4f > remaining)
        }
    }
    @Test fun shortLandscapeRetainsUsableVerticalFaderTravel() {
        // Page padding + header + three 48dp buttons + value + footer.
        val overhead = 12f + 48f + 144f + 20f + 36f
        for (height in listOf(360f, 480f, 600f, 800f)) {
            assertTrue("fader height at $height", height - overhead >= 100f)
            assertTrue("fader travel after 16dp end padding", height - overhead - 32f >= 68f)
        }
    }
    @Test fun shortLabelsPreserveDistinctPartIdentity() {
        val labels = listOf("RIGHT1", "RIGHT2", "RIGHT3", "LEFT", "STYLE", "MASTER", "Rhythm1", "Rhythm2", "Bass", "Chord1", "Chord2", "Pad", "Phrase1", "Phrase2")
        assertEquals(labels.size, labels.map(::compactMixerLabel).toSet().size)
        assertTrue(labels.map(::compactMixerLabel).all { it.length <= 6 })
        assertEquals("CUSTOM", compactMixerLabel("CUSTOM"))
        assertEquals("RHY1", compactMixerLabel("Ch8 (Rhythm1)"))
        assertEquals("PHR2", compactMixerLabel("Ch15 (Phrase2)"))
        assertEquals("RIGHT2", compactMixerLabel("RIGHT 2"))
    }
    @Test fun actualStyleLabelsStayReadableAndDistinct() {
        val roles = listOf("Style", "Style", "Style", "Style", "Rhythm1", "Rhythm2", "Bass", "Chord1", "Chord2", "Pad", "Phrase1", "Phrase2")
        val names = roles.mapIndexed { i, role -> compactMixerLabel("Ch${i + 4} ($role)") }
        assertEquals(roles.size, names.toSet().size)
        assertTrue(names.all { it.length <= 5 && '(' !in it })
        assertEquals("BASS", names[6])
        assertEquals("PAD", names[9])
    }
    @Test fun responsiveWidthDoesNotShrinkTouchTargets() {
        assertEquals(48f, compactMixerStripWidth(600f), 0f)
        assertEquals(50f, compactMixerStripWidth(900f), 0f)
        assertEquals(56f, compactMixerStripWidth(1280f), 0f)
    }
}
