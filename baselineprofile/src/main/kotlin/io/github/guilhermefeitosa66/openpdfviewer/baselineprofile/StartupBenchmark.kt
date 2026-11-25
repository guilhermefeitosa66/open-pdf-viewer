package io.github.guilhermefeitosa66.openpdfviewer.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start to the home screen, and cold "Open with" to the first page on screen (time to full
 * display: the app reports it when the first page is drawn), without and with the Baseline
 * Profile.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test fun startupNoProfile() = startup(CompilationMode.None())
    @Test fun startupBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))
    @Test fun openPdfNoProfile() = openPdf(CompilationMode.None())
    @Test fun openPdfBaselineProfile() = openPdf(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = SampleProvider.TARGET,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    private fun openPdf(mode: CompilationMode) = rule.measureRepeated(
        packageName = SampleProvider.TARGET,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        SampleProvider.openSample(device)
    }
}
