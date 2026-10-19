package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourapp.yamahaarranger.registration.*
import kotlinx.coroutines.*

private val LiveBlue=Color(0xFF58B8FF)
private val LiveCyan=Color(0xFF45D9DB)
private val LiveOrange=Color(0xFFFFAA48)
private val LiveGreen=Color(0xFF27D887)
private val LiveAmber=Color(0xFFFFC857)
private val LivePanel=Color(0xFF171E26)
private val LiveDim=Color(0xFF6B7783)
private val LiveDimLed=Color(0xFF2A3139)
private val LiveUnknown=Color(0xFF8AA0B5)
private val LiveBgTop=Color(0xFF0D1626)
private val LiveBgBottom=Color(0xFF080E18)
private const val LIVE_HEADER_DP=64
private const val LIVE_FADER_PANEL_DP=236

/** UI only. Navigation, composition and bank changes never recall or change playback. */
@Composable
fun LiveControlScreen(state: MainUiState, vm: MainViewModel, onArranger: () -> Unit, onMixer: () -> Unit) {
    val registrations by vm.liveRegistration.collectAsState()
    val external by vm.currentExternal.collectAsState()
    val library by vm.liveLibrary.collectAsState()
    val inventory by vm.liveSectionInventory.collectAsState()
    var panel by remember { mutableStateOf("") }
    var externalPart by remember { mutableStateOf(0) }
    var bankName by remember { mutableStateOf("") }
    var slotName by remember { mutableStateOf("") }
    val context=LocalContext.current
    LaunchedEffect(vm) { vm.initializeLiveLibrary(context.filesDir.toPath().resolve("registration")) }
    val activity=generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<android.app.Activity>().firstOrNull()
    DisposableEffect(activity) {
        val window=activity?.window
        val controller=window?.let { androidx.core.view.WindowCompat.getInsetsController(it,it.decorView) }
        val insets=window?.let { androidx.core.view.ViewCompat.getRootWindowInsets(it.decorView) }
        val visibleTypes=listOf(androidx.core.view.WindowInsetsCompat.Type.statusBars(),androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            .fold(0) { mask,type -> if(insets?.isVisible(type)!=false) mask or type else mask }
        val keptAwake=((window?.attributes?.flags ?: 0) and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)!=0
        controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if(visibleTypes!=0) controller?.show(visibleTypes)
            if(!keptAwake) window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    Surface(color=LiveBgBottom,modifier=Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val g=LiveControlGeometry(maxWidth.value,maxHeight.value)
            val imeVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
            if(!liveInputViewportSupported(maxWidth.value,maxHeight.value,imeVisible)) {
                Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("LIVE requires landscape ≥ 960 × 600 dp",color=Color.White)
                    LiveButton("ARRANGER",onArranger,LiveBlue,Modifier.height(64.dp).width(160.dp))
                }
            } else Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(LiveBgTop,LiveBgBottom))).padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                // ---- HEADER: style, section, voices, tempo, transpose, ACMP / MIDI OUT (all from app state) ----
                LiveCard(Modifier.fillMaxWidth().height(LIVE_HEADER_DP.dp)) {
                Row(Modifier.fillMaxSize().padding(horizontal=8.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.width(84.dp).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        LiveButton("ARRANGER",onArranger,LiveBlue,Modifier.fillMaxWidth().weight(1f))
                        LiveButton("MIXER",onMixer,LiveBlue,Modifier.fillMaxWidth().weight(1f))
                    }
                    Column(Modifier.weight(1f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text(state.styleName,color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f,fill=false))
                            Box(Modifier.border(1.dp,LiveAmber,RoundedCornerShape(3.dp)).padding(horizontal=8.dp,vertical=1.dp)) {
                                Text(state.activeSection.uppercase(),color=LiveAmber,fontSize=14.sp,fontWeight=FontWeight.Bold,maxLines=1)
                            }
                            Text(if(state.isPlaying) "PLAY" else "STOP",color=if(state.isPlaying) LiveGreen else LiveDim,fontSize=12.sp,fontWeight=FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            (0..3).forEach { ch ->
                                val slot=state.rightVoices[ch]
                                val enabled=if(ch==3) state.leftVoiceEnabled else slot.enabled
                                val tag=if(ch==3) "LEFT" else "R${ch+1}"
                                val ext=external[ch]?.takeUnless { com.yourapp.yamahaarranger.registration.Sf2ExternalMapping.isDerived(it) }?.label
                                LiveVoiceChip(tag,if(enabled) slot.displayName() else "OFF",ext,enabled,Modifier.weight(1f).fillMaxHeight(),
                                    onClick={if(ch==3) vm.toggleLeftVoice() else vm.toggleRightVoice(ch)},onLong={externalPart=ch;panel="EXTERNAL"})
                            }
                        }
                    }
                    LiveStepper("TEMPO","${state.tempoBpm}",{ LiveTempoButton(-1,vm::nudgeTempo,38) },{ LiveTempoButton(1,vm::nudgeTempo,38) })
                    LiveStepper("TRANSPOSE",if(state.transpose>0) "+${state.transpose}" else "${state.transpose}",
                        { LiveButton("−",vm::onTransposeDown,LiveBlue,Modifier.size(38.dp)) },{ LiveButton("+",vm::onTransposeUp,LiveBlue,Modifier.size(38.dp)) })
                    Column(Modifier.width(92.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        LiveStatusLed("ACMP",state.acmpEnabled,LiveGreen)
                        LiveStatusLed("MIDI OUT",state.midiOutEnabled,LiveBlue)
                    }
                    LiveButton("REG\nSETUP",{panel="BANK"},LiveCyan,Modifier.width(70.dp).fillMaxHeight())
                }
                }
                Text("${registrations.message}  •  FREEZE: ${liveFreezeLabel(registrations.freeze)}  •  ${liveOutputStatus(registrations.selectedMode,state.midiOutEnabled,registrations.selectedHasExternal)}",
                    color=if(registrations.warning) LiveAmber else LiveDim,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        val oneRow=LiveStyleLayout.oneRow(maxWidth.value)
                        val keyFor: @Composable (LiveSectionButton,Boolean,Modifier) -> Unit = { b,big,mod ->
                            val led=liveSectionLed(inventory,b,state.activeSection)
                            LedKey(b.caption,null,sectionLedColor(led),if(led==SectionLed.ACTIVE) LiveAmber else if(led==SectionLed.ABSENT) LiveDim else Color.White,
                                liveSectionEnabled(led),mod,big=big,active=led==SectionLed.ACTIVE) { vm.onSectionSelected(b.label) }
                        }
                        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            // ---- STYLE CONTROL: LED from the loaded style's parsed section table ----
                            if(oneRow) Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                LiveStyleGroup("INTRO",LiveSectionGrid.intro,false,keyFor,Modifier.weight(3f).fillMaxHeight())
                                LiveStyleGroup("MAIN",LiveSectionGrid.main,true,keyFor,Modifier.weight(4f*LiveStyleLayout.MAIN_WEIGHT).fillMaxHeight())
                                LiveStyleGroup("FILL",LiveSectionGrid.fill,false,keyFor,Modifier.weight(4f).fillMaxHeight())
                                LiveStyleGroup("ENDING",LiveSectionGrid.ending,false,keyFor,Modifier.weight(3f).fillMaxHeight())
                            } else Column(Modifier.fillMaxWidth().weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth().weight(1.25f),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    LiveStyleGroup("INTRO",LiveSectionGrid.intro,false,keyFor,Modifier.weight(3f).fillMaxHeight())
                                    LiveStyleGroup("MAIN",LiveSectionGrid.main,true,keyFor,Modifier.weight(4f*LiveStyleLayout.MAIN_WEIGHT).fillMaxHeight())
                                }
                                Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    LiveStyleGroup("FILL",LiveSectionGrid.fill,false,keyFor,Modifier.weight(4f).fillMaxHeight())
                                    LiveStyleGroup("ENDING",LiveSectionGrid.ending,false,keyFor,Modifier.weight(3f).fillMaxHeight())
                                }
                            }
                            // ---- TRANSPORT ----
                            Row(Modifier.fillMaxWidth().height(68.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                LedKey(if(state.isPlaying) "STOP" else "START",if(!state.startReady && !state.isPlaying) state.readinessReason else null,
                                    if(state.isPlaying) Color(0xFFFF6A6A) else LiveGreen,Color.White,state.isPlaying||state.startReady,Modifier.weight(2f).fillMaxHeight(),big=false,active=state.isPlaying,onClick=vm::onStartStop)
                                LedKey("ACMP",if(state.acmpEnabled) "ON" else "OFF",if(state.acmpEnabled) LiveGreen else LiveDimLed,Color.White,true,Modifier.weight(1f).fillMaxHeight(),big=false,active=false,onClick=vm::toggleAcmp)
                                LedKey("AUTO FILL",if(state.autoFill) "ON" else "OFF",if(state.autoFill) LiveAmber else LiveDimLed,Color.White,true,Modifier.weight(1f).fillMaxHeight(),big=false,active=false,onClick=vm::toggleAutoFill)
                                LedKey("RELEASE","${if(state.releaseEnabled) "ON" else "OFF"} ${state.releaseTime}",if(state.releaseEnabled) LiveBlue else LiveDimLed,Color.White,true,Modifier.weight(1f).fillMaxHeight(),big=false,active=false,onClick=vm::toggleRelease)
                            }
                        }
                    }
                    // ---- LIVE MIXER: four independent faders, existing per-part / style volume actions only ----
                    LiveCard(Modifier.width(LIVE_FADER_PANEL_DP.dp).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize().padding(8.dp)) {
                            Text("LIVE MIXER",color=LiveCyan,fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp)
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                LiveFader("RIGHT1",state.right1Volume,LiveBlue,vm::onRight1VolumeChange,Modifier.weight(1f).fillMaxHeight())
                                LiveFader("RIGHT2",state.right2Volume,LiveCyan,vm::onRight2VolumeChange,Modifier.weight(1f).fillMaxHeight())
                                LiveFader("RIGHT3",state.right3Volume,LiveGreen,vm::onRight3VolumeChange,Modifier.weight(1f).fillMaxHeight())
                                LiveFader("STYLE",state.styleVolume,LiveOrange,vm::onStyleVolumeChange,Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    }
                }
                if(panel.isNotEmpty()) Surface(color=LivePanel,modifier=Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap={}) }) {
                    LivePhase4Panel(panel,library,bankName,{bankName=it},slotName,{slotName=it},externalPart,vm,
                        onClose={panel=""})
                }
                }
                // ---- REGISTRATION BANK: bank name/number, previous/next, exactly eight slots ----
                LiveCard(Modifier.fillMaxWidth().height(g.regHeight.dp)) {
                Row(Modifier.fillMaxSize().padding(6.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.width(g.bankWidth.dp).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text("REG BANK ${if(registrations.banks.isEmpty()) "–" else "${registrations.bankIndex+1}/${registrations.banks.size}"}",color=LiveCyan,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp,maxLines=1)
                        Text(registrations.bank?.name ?: "NO BANK",color=Color.White,fontSize=14.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            LiveButton("◀",{vm.onLiveBankChange(-1)},LiveCyan,Modifier.weight(1f).fillMaxHeight(),enabled=registrations.canPrevious)
                            LiveButton("▶",{vm.onLiveBankChange(1)},LiveCyan,Modifier.weight(1f).fillMaxHeight(),enabled=registrations.canNext)
                        }
                    }
                    (0..7).forEach { index ->
                        val slot=registrations.slot(index)
                        val selected=registrations.selectedBankId==registrations.bank?.id && registrations.selectedSlot==index
                        val armed=library.save.armed(System.nanoTime()/1_000_000)
                        val look=liveRegKeyLook(slot!=null,selected,armed)
                        LedKey("${index+1}",if(slot!=null) "${liveModeBadge(slot.outputMode)} ${slot.name}${if(selected && registrations.missingVoice) " ⚠" else ""}" else if(armed) "SAVE HERE" else "EMPTY",
                            when(look) { RegKeyLook.ACTIVE -> LiveAmber; RegKeyLook.FILLED -> LiveCyan; RegKeyLook.EMPTY -> LiveDimLed; RegKeyLook.SAVE_TARGET -> Color(0xFFFF6A6A) },
                            if(look==RegKeyLook.EMPTY) LiveDim else Color.White,
                            library.loaded && !library.saving && registrations.bank!=null && registrations.bank!!.id !in library.readOnly && (slot!=null || armed),
                            Modifier.weight(1f).fillMaxHeight(),big=false,active=look==RegKeyLook.ACTIVE,empty=look==RegKeyLook.EMPTY) { vm.onLiveSlotTap(index,slotName) }
                    }
                }
                }
                // ---- secondary: existing SAVE / FREEZE / EXT mechanisms, unchanged ----
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    if(library.undoUntil>System.nanoTime()/1_000_000) LiveButton("UNDO SAVE",vm::onLiveUndoSave,LiveAmber,Modifier.weight(1f).height(40.dp),enabled=!library.saving && !registrations.recalling)
                    else LiveSaveHoldButton(enabled=library.loaded && !library.saving && !registrations.recalling,
                        armed=library.save.armed(System.nanoTime()/1_000_000),onArm=vm::onLiveSaveArm,modifier=Modifier.weight(1f).height(40.dp))
                    LiveButton("SAVE OPTIONS",{panel="SAVE"},LiveCyan,Modifier.weight(1f).height(40.dp))
                    LiveButton("FREEZE",{panel="FREEZE"},LiveAmber,Modifier.weight(1f).height(40.dp))
                    LiveButton("EXT VOICE",{panel="EXTERNAL"},LiveBlue,Modifier.weight(1f).height(40.dp))
                }
            }
        }
    }
}

