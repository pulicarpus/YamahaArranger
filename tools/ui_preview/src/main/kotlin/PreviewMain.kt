import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourapp.preview.PrevKeyFace
import com.yourapp.preview.PrevLedButton
import com.yourapp.preview.PrevPanel
import com.yourapp.yamahaarranger.style.ParsedStyle
import com.yourapp.yamahaarranger.style.StyleSectionModel
import com.yourapp.yamahaarranger.ui.*
import java.io.File
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
private fun render(dir: File, name: String, widthDp: Int, heightDp: Int, density: Float, content: @Composable () -> Unit) {
    val scene = ImageComposeScene(width = (widthDp * density).toInt(), height = (heightDp * density).toInt(), density = Density(density), content = content)
    scene.render(0L)
    val image = scene.render(250_000_000L)
    File(dir, name).writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    scene.close()
}

private val AMBER = Color(0xFFFFB431); private val BLUE = Color(0xFF4DA3FF); private val WHITE = Color(0xFFF4F6F8)

@Composable private fun Caption(text: String) = Text(text, color = Color(0xFFB4BCC6), fontSize = 10.sp, fontWeight = FontWeight.Bold)

/** Side-by-side specimen: V3.2 as the user saw it (8b4526c) above, the corrected key below, every LED/press state. */
@Composable private fun Specimen() {
    Column(Modifier.fillMaxSize().background(Color(0xFF111214)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        data class K(val label: String, val sub: String?, val led: Color, val enabled: Boolean, val active: Boolean, val empty: Boolean, val pressed: Boolean, val big: Boolean = false)
        val keys = listOf(
            K("A", null, AMBER, true, true, false, false, true), K("B", null, BLUE, true, false, false, false, true), K("C", null, LedTone.Off, false, false, false, false),
            K("D", null, LedTone.Unknown, true, false, false, false), K("START", null, Color(0xFF27D887), true, false, false, false), K("B", null, BLUE, true, false, false, true),
            K("3", "EMPTY", LedTone.Off, true, false, true, false), K("2", "E+I Strings", AMBER, true, true, false, false))
        Caption("BEFORE - V3.2 8b4526c (what the Redmi Pad showed)")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            keys.forEach { k -> Box(Modifier.size(if (k.big) 84.dp else 72.dp, 66.dp)) {
                PrevKeyFace(Modifier.fillMaxSize(), k.pressed, k.enabled, k.led, k.big, k.active, k.empty) { Text(k.label, color = WHITE, fontSize = 22.sp, fontWeight = FontWeight.Bold); if (k.sub != null) Text(k.sub, color = Color(0xFFB4BCC6), fontSize = 10.sp) } } }
        }
        Caption("AFTER - corrected (amber active, blue available, gray absent disabled, neutral unknown, START green, pressed, empty, active REG)")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            keys.forEach { k -> Box(Modifier.size(if (k.big) 84.dp else 72.dp, 66.dp)) {
                HardwareKeyFace(Modifier.fillMaxSize(), k.pressed, k.enabled, k.led, k.big, k.active, k.empty) { Text(k.label, color = WHITE, fontSize = if (k.big) 28.sp else 22.sp, fontWeight = FontWeight.Bold); if (k.sub != null) Text(k.sub, color = Color(0xFFB4BCC6), fontSize = 10.sp) } } }
        }
    }
}

@Composable private fun LiveBody(old: Boolean = false) {
    val names = listOf("IntroA", "IntroB", "MainA", "MainB", "MainC", "FillAA", "FillBB", "FillCC", "EndingA", "EndingB")
    val inventory = LiveSectionInventory.from(ParsedStyle("dangdut.sty", 480, names.associateWith { StyleSectionModel(it, 1920, emptyList()) }))
    val slots = listOf(LiveRegSlotView(1, "E+I Distortion", RegKeyLook.FILLED, true), LiveRegSlotView(2, "E+I Strings", RegKeyLook.ACTIVE, true),
        LiveRegSlotView(3, "EMPTY", RegKeyLook.EMPTY, false), LiveRegSlotView(4, "I Brass", RegKeyLook.FILLED, true), LiveRegSlotView(5, "EMPTY", RegKeyLook.EMPTY, false),
        LiveRegSlotView(6, "EMPTY", RegKeyLook.EMPTY, false), LiveRegSlotView(7, "E Piano", RegKeyLook.FILLED, true), LiveRegSlotView(8, "EMPTY", RegKeyLook.EMPTY, false))
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF17191D), Color(0xFF0C0D0F)))).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HardwarePanel(Modifier.fillMaxWidth().height(64.dp)) {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("DANGDUT.STY", color = WHITE, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("MAIN B", color = AMBER, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("(header content is Android-only and not part of this preview)", color = Color(0xFF8B96A3), fontSize = 11.sp)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val areaWidthDp = maxWidth.value
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                        LiveStyleControlPanel(inventory, "Main B", areaWidthDp, {}, Modifier.fillMaxWidth())
                        LiveTransportRow(false, true, null, true, true, true, 64, {}, {}, {}, {})
                    }
                }
                LiveMixerPanel(100, 88, 64, 127, {}, {}, {}, {}, Modifier.width(236.dp).fillMaxHeight())
            }
        }
        LiveRegBankRow("REG BANK 1/3", "Dangdut Live", false, true, {}, {}, slots, {}, 160, Modifier.fillMaxWidth().height(80.dp))
    }
}

fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: "build/png").apply { mkdirs() }
    render(dir, "01_specimen_before_after.png", 760, 300, 2.5f) { Specimen() }
    render(dir, "02_live_body_1000x600.png", 1000, 600, 2f) { LiveBody() }
    render(dir, "03_live_body_1280x800.png", 1280, 800, 1.6f) { LiveBody() }
    println("PREVIEW OK " + dir.listFiles()!!.joinToString { it.name })
}
