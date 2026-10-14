package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.background
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

/** UI only. Navigation, composition and bank changes never recall or change playback. */
@Composable
fun LiveControlScreen(state: MainUiState, vm: MainViewModel, onArranger: () -> Unit, onMixer: () -> Unit) {
    val registrations by vm.liveRegistration.collectAsState()
    val external by vm.currentExternal.collectAsState()
    val library by vm.liveLibrary.collectAsState()
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
            } else Column(Modifier.fillMaxSize().padding(g.padding.dp),verticalArrangement=Arrangement.spacedBy(g.gap.dp)) {
                Row(Modifier.fillMaxWidth().height(g.header.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    LiveButton("ARRANGER",onArranger,LiveBlue,Modifier.width(112.dp).fillMaxHeight())
                    LiveButton("MIXER",onMixer,LiveBlue,Modifier.width(96.dp).fillMaxHeight())
                    Column(Modifier.weight(1f)) {
                        Text("${state.styleName} • ${state.activeSection} • ${if(state.isPlaying) "PLAY" else "STOP"}  ♩${state.tempoBpm}  TR ${state.transpose}",color=Color.White,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(registrations.message,color=if(registrations.warning) LiveAmber else LiveCyan,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text("FREEZE: ${liveFreezeLabel(registrations.freeze)}",color=LiveAmber,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    LiveButton("REG\nSETUP",{panel="BANK"},LiveCyan,Modifier.width(80.dp).height(64.dp))
                    Column(Modifier.width(180.dp)) {
                        Text("OUT: ${registrations.selectedMode?.name ?: "—"}",color=LiveCyan,fontSize=13.sp)
                        Text(liveOutputStatus(registrations.selectedMode,state.midiOutEnabled,registrations.selectedHasExternal),color=if(state.midiOutEnabled) LiveBlue else LiveAmber,fontSize=11.sp)
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.width(g.styleWidth.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text("INTRO",color=LiveOrange,fontSize=13.sp)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { (1..3).forEach { n -> LiveButton("$n",{vm.onSectionSelected("Intro $n")},LiveOrange,Modifier.size(64.dp)) } }
                        LiveButton("FILL",vm::onLiveFill,LiveOrange,Modifier.fillMaxWidth().height(64.dp))
                        Text("ENDING",color=LiveOrange,fontSize=13.sp)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { (1..3).forEach { n -> LiveButton("$n",{vm.onSectionSelected("Ending $n")},LiveOrange,Modifier.size(64.dp)) } }
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf("A","B","C","D").forEach { letter -> LiveButton("MAIN $letter",{vm.onLiveMainSelected(letter)},LiveOrange,
                                Modifier.weight(1f).height(g.mainButtonHeight.dp),selected=state.activeSection=="Main $letter") }
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterHorizontally)) {
                            LiveButton(if(state.isPlaying) "STOP" else "START",vm::onStartStop,if(state.isPlaying) Color(0xFFFF6A6A) else LiveGreen,Modifier.width(220.dp).height(96.dp),enabled=state.isPlaying||state.startReady)
                            LiveButton("ACMP",vm::toggleAcmp,LiveBlue,Modifier.width(64.dp).height(96.dp),selected=state.acmpEnabled)
                            LiveButton("SYNC\nN/A",{},LiveBlue,Modifier.width(64.dp).height(96.dp),enabled=false)
                            LiveButton("TAP\nN/A",{},LiveBlue,Modifier.width(64.dp).height(96.dp),enabled=false)
                        }
                        LiveButton(if(state.autoFill) "AUTO FILL ON" else "AUTO FILL OFF",vm::toggleAutoFill,LiveOrange,Modifier.fillMaxWidth().height(64.dp),selected=state.autoFill)
                        if(!state.startReady && !state.isPlaying) Text(state.readinessReason,color=LiveAmber,fontSize=13.sp)
                    }
                    Column(Modifier.width(g.partWidth.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        (0..1).forEach { row ->
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { (row*2..row*2+1).forEach { ch ->
                                val label=if(ch==3) "LEFT" else "RIGHT${ch+1}"
                                val enabled=if(ch==3) state.leftVoiceEnabled else state.rightVoices[ch].enabled
                                LiveButton("$label ${if(enabled) "ON" else "OFF"}\nEXT: ${external[ch]?.label ?: "—"}\nSF2: ${state.rightVoices[ch].displayName()}",
                                    {if(ch==3) vm.toggleLeftVoice() else vm.toggleRightVoice(ch)},LiveBlue,Modifier.width(g.partButtonWidth.dp).height(64.dp),selected=enabled,onLongClick={externalPart=ch;panel="EXTERNAL"})
                            } }
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                            LiveTempoButton(-1,vm::nudgeTempo)
                            Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) { Text("TEMPO",color=LiveBlue,fontSize=11.sp);Text("${state.tempoBpm}",color=Color.White,fontSize=20.sp) }
                            LiveTempoButton(1,vm::nudgeTempo)
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                            LiveButton("−",vm::onTransposeDown,LiveBlue,Modifier.size(64.dp))
                            Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) { Text("TRANS",color=LiveBlue,fontSize=11.sp);Text("${state.transpose}",color=Color.White,fontSize=20.sp) }
                            LiveButton("+",vm::onTransposeUp,LiveBlue,Modifier.size(64.dp))
                        }
                        LiveButton("RELEASE ${if(state.releaseEnabled) "ON" else "OFF"} • ${state.releaseTime}",vm::toggleRelease,LiveBlue,Modifier.fillMaxWidth().height(64.dp),selected=state.releaseEnabled)
                    }
                }
                if(panel.isNotEmpty()) Surface(color=LivePanel,modifier=Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap={}) }) {
                    LivePhase4Panel(panel,library,bankName,{bankName=it},slotName,{slotName=it},externalPart,vm,
                        onClose={panel=""})
                }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(library.undoUntil>System.nanoTime()/1_000_000) LiveButton("UNDO SAVE",vm::onLiveUndoSave,LiveAmber,Modifier.weight(1f).height(64.dp),enabled=!library.saving && !registrations.recalling)
                    else LiveSaveHoldButton(enabled=library.loaded && !library.saving && !registrations.recalling,
                        armed=library.save.armed(System.nanoTime()/1_000_000),onArm=vm::onLiveSaveArm)
                    LiveButton("SAVE OPTIONS",{panel="SAVE"},LiveCyan,Modifier.weight(1f).height(64.dp))
                    LiveButton("FREEZE",{panel="FREEZE"},LiveAmber,Modifier.weight(1f).height(64.dp))
                    LiveButton("EXT VOICE",{panel="EXTERNAL"},LiveBlue,Modifier.weight(1f).height(64.dp))
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
private fun LiveTempoButton(sign: Int,onDelta: (Int) -> Unit) {
    val update by rememberUpdatedState(onDelta)
    Surface(color=LivePanel,modifier=Modifier.size(64.dp).semantics {
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
    Surface(color=if(armed) Color(0xFF802828) else LivePanel,modifier=Modifier.width(180.dp).height(64.dp)
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