@Composable
private fun LiveButton(label: String,onClick: () -> Unit,color: Color,modifier: Modifier,enabled: Boolean=true,selected: Boolean=false,onLongClick: (() -> Unit)? = null) {
    if(onLongClick!=null) {
        Surface(color=if(selected) color.copy(alpha=0.3f) else LivePanel,modifier=modifier
            .semantics { contentDescription=label.replace("\n"," ");role=Role.Button;onClick { if(enabled) onClick();enabled } }
            .pointerInput(enabled,onClick,onLongClick) {
                if(enabled) detectTapGestures(onPress={
                    var long=false
                    coroutineScope {
                        val hold=launch { delay(600);long=true;onLongClick() }
                        val released=try { tryAwaitRelease() } finally { hold.cancel() }
                        if(released && !long) onClick()
                    }
                })
            }) { Box(contentAlignment=Alignment.Center) { Text(label,color=color,fontSize=14.sp,fontWeight=FontWeight.Bold,maxLines=3,overflow=TextOverflow.Ellipsis) } }
    } else Button(onClick=onClick,enabled=enabled,modifier=modifier.semantics { contentDescription=label.replace("\n"," ") },
        contentPadding=PaddingValues(4.dp),colors=ButtonDefaults.buttonColors(containerColor=if(selected) color.copy(alpha=0.3f) else LivePanel,contentColor=color,disabledContainerColor=LivePanel,disabledContentColor=Color(0xFF8F9BA7))) {
        Text(label,fontSize=14.sp,fontWeight=FontWeight.Bold,maxLines=3,overflow=TextOverflow.Ellipsis)
    }
}
@Composable
private fun LiveTempoButton(sign: Int,onDelta: (Int) -> Unit,sizeDp: Int=64) {
    val update by rememberUpdatedState(onDelta)
    Surface(color=LivePanel,modifier=Modifier.size(sizeDp.dp).semantics {
        contentDescription=if(sign>0) "Increase tempo" else "Decrease tempo"
        role=Role.Button
        onClick { update(sign);true }
    }.pointerInput(sign) {
        detectTapGestures(onPress={
            val began=System.nanoTime();update(sign)
            coroutineScope {
                val repeat=launch { delay(350);while(isActive) { update(sign*liveTempoStep((System.nanoTime()-began)/1_000_000));delay(125) } }
                try { tryAwaitRelease() } finally { repeat.cancel() }
            }
        })
    }) { Box(contentAlignment=Alignment.Center) { Text(if(sign>0) "+" else "−",color=LiveBlue,fontSize=24.sp) } }
}

