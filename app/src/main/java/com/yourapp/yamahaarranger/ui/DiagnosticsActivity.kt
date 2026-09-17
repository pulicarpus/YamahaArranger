package com.yourapp.yamahaarranger.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContent {
            YamahaArrangerTheme {
                var logs by mutableStateOf(DebugLog.getAll())
                LaunchedEffect(Unit) {
                    while (true) {
                        logs = DebugLog.getAll()
                        delay(300)
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF111214))
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("ENGINE DIAGNOSTICS", color = Color.White, fontSize = 16.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { DebugLog.clear(); logs = emptyList() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3D42))
                            ) { Text("CLEAR") }
                            Button(
                                onClick = { shareLog(logs) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D72B8))
                            ) { Text("EXPORT LOG") }
                            Button(onClick = { finish() }) { Text("CLOSE") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF090A0B))
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (logs.isEmpty()) {
                            Text("(no diagnostic data yet)", color = Color(0xFF9DA3AA), fontSize = 11.sp)
                        } else {
                            logs.forEach { line ->
                                Text(
                                    line,
                                    color = Color(0xFFD0D5DA),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun shareLog(lines: List<String>) {
        val text = buildString {
            appendLine("YamahaArranger Engine Diagnostic Log")
            appendLine("-----------------------------------")
            lines.forEach(::appendLine)
        }
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "YamahaArranger diagnostic log")
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                "Export diagnostic log"
            )
        )
    }
}
