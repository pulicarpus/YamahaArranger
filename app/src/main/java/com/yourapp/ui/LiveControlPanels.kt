package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Palette shared by the LIVE screen and the panels below. */
object LiveColors {
    val Blue = Color(0xFF4DA3FF)
    val Cyan = Color(0xFF45D9DB)
    val Orange = Color(0xFFFFAA48)
    val Green = Color(0xFF27D887)
    val Amber = Color(0xFFFFB431)
    val Red = Color(0xFFFF6A6A)
    val Dim = Color(0xFF8B96A3)
    val Label = Color(0xFFF4F6F8)
}

fun sectionLedColor(led: SectionLed): Color = when (led) {
    SectionLed.AVAILABLE -> LiveColors.Blue
    SectionLed.ACTIVE -> LiveColors.Amber
    SectionLed.UNKNOWN -> LedTone.Unknown
    SectionLed.ABSENT -> LedTone.Off
}

/**
 * STYLE CONTROL: INTRO / MAIN / FILL / ENDING groups. Keys have physical-button proportions (fixed height, width by weight), MAIN A-D
 * a little larger. LED per key comes from [liveSectionLed] over the parsed section table; absent keys are disabled.
 */
@Composable
fun LiveStyleControlPanel(inventory: LiveSectionInventory, activeSection: String, areaWidthDp: Float, onSection: (String) -> Unit, modifier: Modifier = Modifier) {
    val key: @Composable (LiveSectionButton, Boolean, Modifier) -> Unit = { b, big, mod ->
        val led = liveSectionLed(inventory, b, activeSection)
        HardwareLedButton(b.caption, null, sectionLedColor(led),
            if (led == SectionLed.ACTIVE) LiveColors.Amber else if (led == SectionLed.ABSENT) Color(0xFF59616B) else LiveColors.Label,
            liveSectionEnabled(led), mod, big = big, active = led == SectionLed.ACTIVE) { onSection(b.label) }
    }
    val oneRow = LiveStyleLayout.oneRow(areaWidthDp)
    if (oneRow) Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LiveStyleGroup("INTRO", LiveSectionGrid.intro, false, key, Modifier.weight(3f))
        LiveStyleGroup("MAIN", LiveSectionGrid.main, true, key, Modifier.weight(4f * LiveStyleLayout.MAIN_WEIGHT))
        LiveStyleGroup("FILL", LiveSectionGrid.fill, false, key, Modifier.weight(4f))
        LiveStyleGroup("ENDING", LiveSectionGrid.ending, false, key, Modifier.weight(3f))
    } else Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveStyleGroup("INTRO", LiveSectionGrid.intro, false, key, Modifier.weight(3f))
            LiveStyleGroup("MAIN", LiveSectionGrid.main, true, key, Modifier.weight(4f * LiveStyleLayout.MAIN_WEIGHT))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveStyleGroup("FILL", LiveSectionGrid.fill, false, key, Modifier.weight(4f))
            LiveStyleGroup("ENDING", LiveSectionGrid.ending, false, key, Modifier.weight(3f))
        }
    }
}

@Composable
private fun LiveStyleGroup(title: String, buttons: List<LiveSectionButton>, big: Boolean,
    key: @Composable (LiveSectionButton, Boolean, Modifier) -> Unit, modifier: Modifier) {
    HardwarePanel(modifier) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = LiveColors.Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val h = if (big) HardwareKeyStyle.MAIN_KEY_H_DP else HardwareKeyStyle.KEY_H_DP
                buttons.forEach { b -> key(b, big, Modifier.weight(if (big) LiveStyleLayout.MAIN_WEIGHT else 1f).height(h.dp)) }
            }
        }
    }
}

