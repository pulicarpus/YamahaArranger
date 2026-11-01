## EnglishWaltz2.S519.prs

| Dst ch (0-based / 1-based) | Bank MSB | LSB | PC on the wire (0-based) / list Program (1-based) | Style voice name(s) | Sections | Sent by the app? | E343 status | E343 list entry / nearest |
|---|---|---|---|---|---|---|---|---|
| 1 / 2 | 0 | 112 | 33 / 34 |  | FillBA | UNPROVEN: this part has no CASM entry for its source ch 2; the setup path (applyVoicesFromCasm) only sends parts named by CASM | **EXACT** | #45 Finger Bass (PANEL, VOICE) |
| 2 / 3 | 0 | 112 | 25 / 26 |  | FillAA | UNPROVEN: this part has no CASM entry for its source ch 3; the setup path (applyVoicesFromCasm) only sends parts named by CASM | **EXACT** | #35 Folk Guitar (PANEL, VOICE) |
| 8 / 9 | 127 | 0 | 0 / 1 | Perc | IntroC | SENT (section setup) | **EXACT** | #188 Standard Kit 1 (PANEL, DRUMKIT) |
| 8 / 9 | 127 | 0 | 85 / 86 | Perc | IntroC | OVERRIDDEN at tick 0 (not sent) | **UNSUPPORTED** | NOT in the list. E343 drum kits: 127/0/1 Standard Kit 1, 127/0/2 Standard Kit 2, 127/0/9 Room Kit, 127/0/17 Rock Kit, 127/0/25 Electronic Kit, 127/0/26 Analog Kit, 127/0/113 Dance Kit, 127/0/33 Jazz Kit, 127/0/41 Brush Kit, 127/0/49 Symphony Kit |
| 9 / 10 | 127 | 0 | 1 / 2 | CC_Crash, Drums | 15 sections | SENT (section setup) | **EXACT** | #189 Standard Kit 2 (PANEL, DRUMKIT) |
| 9 / 10 | 127 | 0 | 83 / 84 | CC_Crash | 12 sections | OVERRIDDEN at tick 0 (not sent) | **UNSUPPORTED** | NOT in the list. E343 drum kits: 127/0/1 Standard Kit 1, 127/0/2 Standard Kit 2, 127/0/9 Room Kit, 127/0/17 Rock Kit, 127/0/25 Electronic Kit, 127/0/26 Analog Kit, 127/0/113 Dance Kit, 127/0/33 Jazz Kit, 127/0/41 Brush Kit, 127/0/49 Symphony Kit |
| 9 / 10 | 127 | 0 | 85 / 86 | Drums | 15 sections | OVERRIDDEN at tick 0 (not sent) | **UNSUPPORTED** | NOT in the list. E343 drum kits: 127/0/1 Standard Kit 1, 127/0/2 Standard Kit 2, 127/0/9 Room Kit, 127/0/17 Rock Kit, 127/0/25 Electronic Kit, 127/0/26 Analog Kit, 127/0/113 Dance Kit, 127/0/33 Jazz Kit, 127/0/41 Brush Kit, 127/0/49 Symphony Kit |
| 10 / 11 | 0 | 112 | 33 / 34 | Bass | 15 sections | SENT (section setup) | **EXACT** | #45 Finger Bass (PANEL, VOICE) |
| 11 / 12 | 0 | 112 | 25 / 26 | Gt, Gt Root | 14 sections | SENT (section setup) | **EXACT** | #35 Folk Guitar (PANEL, VOICE) |
| 12 / 13 | 0 | 113 | 0 / 1 | Pno, Pno Root | 13 sections | SENT (section setup) | **FALLBACK_CANDIDATE** | same MSB+Program, other LSB: #1 Grand Piano 0/112/1; #206 Grand Piano 0/0/1; #207 Grand Piano KSP 0/1/1; #208 Piano Strings 0/40/1; #209 Dream 0/41/1 |
| 13 / 14 | 0 | 113 | 49 / 50 | Str | 15 sections | OVERRIDDEN at tick 0 (not sent) | **EXACT** | #57 Slow Strings (PANEL, VOICE) |
| 13 / 14 | 0 | 116 | 49 / 50 | Str, Str Root | 15 sections | SENT (section setup) | **FALLBACK_CANDIDATE** | same MSB+Program, other LSB: #55 Chamber Strings 0/112/50; #56 Marcato Strings 0/115/50; #57 Slow Strings 0/113/50; #349 Strings 2 0/0/50; #350 Stereo Slow Strings 0/3/50; #351 Legato Strings 0/8/50; #352 Warm Strings 0/40/50; #353 Kingdom 0/41/50 |
| 13 / 14 | 0 | 117 | 49 / 50 | Str, Str Root | 15 sections | OVERRIDDEN at tick 0 (not sent) | **FALLBACK_CANDIDATE** | same MSB+Program, other LSB: #55 Chamber Strings 0/112/50; #56 Marcato Strings 0/115/50; #57 Slow Strings 0/113/50; #349 Strings 2 0/0/50; #350 Stereo Slow Strings 0/3/50; #351 Legato Strings 0/8/50; #352 Warm Strings 0/40/50; #353 Kingdom 0/41/50 |
| 14 / 15 | 0 | 112 | 60 / 61 | FrHn | 14 sections | OVERRIDDEN at tick 0 (not sent) | **EXACT** | #84 French Horn (PANEL, VOICE) |
| 14 / 15 | 0 | 115 | 60 / 61 | FrHn | 14 sections | SENT (section setup) | **FALLBACK_CANDIDATE** | same MSB+Program, other LSB: #84 French Horn 0/112/61; #375 French Horn 0/0/61; #376 French Horn Solo 0/6/61; #377 French Horn 2 0/32/61; #378 Horn Orchestra 0/37/61 |

Drum notes on dst ch 9 (1-based) with request 127/0/PC0 (list 1) -> kit #188 Standard Kit 1:
  49=Crash Cymbal 1 x47
  notes with NO sound in this kit: none

Drum notes on dst ch 10 (1-based) with request 127/0/PC1 (list 2) -> kit #189 Standard Kit 2:
  25=Brush Tap (std1) x42, 26=Brush Swirl (std1) x29, 27=Brush Slap (std1) x18, 28=Brush Tap Swirl (std1) x2, 35=Bass Drum Hard (std1) x46, 40=Snare H Hard 2 x30, 44=Hi-Hat Pedal (std1) x52, 45=Low Tom (std1) x10, 46=Hi-Hat Open (std1) x6, 47=Mid Tom L (std1) x8, 49=Crash Cymbal 1 (std1) x8, 51=Ride Cymbal 1 (std1) x7, 57=Crash Cymbal 2 (std1) x58, 59=Ride Cymbal 2 (std1) x60
  notes with NO sound in this kit: none

