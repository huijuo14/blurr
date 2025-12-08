package com.blurr.voice

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricRoborazziRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsActivityTest {

    @get:Rule
    val roborazziRule = RobolectricRoborazziRule(
        captureRoot = RobolectricRoborazziRule.CaptureRoot.FAIL,
        options = RobolectricRoborazziRule.Options(
            outputDirectoryPath = "screenshots"
        )
    )

    @Test
    fun testSettingsActivityScreenshot() {
        ActivityScenario.launch(SettingsActivity::class.java)
    }
}
