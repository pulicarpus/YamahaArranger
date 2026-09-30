from pathlib import Path

p = Path("app/src/main/java/com/yourapp/yamahaarranger/audio/AudioEngineManager.kt")
s = p.read_text(encoding="utf-8")
old = '''            val melodyOk = bridge.nativeLoadMelodySoundFont(melodyPath)
            if (!melodyOk) return@withAudioStreamPausedUnsafe false
            val fallbackOk = fallbackMelodyPath.isNullOrBlank() ||
                bridge.nativeLoadMelodyFallbackSoundFont(fallbackMelodyPath)
            if (!fallbackOk) {
                DebugLog.add("⚠️ Secondary melody SF2 failed; continuing with Yamaha primary")
            }
            val drumOk = bridge.nativeLoadDrumSoundFont(drumPath)
            melodyOk && drumOk'''
new = '''            // Establish the essential Yamaha pair first. The previous order
            // loaded the large secondary melody font before the drum font; if
            // that consumed too much native/SF2 memory, the subsequent drum
            // load could fail and the whole startup transaction was reported
            // failed even though fallback itself is optional.
            val melodyOk = bridge.nativeLoadMelodySoundFont(melodyPath)
            if (!melodyOk) return@withAudioStreamPausedUnsafe false
            val drumOk = bridge.nativeLoadDrumSoundFont(drumPath)
            if (!drumOk) return@withAudioStreamPausedUnsafe false

            DebugLog.add("✅ Core MELODY + DRUM SF2 established; attaching secondary melody")
            val fallbackOk = fallbackMelodyPath.isNullOrBlank() ||
                bridge.nativeLoadMelodyFallbackSoundFont(fallbackMelodyPath)
            if (!fallbackOk) {
                // Fallback is deliberately non-fatal. BassMidiPlayer rolls a
                // failed fallback mapping back without discarding the already
                // valid primary+drum pair.
                DebugLog.add("⚠️ Secondary melody SF2 failed; core Yamaha pair remains active")
            } else if (!fallbackMelodyPath.isNullOrBlank()) {
                DebugLog.add("✅ Secondary melody SF2 attached")
            }
            true'''
if old not in s:
    raise SystemExit("expected loadSoundFontPairWithFallback block not found; refusing to modify source")
s = s.replace(old, new, 1)
p.write_text(s, encoding="utf-8")
print("Applied fallback load-order safety fix")
