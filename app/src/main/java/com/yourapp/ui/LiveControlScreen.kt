package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
    Surface(color=Color(0xFF080D13),modifier=Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val g=LiveControlGeometry(maxWidth.value,maxHeight.value)
            val imeVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
            if(!liveInputViewportSupported(maxWidth.value,maxHeight.value,imeVisible)) {
                Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("LIVE requires landscape ≥ 960 × 600 dp",color=Color.White)
                    LiveButton("ARRANGER",onArranger,LiveBlue,Modifier.height(64.dp).width(160.dp))
                }
            } else Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                // ---- compact header: style, section, voices, tempo, transpose, ACMP / MIDI OUT (all from app state) ----
                Row(Modifier.fillMaxWidth().height(72.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    LiveButton("ARRANGER",onArranger,LiveBlue,Modifier.width(96.dp).fillMaxHeight())
                    LiveButton("MIXER",onMixer,LiveBlue,Modifier.width(80.dp).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text(state.styleName,color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f,fill=false))
                            Text(state.activeSection.uppercase(),color=LiveAmber,fontSize=16.sp,fontWeight=FontWeight.Bold,maxLines=1)
                            Text(if(state.isPlaying) "PLAY" else "STOP",color=if(state.isPlaying) LiveGreen else LiveDim,fontSize=12.sp,fontWeight=FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            (0..3).forEach { ch ->
                                val slot=state.rightVoices[ch]
                                val enabled=if(ch==3) state.leftVoiceEnabled else slot.enabled
                                val tag=if(ch==3) "L" else "R${ch+1}"
                                val ext=external[ch]?.label
                                LiveButton("$tag ${if(enabled) slot.displayName() else "OFF"}"+(if(ext!=null) "\nEXT $ext" else ""),
                                    {if(ch==3) vm.toggleLeftVoice() else vm.toggleRightVoice(ch)},if(enabled) LiveBlue else LiveDim,
                                    Modifier.weight(1f).fillMaxHeight(),selected=enabled,onLongClick={externalPart=ch;panel="EXTERNAL"})
                            }
                        }
                    }
                    LiveStepper("TEMPO","${state.tempoBpm}",{ LiveTempoButton(-1,vm::nudgeTempo,40) },{ LiveTempoButton(1,vm::nudgeTempo,40) })
                    LiveStepper("TRANSPOSE",if(state.transpose>0) "+${state.transpose}" else "${state.transpose}",
                        { LiveButton("−",vm::onTransposeDown,LiveBlue,Modifier.size(40.dp)) },{ LiveButton("+",vm::onTransposeUp,LiveBlue,Modifier.size(40.dp)) })
                    Column(Modifier.width(96.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        LiveStatusLed("ACMP",state.acmpEnabled,LiveGreen)
                        LiveStatusLed("MIDI OUT",state.midiOutEnabled,LiveBlue)
                    }
                    LiveButton("REG\nSETUP",{panel="BANK"},LiveCyan,Modifier.width(76.dp).fillMaxHeight())
                }
                Text("${registrations.message}  •  FREEZE: ${liveFreezeLabel(registrations.freeze)}  •  ${liveOutputStatus(registrations.selectedMode,state.midiOutEnabled,registrations.selectedHasExternal)}",
                    color=if(registrations.warning) LiveAmber else LiveCyan,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        // ---- section keys: LED from the loaded style's parsed section table ----
                        LiveSectionGrid.rows.forEach { row ->
                            Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text(row.first().group,color=LiveOrange,fontSize=12.sp,fontWeight=FontWeight.Bold,modifier=Modifier.width(60.dp))
                                row.forEach { b ->
                                    val led=liveSectionLed(inventory,b,state.activeSection)
                                    LedKey(b.caption,null,sectionLedColor(led),if(led==SectionLed.ACTIVE) LiveAmber else if(led==SectionLed.ABSENT) LiveDim else Color.White,
                                        liveSectionEnabled(led),Modifier.weight(1f).fillMaxHeight()) { vm.onSectionSelected(b.label) }
                                }
                                repeat(4-row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        // ---- transport ----
                        Row(Modifier.fillMaxWidth().height(76.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            LedKey(if(state.isPlaying) "STOP" else "START",if(!state.startReady && !state.isPlaying) state.readinessReason else null,
                                if(state.isPlaying) Color(0xFFFF6A6A) else LiveGreen,Color.White,state.isPlaying||state.startReady,Modifier.weight(2f).fillMaxHeight(),vm::onStartStop)
                            LedKey("ACMP",if(state.acmpEnabled) "ON" else "OFF",if(state.acmpEnabled) LiveGreen else LiveDimLed,Color.White,true,Modifier.weight(1f).fillMaxHeight(),vm::toggleAcmp)
                            LedKey("AUTO FILL",if(state.autoFill) "ON" else "OFF",if(state.autoFill) LiveAmber else LiveDimLed,Color.White,true,Modifier.weight(1f).fillMaxHeight(),vm::toggleAutoFill)
                            LedKey("RELEASE","${if(state.releaseEnabled) "ON" else "OFF"} ${state.releaseTime}",if(state.releaseEnabled) LiveBlue else LiveDimLed,Color.White,true,Modifier.weight(1f).fillMaxHeight(),vm::toggleRelease)
                        }
                    }
                    // ---- independent live faders: existing per-part / style volume actions only ----
                    Surface(color=LivePanel,shape=RoundedCornerShape(6.dp),modifier=Modifier.width(280.dp).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize().padding(8.dp)) {
                            Text("LIVE MIXER",color=LiveCyan,fontSize=12.sp,fontWeight=FontWeight.Bold)
                            Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.SpaceEvenly) {
                                LiveFader("R1",state.right1Volume,LiveBlue,vm::onRight1VolumeChange,Modifier.fillMaxHeight())
                                LiveFader("R2",state.right2Volume,LiveCyan,vm::onRight2VolumeChange,Modifier.fillMaxHeight())
                                LiveFader("R3",state.right3Volume,LiveGreen,vm::onRight3VolumeChange,Modifier.fillMaxHeight())
                                LiveFader("STYLE",state.styleVolume,LiveOrange,vm::onStyleVolumeChange,Modifier.fillMaxHeight())
                            }
                        }
                    }
                }
                if(panel.isNotEmpty()) Surface(color=LivePanel,modifier=Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap={}) }) {
                    LivePhase4Panel(panel,library,bankName,{bankName=it},slotName,{slotName=it},externalPart,vm,
                        onClose={panel=""})
                }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(library.undoUntil>System.nanoTime()/1_000_000) LiveButton("UNDO SAVE",vm::onLiveUndoSave,LiveAmber,Modifier.weight(1f).height(52.dp),enabled=!library.saving && !registrations.recalling)
                    else LiveSaveHoldButton(enabled=library.loaded && !library.saving && !registrations.recalling,
                        armed=library.save.armed(System.nanoTime()/1_000_000),onArm=vm::onLiveSaveArm)
                    LiveButton("SAVE OPTIONS",{panel="SAVE"},LiveCyan,Modifier.weight(1f).height(52.dp))
                    LiveButton("FREEZE",{panel="FREEZE"},LiveAmber,Modifier.weight(1f).height(52.dp))
                    LiveButton("EXT VOICE",{panel="EXTERNAL"},LiveBlue,Modifier.weight(1f).height(52.dp))
                }
                Row(Modifier.fillMaxWidth().height(g.regHeight.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.width(g.bankWidth.dp).fillMaxHeight()) {
                        Text(registrations.bank?.name ?: "NO BANK",color=LiveCyan,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            LiveButton("◀",{vm.onLiveBankChange(-1)},LiveCyan,Modifier.weight(1f).height(64.dp),enabled=registrations.canPrevious)
                            LiveButton("▶",{vm.onLiveBankChange(1)},LiveCyan,Modifier.weight(1f).height(64.dp),enabled=registrations.canNext)
                        }
                    }
                    (0..7).forEach { index ->
                        val slot=registrations.slot(index)
                        val selected=registrations.selectedBankId==registrations.bank?.id && registrations.selectedSlot==index
                        LiveButton("${index+1}  ${liveModeBadge(slot?.outputMode)}\n${slot?.name ?: "—"}${if(selected && registrations.missingVoice) " ⚠" else ""}",
                            {vm.onLiveSlotTap(index,slotName)},if(library.save.armed(System.nanoTime()/1_000_000)) Color(0xFFFF6A6A) else LiveCyan,Modifier.weight(1f).fillMaxHeight(),enabled=library.loaded && !library.saving && registrations.bank!=null && registrations.bank!!.id !in library.readOnly && (slot!=null || library.save.armed(System.nanoTime()/1_000_000)),selected=selected)
                    }
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
private fun LiveSaveHoldButton(enabled: Boolean,armed: Boolean,onArm: (Long) -> Unit) {
    Surface(color=if(armed) Color(0xFF802828) else LivePanel,modifier=Modifier.width(180.dp).height(52.dp)
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

/** Dark arranger keycap with a thin LED bar on top. A disabled key does nothing; there are no placeholder keys. */
@Composable
private fun LedKey(label: String,sub: String?,led: Color,text: Color,enabled: Boolean,modifier: Modifier,onClick: () -> Unit) {
    val shape=RoundedCornerShape(5.dp)
    Column(modifier.background(if(enabled) Color(0xFF1B2229) else Color(0xFF12171C),shape)
        .border(1.dp,if(enabled) Color(0xFF3A4550) else Color(0xFF222A32),shape)
        .semantics { contentDescription=label+(if(sub!=null) " $sub" else "");role=Role.Button;if(!enabled) disabled() }
        .clickable(enabled=enabled,onClick=onClick)) {
        Box(Modifier.fillMaxWidth().height(6.dp).background(led,RoundedCornerShape(topStart=5.dp,topEnd=5.dp)))
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
            Text(label,color=text,fontSize=if(label.length>2) 17.sp else 26.sp,fontWeight=FontWeight.Bold,maxLines=1)
            if(sub!=null) Text(sub,color=LiveDim,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
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
