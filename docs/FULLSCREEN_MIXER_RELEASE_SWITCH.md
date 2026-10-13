# Fullscreen mixer and RELEASE bypass experiment

Base Android-verified RIGHT OFF checkpoint: 6b1665cbca74d8ecdbb59a9e594b21d050239371.
Branch: experiment/fullscreen-mixer-release-switch. No baseline merge.

UI audit: SxMainScreen already had a MIXER navigation button and a fullscreen
style-channel dialog, plus independent STYLE/RIGHT/LEFT volume callbacks. The
activity and manifest already enforce landscape. The dialog content is moved
into a dedicated page; ARRANGER and Android back return without stopping playback
or reconstructing the ViewModel. The page hides system bars and restores their
previous visibility. The activity's chord-selector overlay is hidden on this page.

The existing per-channel VOL/PAN/EXP/REV/CHO, voice selector and mute controls are
retained. Five master rows call the existing STYLE bus and RIGHT1/2/3/LEFT expression
callbacks. Channel cards scroll horizontally and their controls vertically. No
volume/audio mechanism, scheduler, native player, MIDI routing or SF2 loader change.

Only destinations with positive NOTE_ON data in the loaded style are displayed,
across its sections and matching CASM policies. The display helper does not alter
CASM. Reserved keyboard channels 0..3, controller-only tracks and velocity-zero
NOTE_ONs are excluded. There are no placeholder channels before a style is loaded.

RELEASE ON uses the stored 0..127 knob value; OFF sends 64 while retaining the
stored knob. Editing the knob while OFF changes only its stored next-ON value.
The serial collector delivers distinct effective values through the existing
Brain/engine/JNI/BASSMIDI path. Voice changes and MIDI OUT enable mirror that same
effective value. No panic, NOTE_OFF, SOUNDOFF or Sustain control is added by bypass.
Engine restart and SF2 lifecycle use the engine's retained effective value.

Tests cover channel inventory, stored/effective bypass state, held-note ownership,
restart, volume/mute restoration and STYLE/keyboard isolation. Real Linux PCM gates
exercise volume zero/restore on all four keyboard parts, style mute/unmute/trim,
and RELEASE 127/64/127 while notes remain held. Gain checks allow BASSMIDI's MIDI
expression curve; they do not replace that curve with a new audio mechanism.
All previous RIGHT OFF and RELEASE character tests remain mandatory before APK.

Brain, style code and native audio remain byte-identical to the RIGHT OFF checkpoint.
The original SHA guards remain intact. The known F12 baseline guard failure is
reported separately; the experiment is not READY certification. Layout, Android
back/navigation and actual SF2 audio still require Android testing after build.