@Composable
private fun LiveSaveHoldButton(enabled: Boolean,armed: Boolean,onArm: (Long) -> Unit,modifier: Modifier) {
    Surface(color=if(armed) Color(0xFF802828) else LivePanel,modifier=modifier
        .semantics { contentDescription="Hold SAVE 600 milliseconds" }
        .pointerInput(enabled,onArm) {
            if(enabled) detectTapGestures(onPress={
                coroutineScope { val hold=launch { delay(600);onArm(600) }
                    try { tryAwaitRelease() } finally { hold.cancel() } }
            })
        }) { Box(contentAlignment=Alignment.Center) { Text(if(armed) "TAP SLOT TO SAVE" else "HOLD SAVE",color=if(enabled) Color(0xFFFF8C8C) else Color.Gray) } }
}

@Composable
private fun LivePhase4Panel(panel: String,library: RegistrationLibraryState,bankName: String,
    onBankName: (String)->Unit,slotName: String,onSlotName: (String)->Unit,initialPart: Int,
    vm: MainViewModel,onClose: ()->Unit) {
    var part by remember(initialPart) { mutableStateOf(initialPart) }
    var label by remember { mutableStateOf("") }
    var msb by remember { mutableStateOf("0") }
    var lsb by remember { mutableStateOf("0") }
    var pc by remember { mutableStateOf("0") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(panel,color=LiveCyan,fontSize=20.sp)
            LiveButton("CLOSE",onClose,LiveBlue,Modifier.width(112.dp).height(64.dp))
        }
        when(panel) {
            "BANK" -> {
                OutlinedTextField(bankName,onBankName,label={Text("New bank name (24 characters)")},singleLine=true)
                LiveButton("CREATE BANK",{vm.onLiveCreateBank(bankName)},LiveCyan,Modifier.height(64.dp),enabled=bankName.isNotBlank() && !library.manifestReadOnly)
                library.banks.forEachIndexed { i,b -> Text("${i+1}: ${b.name}${if(b.id in library.readOnly) " • READ-ONLY" else ""}",color=Color.White) }
                Text("Use bank ◀ / ▶ to select; selection never recalls.",color=Color.White)
                Text("Default SAVE source",color=LiveCyan)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutputMode.values().forEach { mode ->
                    LiveButton(mode.name,{vm.onLiveDefaultMode(mode)},LiveBlue,Modifier.height(64.dp),selected=library.manifest.defaultOutputMode==mode)
                } }
            }
            "SAVE" -> {
                OutlinedTextField(slotName,{onSlotName(it.take(24))},label={Text("Registration name")},singleLine=true)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutputMode.values().forEach { mode ->
                    LiveButton(mode.name,{vm.onLiveSaveOptions(mode,library.saveTempo,library.saveTranspose)},LiveCyan,Modifier.height(64.dp),selected=library.mode==mode)
                } }
                Row { Checkbox(library.saveTempo,{vm.onLiveSaveOptions(library.mode,it,library.saveTranspose)},modifier=Modifier.size(64.dp));Text("Save tempo",color=Color.White) }
                Row { Checkbox(library.saveTranspose,{vm.onLiveSaveOptions(library.mode,library.saveTempo,it)},modifier=Modifier.size(64.dp));Text("Save transpose",color=Color.White) }
                Text("Close this panel, hold SAVE 600 ms, then tap REG. Occupied slots require a second tap within 2 seconds. UNDO SAVE: 10 seconds.",color=Color.White)
            }
            "FREEZE" -> {
                val f=library.manifest.freeze
                listOf("TEMPO" to f.tempo,"TRANSPOSE" to f.transpose,"RELEASE" to f.release,"VOICES" to f.voices,"VOLUMES" to f.volumes,"PART ON/OFF" to f.partOnOff).forEachIndexed { i,p ->
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(p.second,{ v -> vm.onLiveFreeze(when(i) {
                            0->f.copy(tempo=v);1->f.copy(transpose=v);2->f.copy(release=v)
                            3->f.copy(voices=v);4->f.copy(volumes=v);else->f.copy(partOnOff=v)
                        }) },modifier=Modifier.size(64.dp));Text(p.first,color=Color.White)
                    }
                }
            }
            "EXTERNAL" -> {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { (0..3).forEach { ch ->
                    LiveButton(if(ch==3) "LEFT" else "RIGHT${ch+1}",{part=ch},LiveBlue,Modifier.height(64.dp),selected=part==ch)
                } }
                Text("MIDI values are 0–127. If the Data List numbers Program 1–128, subtract 1. Enter MSB/LSB as the documented MIDI values; no SF2 conversion.",color=LiveAmber)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(label,{label=it.take(24)},label={Text("Label")},modifier=Modifier.weight(2f),singleLine=true)
                    OutlinedTextField(msb,{msb=it},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),label={Text("MSB 0–127")},modifier=Modifier.weight(1f),singleLine=true)
                    OutlinedTextField(lsb,{lsb=it},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),label={Text("LSB 0–127")},modifier=Modifier.weight(1f),singleLine=true)
                    OutlinedTextField(pc,{pc=it},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),label={Text("Program 0–127")},modifier=Modifier.weight(1f),singleLine=true)
                }
                val valid=externalVoiceInput(msb,lsb,pc,label)!=null
                LiveButton("ADD TO LIST",{externalVoiceInput(msb,lsb,pc,label)?.let { vm.onLiveAddExternal(it) }},LiveBlue,Modifier.height(64.dp),enabled=valid && !library.manifestReadOnly)
                Text("ADD only stores an entry. SELECT explicitly sends Bank/Program if MIDI OUT is ON. Connect/reconnect never selects it.",color=Color.White)
                library.manifest.externalVoices.forEach { entry ->
                    val v=entry.voice
                    LiveButton("SELECT ${v.label} • ${v.msb}/${v.lsb}/${v.program}",{vm.onLiveExternalSelected(part,v)},LiveBlue,Modifier.fillMaxWidth().height(64.dp))
                }
            }
        }
        Text(library.message,color=LiveAmber)
    }
}

