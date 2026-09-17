package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourapp.yamahaarranger.chord.ChordMode
import com.yourapp.yamahaarranger.chord.ChordModeController

/** Compact live control for the three chord modes used by the E343 workflow. */
@Composable
fun ChordModeSelector(modifier: Modifier = Modifier) {
    val mode by ChordModeController.mode.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            color = Color(0xFF172029),
            shape = RoundedCornerShape(4.dp),
            onClick = { expanded = !expanded }
        ) {
            Text(
                text = "CHORD ${mode.shortLabel()}",
                color = Color.White,
                fontSize = 8.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF151A1F))
        ) {
            listOf(
                ChordMode.SingleFinger to "Single Finger",
                ChordMode.MultiFinger to "Multi Finger",
                ChordMode.AiFingered to "AI Fingered"
            ).forEach { (candidate, label) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(label, color = Color.White, fontSize = 13.sp)
                            if (candidate == ChordMode.MultiFinger) {
                                Text("E343 default", color = Color(0xFF9AA3AD), fontSize = 9.sp)
                            }
                        }
                    },
                    onClick = {
                        ChordModeController.setMode(candidate)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun ChordMode.shortLabel(): String = when (this) {
    ChordMode.SingleFinger -> "SINGLE"
    ChordMode.MultiFinger -> "MULTI"
    ChordMode.Fingered -> "FINGERED"
    ChordMode.AiFingered -> "AI"
}