/** START/STOP, ACMP, AUTO FILL, RELEASE. Callbacks are the existing ViewModel actions. */
@Composable
fun LiveTransportRow(isPlaying: Boolean, startEnabled: Boolean, startReason: String?, acmpOn: Boolean, autoFillOn: Boolean,
    releaseOn: Boolean, releaseValue: Int, onStartStop: () -> Unit, onAcmp: () -> Unit, onAutoFill: () -> Unit, onRelease: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(HardwareKeyStyle.TRANSPORT_KEY_H_DP.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HardwareLedButton(if (isPlaying) "STOP" else "START", if (!startEnabled && !isPlaying) startReason else null,
            if (isPlaying) LiveColors.Red else LiveColors.Green, LiveColors.Label, isPlaying || startEnabled, Modifier.weight(2f).fillMaxHeight(), active = isPlaying, onClick = onStartStop)
        HardwareLedButton("ACMP", if (acmpOn) "ON" else "OFF", if (acmpOn) LiveColors.Green else LedTone.Off, LiveColors.Label, true, Modifier.weight(1f).fillMaxHeight(), onClick = onAcmp)
        HardwareLedButton("AUTO FILL", if (autoFillOn) "ON" else "OFF", if (autoFillOn) LiveColors.Amber else LedTone.Off, LiveColors.Label, true, Modifier.weight(1f).fillMaxHeight(), onClick = onAutoFill)
        HardwareLedButton("RELEASE", "${if (releaseOn) "ON" else "OFF"} $releaseValue", if (releaseOn) LiveColors.Blue else LedTone.Off, LiveColors.Label, true, Modifier.weight(1f).fillMaxHeight(), onClick = onRelease)
    }
}

/** LIVE MIXER: exactly four independent faders, 0..127, each bound to its own volume action. */
@Composable
fun LiveMixerPanel(right1: Int, right2: Int, right3: Int, style: Int,
    onRight1: (Int) -> Unit, onRight2: (Int) -> Unit, onRight3: (Int) -> Unit, onStyle: (Int) -> Unit, modifier: Modifier = Modifier) {
    HardwarePanel(modifier) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Text("LIVE MIXER", color = LiveColors.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LiveFader("RIGHT1", right1, LiveColors.Blue, onRight1, Modifier.weight(1f).fillMaxHeight())
                LiveFader("RIGHT2", right2, LiveColors.Cyan, onRight2, Modifier.weight(1f).fillMaxHeight())
                LiveFader("RIGHT3", right3, LiveColors.Green, onRight3, Modifier.weight(1f).fillMaxHeight())
                LiveFader("STYLE", style, LiveColors.Orange, onStyle, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** One registration slot as drawn: number, short info line, look and whether it can be pressed. */
data class LiveRegSlotView(val number: Int, val info: String, val look: RegKeyLook, val enabled: Boolean)

/** REG BANK: bank number/name, previous/next, exactly eight slot keys. */
@Composable
fun LiveRegBankRow(bankTitle: String, bankName: String, canPrevious: Boolean, canNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit,
    slots: List<LiveRegSlotView>, onSlot: (Int) -> Unit, bankWidthDp: Int, modifier: Modifier = Modifier) {
    require(slots.size == 8)
    HardwarePanel(modifier) {
        Row(Modifier.fillMaxSize().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Column(Modifier.width(bankWidthDp.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(bankTitle, color = LiveColors.Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
                Text(bankName, color = LiveColors.Label, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HardwareLedButton("◀", null, LedTone.Off, if (canPrevious) LiveColors.Blue else LiveColors.Dim, canPrevious, Modifier.weight(1f).fillMaxHeight(), fontSp = 18, lamp = false, onClick = onPrevious)
                    HardwareLedButton("▶", null, LedTone.Off, if (canNext) LiveColors.Blue else LiveColors.Dim, canNext, Modifier.weight(1f).fillMaxHeight(), fontSp = 18, lamp = false, onClick = onNext)
                }
            }
            slots.forEachIndexed { index, s ->
                HardwareLedButton("${s.number}", s.info,
                    when (s.look) { RegKeyLook.ACTIVE -> LiveColors.Amber; RegKeyLook.FILLED -> LiveColors.Cyan; RegKeyLook.EMPTY -> LedTone.Off; RegKeyLook.SAVE_TARGET -> LiveColors.Red },
                    if (s.look == RegKeyLook.EMPTY) LiveColors.Dim else LiveColors.Label, s.enabled, Modifier.weight(1f).fillMaxHeight(),
                    active = s.look == RegKeyLook.ACTIVE, empty = s.look == RegKeyLook.EMPTY) { onSlot(index) }
            }
        }
    }
}