private fun sectionLedColor(led: SectionLed): Color = when(led) {
    SectionLed.AVAILABLE -> LiveBlue
    SectionLed.ACTIVE -> LiveAmber
    SectionLed.UNKNOWN -> LiveUnknown
    SectionLed.ABSENT -> LiveDimLed
}

/** Dark arranger keycap: soft gradient face, thin LED bar on top, restrained shadow. A disabled key does nothing; there are no placeholder keys. */
@Composable
private fun LedKey(label: String,sub: String?,led: Color,text: Color,enabled: Boolean,modifier: Modifier,big: Boolean=false,active: Boolean=false,empty: Boolean=false,onClick: () -> Unit) {
    val shape=RoundedCornerShape(5.dp)
    val face=if(!enabled) Brush.verticalGradient(listOf(Color(0xFF151B23),Color(0xFF10151B)))
        else if(empty) Brush.verticalGradient(listOf(Color(0xFF1A222C),Color(0xFF131A22)))
        else Brush.verticalGradient(listOf(Color(0xFF2B3746),Color(0xFF18212C)))
    Column(modifier.shadow(if(enabled) 3.dp else 0.dp,shape).background(face,shape)
        .border(if(active) 2.dp else 1.dp,if(active) led else if(enabled) Color(0xFF3A4757) else Color(0xFF1F2832),shape)
        .semantics { contentDescription=label+(if(sub!=null) " $sub" else "");role=Role.Button;if(!enabled) disabled() }
        .clickable(enabled=enabled,onClick=onClick)) {
        Box(Modifier.fillMaxWidth().height(if(big) 7.dp else 5.dp).background(led,RoundedCornerShape(topStart=5.dp,topEnd=5.dp)))
        Column(Modifier.fillMaxSize().padding(horizontal=3.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
            Text(label,color=text,fontSize=if(label.length>2) 17.sp else if(big) 34.sp else 26.sp,fontWeight=FontWeight.Bold,maxLines=1,softWrap=false)
            if(sub!=null) Text(sub,color=LiveDim,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}

/** Raised dark-navy panel used for header, style groups, mixer and registration bank. */
@Composable
private fun LiveCard(modifier: Modifier,content: @Composable () -> Unit) {
    val shape=RoundedCornerShape(7.dp)
    Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFF141F31),Color(0xFF0F1826))),shape).border(1.dp,Color(0xFF22324A),shape)) { content() }
}

