package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourapp.yamahaarranger.browser.BrowserFolder
import com.yourapp.yamahaarranger.browser.BrowserGrid
import com.yourapp.yamahaarranger.browser.BrowserItem

/** Same palette as the Style Browser in SxMainScreen; one place so Style and Voice cannot drift apart. */
internal object BrowserPalette {
    val Lcd = Color(0xFF151A20); val LcdBorder = Color(0xFF38424C)
    val Cell = Color(0xFF20262D); val CellBorder = Color(0xFF38434E)
    val Blue = Color(0xFF0878D1); val BlueBorder = Color(0xFF58A9FF); val BlueSub = Color(0xFFC9E5FF)
    val Orange = Color(0xFFB65300); val OrangeBright = Color(0xFFFF8A00); val OrangeBorder = Color(0xFFFFB25A)
    val Page = Color(0xFF303840); val PageOff = Color(0xFF171B20); val PageBorder = Color(0xFF444D56)
    val Dim = Color(0xFF9AA3AD); val Green = Color(0xFF27D887)
    val Panel = Color(0xFF12161B); val PanelBorder = Color(0xFF303942); val Row = Color(0xFF1D2329)
    val VoiceAccent = Color(0xFF0878D1)
}

enum class BrowserKind { STYLE, VOICE }

/** Everything a browser page needs; Style and Voice fill it differently but render through the same composables. */
class BrowserUi(
    val folders: List<BrowserFolder>,
    val folderKey: String?,
    val highlightKey: String?,
    /** Active item (the Voice currently assigned to the part); shown with an orange outline. */
    val markedKey: String?,
    val page: Int,
    val canPrev: Boolean,
    val canNext: Boolean,
    /** Replaces the grid (e.g. "SELECT A STYLE FOLDER"). */
    val message: String?,
    val subtitle: String,
    val actionLabel: String,
    val actionEnabled: Boolean,
    val onPage: (Int) -> Unit,
    val onCell: (String) -> Unit,
    val onFolder: (String) -> Unit,
    val onPrev: () -> Unit,
    val onNext: () -> Unit,
    val onAction: () -> Unit
) {
    val folder: BrowserFolder? get() = folders.firstOrNull { it.key == folderKey }
    val items: List<BrowserItem> get() = folder?.items.orEmpty()
    val pageCount: Int get() = BrowserGrid.pageCount(items.size)
    val safePage: Int get() = page.coerceIn(0, pageCount - 1)
}

/** Header extras only the Voice browser has (category filter, search). */
class VoiceHeaderExtras(val categoryLabel: String, val searchActive: Boolean, val onCategory: () -> Unit, val onSearch: () -> Unit)

