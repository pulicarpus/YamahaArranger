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
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
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

    // On-screen keyboard safety: any pause / focus loss releases every virtual note and the virtual sustain.
    override fun onPause() {
        viewModel.closeVirtualKeyboard()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) viewModel.closeVirtualKeyboard()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) { // fresh process/launch: diagnostic test mode never survives a restart
            com.yourapp.yamahaarranger.audio.RightVelocityTest.reset()
            com.yourapp.yamahaarranger.audio.RightVelocityMonitor.shared.reset()
        }
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
                val showInspector = remember { mutableStateOf(false) }
                val overlayPageVisible = remember { mutableStateOf(false) }
                val keyboardOpen = remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize()) {
                    SxMainScreen(
                        viewModel = viewModel,
                        onOpenInspector = { showInspector.value = true },
                        onMixerVisibilityChanged = { overlayPageVisible.value = it }
                    )
                    if (keyboardOpen.value) VirtualKeyboardPanel(
                        viewModel = viewModel,
                        onClose = { keyboardOpen.value = false },
                        modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                    ) else androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier
                            .align(androidx.compose.ui.Alignment.BottomEnd)
                            .padding(end = 6.dp, bottom = 6.dp)
                            .background(androidx.compose.ui.graphics.Color(0xDD1E5AA8), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .clickable { keyboardOpen.value = true }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        androidx.compose.material3.Text("🎹 KEYBOARD", color = androidx.compose.ui.graphics.Color.White,
                            fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                    RightVoiceDiagOverlay(
                        viewModel = viewModel,
                        modifier = androidx.compose.ui.Modifier
                            .align(androidx.compose.ui.Alignment.BottomStart)
                            .padding(start = 6.dp, bottom = 6.dp + if (keyboardOpen.value) VirtualKeyboardPanelHeight else 0.dp)
                    )
                    if (!overlayPageVisible.value) ChordModeSelector(
                        modifier = androidx.compose.ui.Modifier
                            .align(androidx.compose.ui.Alignment.TopCenter)
                            .padding(top = 7.dp)
                    )
                }
                if (showInspector.value) {
                    Sf2StyleInspectorDialog(
                        onDismiss = { showInspector.value = false },
                        viewModel = viewModel
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
