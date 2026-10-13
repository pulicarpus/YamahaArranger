package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
    val context=LocalContext.current
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
            if(!g.supported) {
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
                    Column(Modifier.width(180.dp)) {
                        Text("OUT: ${registrations.selectedMode?.name ?: "—"}",color=LiveCyan,fontSize=13.sp)
                        Text(liveOutputStatus(registrations.selectedMode,state.midiOutEnabled,registrations.selectedHasExternal),color=if(state.midiOutEnabled) LiveBlue else LiveAmber,fontSize=11.sp)
                    }
                }
                Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
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
                                    {if(ch==3) vm.toggleLeftVoice() else vm.toggleRightVoice(ch)},LiveBlue,Modifier.width(g.partButtonWidth.dp).height(64.dp),selected=enabled)
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
                            {vm.onLiveRegRecall(index)},LiveCyan,Modifier.weight(1f).fillMaxHeight(),enabled=slot!=null,selected=selected)
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveButton(label: String,onClick: () -> Unit,color: Color,modifier: Modifier,enabled: Boolean=true,selected: Boolean=false) {
    Button(onClick=onClick,enabled=enabled,modifier=modifier.semantics { contentDescription=label.replace("\n"," ") },
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