@Composable
private fun LiveStyleGroup(title: String,buttons: List<LiveSectionButton>,big: Boolean,
    key: @Composable (LiveSectionButton,Boolean,Modifier) -> Unit,modifier: Modifier) {
    LiveCard(modifier) {
        Column(Modifier.fillMaxSize().padding(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,color=LiveOrange,fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp,maxLines=1)
            Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                buttons.forEach { b -> key(b,big,Modifier.weight(if(big) LiveStyleLayout.MAIN_WEIGHT else 1f).fillMaxHeight()) }
            }
        }
    }
}

/** Voice summary chip: tap toggles the part, long press opens the external voice list (unchanged behaviour). */
@Composable
private fun LiveVoiceChip(tag: String,name: String,ext: String?,on: Boolean,modifier: Modifier,onClick: () -> Unit,onLong: () -> Unit) {
    val shape=RoundedCornerShape(4.dp)
    Row(modifier.background(if(on) Color(0xFF1B2C44) else Color(0xFF131A24),shape).border(1.dp,if(on) Color(0xFF2F5A8C) else Color(0xFF222C3A),shape)
        .semantics { contentDescription="$tag $name"+(if(ext!=null) " EXT $ext" else "");role=Role.Button;onClick { onClick();true } }
        .pointerInput(Unit) { detectTapGestures(onTap={ onClick() },onLongPress={ onLong() }) }.padding(horizontal=6.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).background(if(on) LiveBlue else LiveDimLed,RoundedCornerShape(4.dp)))
        Text(tag,color=if(on) LiveBlue else LiveDim,fontSize=11.sp,fontWeight=FontWeight.Bold,maxLines=1)
        Column(Modifier.weight(1f)) {
            Text(name,color=if(on) Color.White else LiveDim,fontSize=13.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(ext!=null) Text("EXT $ext",color=LiveCyan,fontSize=9.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LiveStepper(title: String,value: String,minus: @Composable () -> Unit,plus: @Composable () -> Unit) {
    Column(horizontalAlignment=Alignment.CenterHorizontally) {
        Text(title,color=LiveBlue,fontSize=10.sp)
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            minus()
            Text(value,color=Color.White,fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.widthIn(min=44.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            plus()
        }
    }
}

@Composable
private fun LiveStatusLed(name: String,on: Boolean,color: Color) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(if(on) color else LiveDimLed,RoundedCornerShape(5.dp)))
        Text(name,color=if(on) Color.White else LiveDim,fontSize=12.sp,fontWeight=FontWeight.Bold)
    }
}
