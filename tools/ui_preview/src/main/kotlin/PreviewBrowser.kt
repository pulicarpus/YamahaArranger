import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourapp.yamahaarranger.browser.BrowserFolder
import com.yourapp.yamahaarranger.browser.BrowserItem
import com.yourapp.yamahaarranger.browser.BrowserSources
import com.yourapp.yamahaarranger.ui.BrowserKind
import com.yourapp.yamahaarranger.ui.BrowserLcdPage
import com.yourapp.yamahaarranger.ui.BrowserSidePanel
import com.yourapp.yamahaarranger.ui.BrowserUi
import com.yourapp.yamahaarranger.ui.VoiceHeaderExtras
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

private fun styleFolder(key: String, n: Int) = BrowserFolder(key, key, List(n) { BrowserItem("$key/Style${it + 1}", if (it == 0) "Love Song" else "Style ${it + 1}") })

private fun ui(kind: BrowserKind, folders: List<BrowserFolder>, folder: String, highlight: String?, page: Int, marked: String? = null) = BrowserUi(
    folders, folder, highlight, marked, page, true, true, null,
    "${folders.firstOrNull { it.key == folder }?.items?.size ?: 0} ${if (kind == BrowserKind.STYLE) "STYLES" else "VOICES"}",
    if (kind == BrowserKind.STYLE) "LOAD" else "APPLY", highlight != null, {}, {}, {}, {}, {}, {})

