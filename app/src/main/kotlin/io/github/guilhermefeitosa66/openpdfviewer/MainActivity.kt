package io.github.guilhermefeitosa66.openpdfviewer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.guilhermefeitosa66.openpdfviewer.data.DocumentSource
import io.github.guilhermefeitosa66.openpdfviewer.home.HomeScreen
import io.github.guilhermefeitosa66.openpdfviewer.ui.OpenPdfTheme
import io.github.guilhermefeitosa66.openpdfviewer.viewer.ViewerScreen
import io.github.guilhermefeitosa66.openpdfviewer.viewer.ViewerViewModel

/** Which document is open, if any. Survives process death through the saved state. */
class NavViewModel(private val saved: SavedStateHandle) : ViewModel() {
    var openUri by mutableStateOf(saved.get<Uri>(KEY))
        private set

    fun show(uri: Uri?) {
        openUri = uri
        saved[KEY] = uri
    }

    private companion object {
        const val KEY = "open_uri"
    }
}

class MainActivity : ComponentActivity() {
    private val nav: NavViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            OpenPdfTheme {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        DocumentSource.persistAccess(this, uri)
                        nav.show(uri)
                    }
                }
                val pick = { picker.launch(arrayOf("application/pdf")) }
                val uri = nav.openUri
                if (uri == null) {
                    HomeScreen(onOpen = nav::show, onPickFile = pick)
                } else {
                    val vm: ViewerViewModel = viewModel()
                    ViewerScreen(vm = vm, uri = uri, onBack = { nav.show(null) }, onPickFile = pick)
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
        if (uri != null) {
            DocumentSource.persistAccess(this, uri)
            nav.show(uri)
        }
    }
}
