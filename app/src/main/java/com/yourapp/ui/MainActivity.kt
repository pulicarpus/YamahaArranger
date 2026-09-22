package com.yourapp.yamahaarranger.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (!granted) Timber.w("Audio permission denied") }

    private val requestAllFilesAccess = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            Timber.i("YamahaArranger storage access granted")
        } else {
            Timber.w("YamahaArranger storage access not granted")
        }
    }
    private val pickStyleFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::onStyleFilePicked) }

    private val pickSoundFont = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::onSoundFontFilePicked) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Timber.i("Opening Android All files access for /storage/emulated/0/YamahaArranger")
            requestAllFilesAccess.launch(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:" + packageName)
                }
            )
        }

        setContent {
            YamahaArrangerTheme {
                Box(Modifier.fillMaxSize()) {
                    SxMainScreen(viewModel = viewModel)
                    ChordModeSelector(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 7.dp)
                    )
                }
            }
        }

        lifecycleScope.launch {
            delay(1500L)
            Timber.i("MIDI auto-connect…")
            viewModel.connectFirstAvailableMidiDevice()
        }
    }
}
