import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yourapp.yamahaarranger.audio.MetronomeMeter
import com.yourapp.yamahaarranger.audio.MetronomeMeters
import com.yourapp.yamahaarranger.audio.MetronomeMode
import com.yourapp.yamahaarranger.audio.MetronomeUiState
import com.yourapp.yamahaarranger.ui.MetronomePanel
import java.io.File

fun renderMetronome(dir: File, render: (String, Int, Int, Float, @Composable () -> Unit) -> Unit) {
    fun shot(name: String, state: MetronomeUiState, pulse: Int, level: Int) = render(name, 600, 250, 2.2f) {
        Box(Modifier.fillMaxSize().background(Color(0xFF080A0D)).padding(10.dp), contentAlignment = Alignment.Center) {
            MetronomePanel(state, pulse, level, {}, {}, {}, {}, {}, {})
        }
    }
    shot("20_metronome_standalone_44.png", MetronomeUiState(enabled = true, mode = MetronomeMode.STANDALONE, meter = MetronomeMeters.DEFAULT, tempoBpm = 120), 0, 2)
    shot("21_metronome_standalone_68.png", MetronomeUiState(enabled = true, mode = MetronomeMode.STANDALONE, meter = MetronomeMeter(6, 8), tempoBpm = 96, volumePermille = 400), 3, 1)
    shot("22_metronome_follow_style.png", MetronomeUiState(enabled = true, mode = MetronomeMode.FOLLOW, tempoBpm = 132, styleMeter = MetronomeMeter(4, 4), styleRunning = true, volumePermille = 700), 2, 0)
    shot("23_metronome_off_follow_stopped.png", MetronomeUiState(enabled = false, mode = MetronomeMode.FOLLOW, tempoBpm = 120, styleMeter = MetronomeMeter(3, 4), styleRunning = false), -1, 0)
}
