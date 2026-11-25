package io.github.guilhermefeitosa66.openpdfviewer.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The critical journeys for the Baseline Profile: start to the home screen, then open a PDF
 * ("Open with") and scroll it.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = SampleProvider.TARGET, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        SampleProvider.openSample(device)
        val surface = device.findObject(By.res("android:id/content")) ?: return@collect
        repeat(3) {
            surface.fling(Direction.DOWN)
            device.waitForIdle()
        }
        surface.fling(Direction.UP)
        device.waitForIdle()
    }
}