/** Centre LCD page: header, fixed 4x4 grid (16 equal slots whatever the item count), P buttons. */
@Composable
fun BrowserLcdPage(kind: BrowserKind, title: String, ui: BrowserUi, compact: Boolean, onHome: () -> Unit, extras: VoiceHeaderExtras? = null, modifier: Modifier = Modifier.fillMaxSize()) {
    val accent = if (kind == BrowserKind.STYLE) BrowserPalette.OrangeBright else BrowserPalette.VoiceAccent
    val items = ui.items
    val page = ui.safePage
    Column(modifier.padding(if (compact) 5.dp else 7.dp)) {
        Row(Modifier.fillMaxWidth().height(if (compact) 27.dp else 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (kind == BrowserKind.STYLE) "STYLE" else "VOICE", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(7.dp))
            Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            extras?.let {
                TextButton(onClick = it.onCategory, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(it.categoryLabel, color = BrowserPalette.Dim, fontSize = 8.sp, maxLines = 1) }
                TextButton(onClick = it.onSearch, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if (it.searchActive) "SEARCH •" else "SEARCH", color = if (it.searchActive) BrowserPalette.BlueBorder else BrowserPalette.Dim, fontSize = 8.sp, maxLines = 1) }
            }
            Text(ui.subtitle, color = BrowserPalette.Dim, fontSize = 7.sp)
            TextButton(onClick = onHome, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("HOME", color = BrowserPalette.Green, fontSize = 8.sp) }
        }
        if (ui.message != null) {
            Surface(color = BrowserPalette.Lcd, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                Box(contentAlignment = Alignment.Center) { Text(ui.message, color = BrowserPalette.Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        } else {
            Surface(color = BrowserPalette.Lcd, shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth().weight(1f).border(1.dp, BrowserPalette.LcdBorder, RoundedCornerShape(4.dp))) {
                // ALWAYS 4 rows x 4 columns of equal weight: the cell size is fixed by the panel, never by how many items exist.
                val slots = BrowserGrid.slots(items, page)
                Column(Modifier.fillMaxSize().padding(if (compact) 4.dp else 5.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)) {
                    repeat(BrowserGrid.ROWS) { row ->
                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)) {
                            repeat(BrowserGrid.COLS) { col ->
                                val index = row * BrowserGrid.COLS + col
                                val item = slots[index]
                                if (item == null) Spacer(Modifier.weight(1f).fillMaxHeight())
                                else BrowserCell(kind, item, page * BrowserGrid.PAGE_SIZE + index + 1, item.key == ui.highlightKey, item.key == ui.markedKey, compact, Modifier.weight(1f).fillMaxHeight()) { ui.onCell(item.key) }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        val start = BrowserGrid.groupStart(page)
        Row(Modifier.fillMaxWidth().height(if (compact) 34.dp else 39.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            (0 until BrowserGrid.PAGE_BUTTONS).forEach { slot ->
                val index = start + slot
                val enabled = index < ui.pageCount && ui.message == null
                Surface(color = if (page == index) BrowserPalette.Orange else if (enabled) BrowserPalette.Page else BrowserPalette.PageOff,
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight().clickable(enabled = enabled) { ui.onPage(index) }
                        .border(1.dp, if (page == index) BrowserPalette.OrangeBorder else BrowserPalette.PageBorder, RoundedCornerShape(2.dp))) {
                    Box(contentAlignment = Alignment.Center) { Text("P${index + 1}", color = if (enabled) Color.White else BrowserPalette.Dim, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun BrowserCell(kind: BrowserKind, item: BrowserItem, number: Int, selected: Boolean, marked: Boolean, compact: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val style = kind == BrowserKind.STYLE
    Surface(color = if (selected) BrowserPalette.Blue else BrowserPalette.Cell, shape = RoundedCornerShape(3.dp),
        modifier = modifier.clickable(onClick = onClick).border(1.dp,
            if (selected) BrowserPalette.BlueBorder else if (marked) BrowserPalette.OrangeBright else BrowserPalette.CellBorder, RoundedCornerShape(3.dp))) {
        Column(Modifier.fillMaxSize().padding(horizontal = if (style) 6.dp else 6.dp, vertical = 3.dp), verticalArrangement = Arrangement.Center) {
            Text(if (style) item.title.take(22) else (if (marked) "● " else "") + item.title, color = Color.White,
                fontSize = if (compact) (if (style) 8.sp else 8.sp) else (if (style) 9.sp else 9.sp), fontWeight = FontWeight.Bold,
                maxLines = if (style) 1 else 2, overflow = TextOverflow.Ellipsis, lineHeight = if (style) 12.sp else 10.sp)
            Text(if (style) "STYLE $number" else item.subtitle, color = if (selected) BrowserPalette.BlueSub else BrowserPalette.Dim, fontSize = if (style) 5.sp else 6.sp, maxLines = 1)
        }
    }
}

/** Right panel: the folder (Style) or SoundFont (Voice) list, then Previous / Next / LOAD-APPLY. */
@Composable
fun BrowserSidePanel(kind: BrowserKind, ui: BrowserUi, compact: Boolean, modifier: Modifier = Modifier.fillMaxSize()) {
    val accent = if (kind == BrowserKind.STYLE) BrowserPalette.OrangeBright else Color(0xFF55A9E6)
    Surface(color = BrowserPalette.Panel, shape = RoundedCornerShape(5.dp), modifier = modifier.border(1.dp, BrowserPalette.PanelBorder, RoundedCornerShape(5.dp))) {
        Column(Modifier.fillMaxSize().padding(if (compact) 4.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) {
            Text(if (kind == BrowserKind.STYLE) "STYLE SELECT" else "VOICE SELECT", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(if (kind == BrowserKind.STYLE) "STYLE FOLDER" else "SOUNDFONT", color = BrowserPalette.Dim, fontSize = 7.sp, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(ui.folders, key = { it.key }) { folder ->
                    val selected = folder.key == ui.folderKey
                    Row(Modifier.fillMaxWidth().clickable { ui.onFolder(folder.key) }
                        .background(if (selected) BrowserPalette.Blue else BrowserPalette.Row, RoundedCornerShape(3.dp))
                        .padding(horizontal = 5.dp, vertical = if (compact) 6.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (kind == BrowserKind.STYLE) "📁" else "♪", fontSize = 9.sp, color = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text(folder.name, color = if (folder.items.isEmpty()) BrowserPalette.Dim else Color.White, fontSize = if (compact) 6.sp else 7.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(folder.items.size.toString(), color = BrowserPalette.Dim, fontSize = 6.sp)
                    }
                }
            }
            // Page groups are only needed beyond P6; the grid, P buttons and panel sizes do not change.
            if (ui.pageCount > BrowserGrid.PAGE_BUTTONS) {
                val start = BrowserGrid.groupStart(ui.safePage)
                Row(Modifier.fillMaxWidth().height(if (compact) 28.dp else 32.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    NavButton("P◀", start > 0, Modifier.weight(1f).fillMaxHeight()) { ui.onPage(start - 1) }
                    Text("P${start + 1}-${minOf(start + BrowserGrid.PAGE_BUTTONS, ui.pageCount)}/${ui.pageCount}", color = BrowserPalette.Dim, fontSize = 6.sp, maxLines = 1)
                    NavButton("P▶", start + BrowserGrid.PAGE_BUTTONS < ui.pageCount, Modifier.weight(1f).fillMaxHeight()) { ui.onPage(start + BrowserGrid.PAGE_BUTTONS) }
                }
            }
            Row(Modifier.fillMaxWidth().height(if (compact) 40.dp else 46.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                NavButton("◀", ui.canPrev, Modifier.weight(1f).fillMaxHeight(), 16.sp) { ui.onPrev() }
                NavButton("▶", ui.canNext, Modifier.weight(1f).fillMaxHeight(), 16.sp) { ui.onNext() }
                NavButton(ui.actionLabel, ui.actionEnabled, Modifier.weight(1.7f).fillMaxHeight(), 8.sp, accentFill = true) { ui.onAction() }
            }
            Text("◀ ▶ move the highlight only", color = BrowserPalette.Dim, fontSize = 6.sp, maxLines = 1)
        }
    }
}

@Composable
private fun NavButton(label: String, enabled: Boolean, modifier: Modifier, size: androidx.compose.ui.unit.TextUnit = 9.sp, accentFill: Boolean = false, onClick: () -> Unit) {
    Surface(color = if (!enabled) BrowserPalette.PageOff else if (accentFill) BrowserPalette.Blue else BrowserPalette.Page, shape = RoundedCornerShape(3.dp),
        modifier = modifier.then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).border(1.dp, if (enabled) BrowserPalette.PageBorder else BrowserPalette.PageOff, RoundedCornerShape(3.dp))) {
        Box(contentAlignment = Alignment.Center) { Text(label, color = if (enabled) Color.White else BrowserPalette.Dim, fontSize = size, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
    }
}
