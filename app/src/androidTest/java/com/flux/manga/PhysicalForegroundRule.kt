package com.flux.manga

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Physical-device data tests keep an Activity visible, without changing the device's
 * background restrictions. Existing emulator runs keep their original lifecycle. */
class PhysicalForegroundRule : ExternalResource() {
    private var scenario: ActivityScenario<MainActivity>? = null
    override fun before() {
        if (InstrumentationRegistry.getArguments().getString("physicalForeground") == "true") {
            scenario = ActivityScenario.launch(MainActivity::class.java)
        }
    }
    override fun after() { scenario?.close(); scenario = null }
}
