package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

private val WHITE_KEY_SEMITONES = listOf(0, 2, 4, 5, 7, 9, 11)
private val BLACK_KEY_SEMITONES = listOf(1, 3, 6, 8, 10)

@Composable
fun PianoKeyboard(
    startNote: Int = 48,
    numOctaves: Int = 3,
    onNoteOn: (midiNote: Int, velocity: Float) -> Unit,
    onNoteOff: (midiNote: Int) -> Unit
) {
    val totalWhiteKeys = numOctaves * 7

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        val note = noteForOffset(
                            offset = offset,
                            canvasSize = Size(size.width.toFloat(), size.height.toFloat()),
                            startNote = startNote,
                            totalWhiteKeys = totalWhiteKeys
                        )
                        onNoteOn(note, 0.9f)
                        tryAwaitRelease()
                        onNoteOff(note)
                    }
                )
            }
    ) {
        val whiteKeyWidth = this.size.width / totalWhiteKeys
        val whiteKeyHeight = this.size.height
        val blackKeyWidth = whiteKeyWidth * 0.6f
        val blackKeyHeight = whiteKeyHeight * 0.6f

        for (i in 0 until totalWhiteKeys) {
            drawRect(
                color = Color(0xFFF5F5F5),
                topLeft = Offset(i * whiteKeyWidth, 0f),
                size = Size(whiteKeyWidth - 1f, whiteKeyHeight)
            )
        }

        var whiteIndex = 0
        for (octave in 0 until numOctaves) {
            for (semitone in 0..11) {
                if (semitone in WHITE_KEY_SEMITONES) {
                    whiteIndex++
                } else if (semitone in BLACK_KEY_SEMITONES) {
                    val x = whiteIndex * whiteKeyWidth - blackKeyWidth / 2f
                    drawRect(
                        color = Color(0xFF181818),
                        topLeft = Offset(x, 0f),
                        size = Size(blackKeyWidth, blackKeyHeight)
                    )
                }
            }
        }
    }
}

private fun noteForOffset(
    offset: Offset,
    canvasSize: Size,
    startNote: Int,
    totalWhiteKeys: Int
): Int {
    val whiteKeyWidth = canvasSize.width / totalWhiteKeys
    val blackKeyWidth = whiteKeyWidth * 0.6f
    val blackKeyHeight = canvasSize.height * 0.6f

    val whiteIndex = (offset.x / whiteKeyWidth).toInt().coerceIn(0, totalWhiteKeys - 1)

    if (offset.y < blackKeyHeight) {
        val nearbyBlackX = whiteIndex * whiteKeyWidth
        val distToBoundary = offset.x - nearbyBlackX
        if (kotlin.math.abs(distToBoundary) < blackKeyWidth / 2f) {
            val octave = whiteIndex / 7
            val whiteInOctave = whiteIndex % 7
            val blackSemitoneMap = mapOf(0 to 1, 1 to 3, 3 to 6, 4 to 8, 5 to 10)
            blackSemitoneMap[whiteInOctave]?.let { semitone ->
                return startNote + octave * 12 + semitone
            }
        }
    }

    val octave = whiteIndex / 7
    val whiteInOctave = whiteIndex % 7
    return startNote + octave * 12 + WHITE_KEY_SEMITONES[whiteInOctave]
}