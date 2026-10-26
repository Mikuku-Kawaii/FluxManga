package com.flux.manga

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LocalContentGestureTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun systemEdgeBack_readerToDetailToHome() {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val id=ContentFixtures.readerId()
        kotlinx.coroutines.runBlocking { ContentFixtures.library.saveProgress(com.flux.manga.data.MangaKey(com.flux.manga.ui.navigation.PrototypeWorld.SFW,"cbz",id),"page1.png",0f,com.flux.manga.data.ReadingMode.LTR) }
        compose.runOnUiThread { compose.activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10000) { compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT }
        assertEquals(2,android.provider.Settings.Secure.getInt(compose.activity.contentResolver,"navigation_mode"))
        compose.awaitTag("shelf-more")
        compose.runOnIdle { compose.activity.navigation.openLocalBook(id) }
        compose.awaitTag("local-read")
        compose.onNodeWithTag("local-read").performScrollTo().performClick()
        compose.awaitTag("reader-pager")
        compose.onNodeWithTag("main-tabs").assertDoesNotExist()
        fun back() {
            val metrics=compose.activity.resources.displayMetrics
            val down=SystemClock.uptimeMillis()
            for(step in 0..20) {
                val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(), when(step){0->MotionEvent.ACTION_DOWN;20->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE},
                    2f+metrics.widthPixels*.4f*step/20,metrics.heightPixels*.5f,0)
                event.source=InputDevice.SOURCE_TOUCHSCREEN
                automation.injectInputEvent(event,true); event.recycle(); SystemClock.sleep(16)
            }
        }
        back(); compose.awaitTag("local-read")
        back(); compose.awaitTag("shelf-more")
        compose.onNodeWithTag("main-tabs").assertExists()
        ContentFixtures.verifyOriginals()
    }
}


