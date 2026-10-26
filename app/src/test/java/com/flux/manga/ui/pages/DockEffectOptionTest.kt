package com.flux.manga.ui.pages

import com.flux.manga.ui.glass.DockEffects
import org.junit.Assert.*
import org.junit.Test

class DockEffectOptionTest {
    @Test fun lowEffectsSuspendsRenderingWithoutOverwritingOtherSelections() {
        val selected = DockEffects(sampling = true, blur = true, tint = false, reduceMotion = true)
        val low = DockEffectOption.LOW.changed(selected, true)
        assertEquals(selected.copy(lowEffects = true), low)
        assertFalse(low.backdropEnabled)
        assertFalse(low.blurEnabled)
        assertFalse(DockEffectOption.BLUR.enabled(low))
        assertFalse(DockEffectOption.SAMPLING.enabled(low))
        assertEquals(low, DockEffectOption.BLUR.changed(low, false))
        assertEquals(selected, DockEffectOption.LOW.changed(low, false))
    }
    @Test fun motionAndTintRemainIndependentWhileLowEffectsIsOn() {
        val start = DockEffects(lowEffects = true)
        assertTrue(DockEffectOption.MOTION.enabled(start))
        val reduced = DockEffectOption.MOTION.changed(start, true)
        assertEquals(start.copy(reduceMotion = true), reduced)
        assertEquals(reduced.copy(tint = false), DockEffectOption.TINT.changed(reduced, false))
        assertEquals(start, DockEffectOption.MOTION.changed(reduced, false))
    }
    @Test fun blurRequiresSamplingAndDefaultsRemainUnchanged() {
        val defaults = DockEffects()
        assertFalse(defaults.sampling); assertFalse(defaults.blur); assertTrue(defaults.tint)
        val blur = DockEffectOption.BLUR.changed(defaults, true)
        assertTrue(blur.blur); assertFalse(blur.blurEnabled)
        val sampled = DockEffectOption.SAMPLING.changed(blur, true)
        assertTrue(sampled.blurEnabled)
        assertEquals(blur, DockEffectOption.SAMPLING.changed(sampled, false))
    }
}