/** The LCD frame (centre) and right panel in the same 0.64 : 0.18 proportion the app uses. */
@Composable private fun Frame(kind: BrowserKind, title: String, u: BrowserUi, extras: VoiceHeaderExtras? = null) {
    Row(Modifier.fillMaxSize().background(Color(0xFF080A0D)).padding(7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(color = Color(0xFF101419), shape = RoundedCornerShape(6.dp), modifier = Modifier.weight(.64f).fillMaxHeight().border(2.dp, Color(0xFF39424C), RoundedCornerShape(6.dp))) {
            BrowserLcdPage(kind, title, u, false, {}, extras)
        }
        BrowserSidePanel(kind, u, false, Modifier.weight(.18f).fillMaxHeight())
    }
}

/** REPLICA of the pre-fix grid code (rows from chunked(4), each weight 1f) to show the bug; not used by the app. */
@Composable private fun LegacyStyleGrid(items: List<String>) {
    Row(Modifier.fillMaxSize().background(Color(0xFF080A0D)).padding(7.dp)) {
        Surface(color = Color(0xFF151A20), shape = RoundedCornerShape(4.dp), modifier = Modifier.weight(.64f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().padding(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { Surface(color = Color(0xFF20262D), shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1f).fillMaxHeight()) { Text(it, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) } }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Spacer(Modifier.weight(.18f))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun shot(dir: File, name: String, w: Int, h: Int, d: Float, content: @Composable () -> Unit): Image {
    val scene = ImageComposeScene(width = (w * d).toInt(), height = (h * d).toInt(), density = Density(d), content = content)
    scene.render(0L); val img = scene.render(250_000_000L)
    File(dir, name).writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes); scene.close(); return img
}

fun renderBrowsers(dir: File) {
    val W = 1000; val H = 560; val D = 2f
    val folderA = styleFolder("Pop", 66); val folderB = styleFolder("Dangdut", 4); val folderC = styleFolder("Solo", 1)
    val folderD = styleFolder("Seventeen", 17); val folderE = styleFolder("Fifteen", 15); val folderF = styleFolder("Sixteen", 16)
    val all = listOf(folderA, folderB, folderC, folderD, folderE, folderF)
    val four = shot(dir, "10_style_4_items.png", W, H, D) { Frame(BrowserKind.STYLE, "Dangdut", ui(BrowserKind.STYLE, all, "Dangdut", "Dangdut/Style1", 0)) }
    val full = shot(dir, "11_style_66_page1.png", W, H, D) { Frame(BrowserKind.STYLE, "Pop", ui(BrowserKind.STYLE, all, "Pop", "Pop/Style1", 0)) }
    shot(dir, "12_style_1_item.png", W, H, D) { Frame(BrowserKind.STYLE, "Solo", ui(BrowserKind.STYLE, all, "Solo", "Solo/Style1", 0)) }
    shot(dir, "13_style_17_page2.png", W, H, D) { Frame(BrowserKind.STYLE, "Seventeen", ui(BrowserKind.STYLE, all, "Seventeen", "Seventeen/Style17", 1)) }
    shot(dir, "14_style_BEFORE_bug_4_items.png", W, H, D) { LegacyStyleGrid(List(4) { if (it == 0) "Love Song" else "Style ${it + 1}" }) }
    // Cell geometry must not depend on the item count: the first cell of the 4-item and 66-item pages is pixel-identical.
    val x0 = (W * D * 0.012).toInt(); val y0 = (H * D * 0.115).toInt(); val cw = (W * D * 0.09).toInt(); val ch = (H * D * 0.06).toInt()
    fun crop(img: Image): ByteArray { val bmp = org.jetbrains.skia.Bitmap(); bmp.allocN32Pixels(cw, ch); val cv = org.jetbrains.skia.Canvas(bmp)
        cv.drawImageRect(img, org.jetbrains.skia.Rect.makeXYWH(x0.toFloat(), y0.toFloat(), cw.toFloat(), ch.toFloat()), org.jetbrains.skia.Rect.makeWH(cw.toFloat(), ch.toFloat())); return org.jetbrains.skia.Image.makeFromBitmap(bmp).encodeToData()!!.bytes }
    println("CELL_SIZE_IDENTICAL_4_vs_66=" + crop(four).contentEquals(crop(full)))
    // Voice browser: SX Prime Melody specimen (names are the real phdr names of the owner's SF2; the ordering is bank, program).
    val prime = listOf(0 to "Yamaha ConcertGrand", 1 to "Yamaha Bright Piano", 2 to "Yamaha EleckPiano", 3 to "Yamaha Honky Tonk", 7 to "Clavinet", 11 to "Vibraphone",
        12 to "Marimba", 16 to "Church Organ1", 17 to "B3 Org", 19 to "Church Org", 20 to "Reed Org", 21 to "Accordion", 23 to " Accordian", 24 to "Clean Guitar Sweet",
        25 to "RYTEM YAMAHA", 26 to "RYTEM PSR-SX", 27 to "Clean yamaha", 28 to "Electric Guitar (Cl", 29 to "Overdriven", 30 to "Overdriven", 31 to "Rock Hero",
        32 to "elctrick bass", 33 to "BASS PSR", 34 to "BASS PSR", 40 to "Saleem Abdullah", 52 to "Suling S770", 72 to "Suling S770", 73 to "Suling S770", 74 to "Flute reza748", 75 to "Pan Flute")
    val file = "MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2"
    val voices = BrowserSources.voiceFolders(prime.map { BrowserSources.VoiceLike(file, 0, it.first, it.second) } +
        List(40) { BrowserSources.VoiceLike("ColomboGMGS2_BM.sf2", 0, it, "Colombo voice ${it + 1}") })
    val vu = ui(BrowserKind.VOICE, voices, file, BrowserSources.voiceItemKey(file, 0, 52), 1, BrowserSources.voiceItemKey(file, 0, 48))
    shot(dir, "15_voice_browser_prime_p2.png", W, H, D) { Frame(BrowserKind.VOICE, "RIGHT 1 — $file", vu, VoiceHeaderExtras("CAT: ALL", false, {}, {})) }
    shot(dir, "16_voice_browser_prime_p1.png", W, H, D) { Frame(BrowserKind.VOICE, "RIGHT 1 — $file", ui(BrowserKind.VOICE, voices, file, BrowserSources.voiceItemKey(file, 0, 32), 0), VoiceHeaderExtras("CAT: BASS", true, {}, {})) }
    val long = BrowserFolder("Colombo", "ColomboGMGS2_BM.sf2", List(892) { BrowserItem("c$it", "Colombo preset ${it + 1}", "0:$it · PC${it + 1}") })
    shot(dir, "17_voice_page_group_2.png", W, H, D) { Frame(BrowserKind.VOICE, "RIGHT 2 — Colombo", ui(BrowserKind.VOICE, listOf(long), "Colombo", "c600", 37), VoiceHeaderExtras("CAT: ALL", false, {}, {})) }
}
