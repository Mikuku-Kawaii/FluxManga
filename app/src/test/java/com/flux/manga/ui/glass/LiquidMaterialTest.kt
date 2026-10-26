package com.flux.manga.ui.glass

import com.flux.manga.ui.pages.DockEffectOption
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LiquidMaterialTest {
    private val enhanced = DockEffects(sampling = true, blur = true, fusion = true,
        highlight = true, refraction = true, saturation = true)

    @Test fun legacyDefaultsHaveNoEnhancementOrBackdrop() {
        val e = DockEffects()
        assertFalse(e.backdropEnabled); assertFalse(e.blurEnabled); assertTrue(e.tint)
        for (api in listOf(31, 32, 33, 37)) {
            assertFalse(e.unifiedMaterial(api)); assertFalse(e.highlightEnabled(api)); assertFalse(e.refractionEnabled(api))
        }
        assertFalse(e.saturationEnabled)
    }
    @Test fun lowEffectsSuspendsAllEnhancementsAndRestoresExactSelection() {
        val low = DockEffectOption.LOW.changed(enhanced, true)
        assertFalse(low.fusionEnabled); assertFalse(low.highlightEnabled(33)); assertFalse(low.refractionEnabled(33))
        assertFalse(low.saturationEnabled); assertFalse(low.unifiedMaterial(31))
        for (option in listOf(DockEffectOption.FUSION, DockEffectOption.HIGHLIGHT, DockEffectOption.REFRACTION, DockEffectOption.SATURATION)) {
            assertFalse(option.enabled(low)); assertEquals(low, option.changed(low, false))
        }
        assertEquals(enhanced, DockEffectOption.LOW.changed(low, false))
    }
    @Test fun oldApisOfferFusionAndSaturationButCannotEnableAgslOptions() {
        for (api in listOf(31, 32)) {
            assertTrue(enhanced.fusionEnabled); assertTrue(enhanced.saturationEnabled)
            assertFalse(enhanced.highlightEnabled(api)); assertFalse(enhanced.refractionEnabled(api))
            assertTrue(DockEffectOption.FUSION.enabled(enhanced, api))
            for (option in listOf(DockEffectOption.HIGHLIGHT, DockEffectOption.REFRACTION)) {
                assertFalse(option.enabled(enhanced, api))
                assertEquals(enhanced, option.changed(enhanced, false, api))
                assertTrue(option.description(enhanced, api).contains("Android 13"))
            }
        }
        assertTrue(enhanced.highlightEnabled(33)); assertTrue(enhanced.refractionEnabled(33))
    }
    @Test fun samplingGateDoesNotClearStoredChoicesOrRequireBlur() {
        val e = enhanced.copy(blur = false)
        assertTrue(e.saturationEnabled); assertTrue(e.refractionEnabled(33)); assertFalse(e.blurEnabled)
        val noSample = DockEffectOption.SAMPLING.changed(e, false)
        assertFalse(noSample.saturationEnabled); assertFalse(noSample.refractionEnabled(33))
        assertTrue(noSample.saturation); assertTrue(noSample.refraction)
        assertTrue(noSample.fusionEnabled); assertTrue(noSample.highlightEnabled(33))
        assertEquals(e, DockEffectOption.SAMPLING.changed(noSample, true))
    }
    @Test fun everyNewControlChangesExactlyOneField() {
        val e = DockEffects()
        assertEquals(e.copy(fusion = true), DockEffectOption.FUSION.changed(e, true))
        assertEquals(e.copy(highlight = true), DockEffectOption.HIGHLIGHT.changed(e, true))
        assertEquals(e.copy(refraction = true), DockEffectOption.REFRACTION.changed(e, true))
        assertEquals(e.copy(saturation = true), DockEffectOption.SATURATION.changed(e, true))
        assertEquals(enhanced.copy(reduceMotion = true), DockEffectOption.MOTION.changed(enhanced, true))
    }
    private fun frame(p: Float, h: Float = 52f, speed: Float = 0f) =
        DockMaterialFrame.create(348f, h, 52f, 12f, p, p, speed, speed)

    @Test fun stablePositionsMatchExistingDockAndLeaveClearGap() {
        val left = frame(0f); val right = frame(1f)
        assertEquals(MaterialBounds(0f, 0f, 52f, 52f), left.circle)
        assertEquals(MaterialBounds(64f, 0f, 348f, 52f), left.pod)
        assertEquals(MaterialBounds(296f, 0f, 348f, 52f), right.circle)
        assertEquals(MaterialBounds(0f, 0f, 284f, 52f), right.pod)
        for (y in 0..52) {
            assertTrue(left.distance(58f, y.toFloat(), 12f) > 0f)
            assertTrue(right.distance(290f, y.toFloat(), 12f) > 0f)
        }
    }
    @Test fun fusionConnectsCloseShapesButNotDistantOnes() {
        val a = MaterialBounds(0f, 0f, 52f, 52f)
        val near = DockMaterialFrame(a, MaterialBounds(54f, 0f, 106f, 52f))
        assertTrue(near.distance(53f, 26f, 0f) > 0f)
        assertTrue(near.distance(53f, 26f, 12f) < 0f)
        val far = DockMaterialFrame(a, MaterialBounds(80f, 0f, 132f, 52f))
        assertTrue(far.distance(66f, 26f, 12f) > 0f)
    }
    @Test fun allAnimationAndLargeTextBoundsFitExpandedMaterialRegion() {
        for (height in listOf(52f, 100f, 148f)) for (i in 0..100) for (speed in listOf(0f, 1f)) {
            val f = frame(i / 100f, height, speed)
            for (b in listOf(f.circle, f.pod)) {
                assertTrue(b.left >= -GlassTokens.MaterialPaddingDp)
                assertTrue(b.right <= 348f + GlassTokens.MaterialPaddingDp)
                assertTrue(b.top >= -64f - GlassTokens.MaterialPaddingDp)
                assertTrue(b.bottom <= height + GlassTokens.MaterialPaddingDp)
                assertTrue(b.radius > 0)
                assertTrue(b.distance(b.cx, b.cy) < 0)
            }
        }
    }
    @Test fun oppositeDirectionsAndOutOfRangeProgressResolveToSameEndpoints() {
        assertEquals(frame(0f), frame(-.2f)); assertEquals(frame(1f), frame(1.2f))
        val f = frame(.5f)
        assertEquals(-64f, f.circle.top, .001f)
        assertTrue(abs(f.pod.cx - 174f) < .01f)
        // A committed/rolled back motion reads the stable endpoint, so no bridge state is saved.
        assertEquals(frame(0f), DockMaterialFrame.create(348f, 52f, 52f, 12f, 0f, 0f, 0f, 0f))
    }
}
