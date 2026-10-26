package com.flux.manga

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.flux.manga.ui.glass.LiquidMaterialShader
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Compile-only in this phase. Runtime AGSL compilation must run on an API 33+ device later. */
@RunWith(AndroidJUnit4::class)
class LiquidMaterialPlatformTest {
    @Test @SdkSuppress(minSdkVersion = 33)
    fun materialProgramCompilesAndBindsItsInputAndUniforms() {
        val shader = RuntimeShader(LiquidMaterialShader)
        shader.setFloatUniform("resolution", 384f, 152f)
        shader.setFloatUniform("circle", 18f, 82f, 70f, 134f)
        shader.setFloatUniform("pod", 82f, 82f, 366f, 134f)
        for ((key, value) in mapOf("mergeRadius" to 12f, "strokeWidth" to 1f, "edgeWidth" to 12f,
            "refractionAmount" to 2f, "highlight" to 1f, "sampled" to 1f)) shader.setFloatUniform(key, value)
        shader.setColorUniform("fillColor", android.graphics.Color.WHITE)
        shader.setColorUniform("tintColor", android.graphics.Color.TRANSPARENT)
        shader.setColorUniform("outlineColor", android.graphics.Color.GRAY)
        assertNotNull(RenderEffect.createRuntimeShaderEffect(shader, "content"))
    }
}
