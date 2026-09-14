package com.yourapp.yamahaarranger.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // Phase 1: just log; Phase 4 should show a proper rationale screen.
        }
    }

    // "Import style dari storage" (spec section 7) via the Storage Access
    // Framework — works with any provider (local files, SD card, cloud
    // docs providers) without needing broad storage permissions.
    private val pickStyleFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::onStyleFilePicked) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            YamahaArrangerTheme {
                MainScreen(
                    viewModel = viewModel,
                    onImportStyleClicked = {
                        // .sty files have no standard MIME type, so accept
                        // anything and let StyleRepository's SMF-header
                        // check reject non-style files.
                        pickStyleFile.launch(arrayOf("*/*"))
                    }
                )
            }
        }
    }
}
