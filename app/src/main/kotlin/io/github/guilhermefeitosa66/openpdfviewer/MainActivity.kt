package io.github.guilhermefeitosa66.openpdfviewer

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.guilhermefeitosa66.openpdfviewer.data.DocumentSource
import io.github.guilhermefeitosa66.openpdfviewer.home.HomeScreen
import io.github.guilhermefeitosa66.openpdfviewer.settings.SettingsScreen
import io.github.guilhermefeitosa66.openpdfviewer.ui.LocalThemeIsDark
import io.github.guilhermefeitosa66.openpdfviewer.ui.OpenPdfTheme
import io.github.guilhermefeitosa66.openpdfviewer.ui.ThemeSettings
import io.github.guilhermefeitosa66.openpdfviewer.viewer.ViewerScreen
import io.github.guilhermefeitosa66.openpdfviewer.viewer.ViewerViewModel

/** Which document is open, if any. Survives process death through the saved state. */
class NavViewModel(private val saved: SavedStateHandle) : ViewModel() {
    var openUri by mutableStateOf(saved.get<Uri>(KEY))
        private set

    var settingsOpen by mutableStateOf(saved.get<Boolean>(KEY_SETTINGS) ?: false)
        private set

    fun show(uri: Uri?) {
        openUri = uri
        saved[KEY] = uri
        if (uri != null) showSettings(false)
    }

    fun showSettings(open: Boolean) {
        settingsOpen = open
        saved[KEY_SETTINGS] = open
    }

    private companion object {
        const val KEY = "open_uri"
        const val KEY_SETTINGS = "settings_open"
    }
}

class MainActivity : ComponentActivity() {
    private val nav: NavViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Paint the window in the chosen theme's color before the first frame.
        ThemeSettings.get(this).current?.let {
            window.setBackgroundDrawable(ColorDrawable(it.ui.bg.toArgb()))
        }
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            OpenPdfTheme {
                val dark = LocalThemeIsDark.current
                LaunchedEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    Log.d(TAG, "picker result: ${uri != null}")
                    if (uri != null) {
                        DocumentSource.persistAccess(this, uri)
                        nav.show(uri)
                    }
                }
                val pick = { picker.launch(arrayOf("application/pdf")) }
                val uri = nav.openUri
                if (uri == null && nav.settingsOpen) {
                    SettingsScreen(onBack = { nav.showSettings(false) })
                } else if (uri == null) {
                    HomeScreen(onOpen = nav::show, onPickFile = pick, onSettings = { nav.showSettings(true) })
                } else {
                    val vm: ViewerViewModel = viewModel()
                    ViewerScreen(vm = vm, uri = uri, onBack = { nav.show(null) }, onPickFile = pick, onOpenOther = nav::show)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        Log.d(TAG, "intent ${intent?.action}: ${uri != null}")
        if (uri != null) {
            DocumentSource.persistAccess(this, uri)
            nav.show(uri)
        }
    }

    private companion object {
        const val TAG = "OpenPdfViewer"
    }
}
