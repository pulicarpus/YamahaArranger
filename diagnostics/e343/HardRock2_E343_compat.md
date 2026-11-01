## HardRock2.S578.prs

| Dst ch (0-based / 1-based) | Bank MSB | LSB | PC on the wire (0-based) / list Program (1-based) | Style voice name(s) | Sections | Sent by the app? | E343 status | E343 list entry / nearest |
|---|---|---|---|---|---|---|---|---|
| 8 / 9 | 127 | 0 | 0 / 1 | perc | 15 sections | SENT (section setup) | **EXACT** | #188 Standard Kit 1 (PANEL, DRUMKIT) |
| 8 / 9 | 127 | 0 | 83 / 84 | perc | 15 sections | OVERRIDDEN at tick 0 (not sent) | **UNSUPPORTED** | NOT in the list. E343 drum kits: 127/0/1 Standard Kit 1, 127/0/2 Standard Kit 2, 127/0/9 Room Kit, 127/0/17 Rock Kit, 127/0/25 Electronic Kit, 127/0/26 Analog Kit, 127/0/113 Dance Kit, 127/0/33 Jazz Kit, 127/0/41 Brush Kit, 127/0/49 Symphony Kit |
| 9 / 10 | 127 | 0 | 83 / 84 | CC_Crash, drums | 15 sections | OVERRIDDEN at tick 0 (not sent) | **UNSUPPORTED** | NOT in the list. E343 drum kits: 127/0/1 Standard Kit 1, 127/0/2 Standard Kit 2, 127/0/9 Room Kit, 127/0/17 Rock Kit, 127/0/25 Electronic Kit, 127/0/26 Analog Kit, 127/0/113 Dance Kit, 127/0/33 Jazz Kit, 127/0/41 Brush Kit, 127/0/49 Symphony Kit |
| 9 / 10 | 127 | 0 | 86 / 87 | CC_Crash, drums | 15 sections | SENT (section setup) | **UNSUPPORTED** | NOT in the list. E343 drum kits: 127/0/1 Standard Kit 1, 127/0/2 Standard Kit 2, 127/0/9 Room Kit, 127/0/17 Rock Kit, 127/0/25 Electronic Kit, 127/0/26 Analog Kit, 127/0/113 Dance Kit, 127/0/33 Jazz Kit, 127/0/41 Brush Kit, 127/0/49 Symphony Kit |
| 10 / 11 | 0 | 112 | 33 / 34 | Bass | 15 sections | OVERRIDDEN at tick 0 (not sent) | **EXACT** | #45 Finger Bass (PANEL, VOICE) |
| 10 / 11 | 0 | 114 | 33 / 34 | Bass | 15 sections | SENT (section setup) | **FALLBACK_CANDIDATE** | same MSB+Program, other LSB: #44 Dynamic Electric Bass 0/116/34; #45 Finger Bass 0/112/34; #305 Finger Bass 0/0/34; #306 Finger Dark 0/18/34; #307 Bass & Distorted Electric Guitar 0/40/34; #308 Finger Slap Bass 0/43/34; #309 Finger Bass 2 0/45/34; #310 Modulated Bass 0/65/34 |
| 11 / 12 | 0 | 113 | 30 / 31 | DistGt | 15 sections | OVERRIDDEN at tick 0 (not sent) | **FALLBACK_CANDIDATE** | same MSB+Program, other LSB: #43 Distortion Guitar 0/112/31; #296 Distortion Guitar 0/0/31; #297 Feedback Guitar 0/40/31; #298 Feedback Guitar 2 0/41/31 |
| 11 / 12 | 0 | 115 | 30 / 31 | DistGt | 15 sections | SENT (section setup) | **FALLBACK_CANDIDATE** | same MSB+Program, other LSB: #43 Distortion Guitar 0/112/31; #296 Distortion Guitar 0/0/31; #297 Feedback Guitar 0/40/31; #298 Feedback Guitar 2 0/41/31 |
| 11 / 12 | 0 | 123 | 27 / 28 |  | FillAA | UNPROVEN: this part has no CASM entry for its source ch 12; the setup path (applyVoicesFromCasm) only sends parts named by CASM | **FALLBACK_CANDIDATE** | same MSB+Program, other LSB: #37 60s Clean Guitar 0/117/28; #39 Clean Guitar 0/112/28; #288 Clean Guitar 0/0/28; #289 Chorus Guitar 0/32/28 |
| 13 / 14 | 0 | 115 | 18 / 19 | OrgRoot, Organ | 15 sections | SENT (section setup) | **FALLBACK_CANDIDATE** | same MSB+Program, other LSB: #13 Cool! Organ 0/118/19; #14 Cool! Rotor Organ 0/117/19; #17 Rock Organ 0/112/19; #18 Purple Organ 0/114/19; #21 Theater Organ 0/127/19; #260 Rock Organ 0/0/19; #261 Rotary Organ 0/64/19; #262 Slow Rotary 0/65/19; #263 Fast Rotary 0/66/19 |
| 14 / 15 | 0 | 123 | 27 / 28 | RGuit | 7 sections | OVERRIDDEN at tick 0 (not sent) | **FALLBACK_CANDIDATE** | same MSB+Program, other LSB: #37 60s Clean Guitar 0/117/28; #39 Clean Guitar 0/112/28; #288 Clean Guitar 0/0/28; #289 Chorus Guitar 0/32/28 |
| 14 / 15 | 0 | 125 | 27 / 28 | RGuit | 7 sections | SENT (section setup) | **FALLBACK_CANDIDATE** | same MSB+Program, other LSB: #37 60s Clean Guitar 0/117/28; #39 Clean Guitar 0/112/28; #288 Clean Guitar 0/0/28; #289 Chorus Guitar 0/32/28 |

Drum notes on dst ch 9 (1-based) with request 127/0/PC0 (list 1) -> kit #188 Standard Kit 1:
  54=Tambourine x208
  notes with NO sound in this kit: none

Drum notes on dst ch 10 (1-based) with request 127/0/PC86 (list 87) -> no E343 kit with this number (UNSUPPORTED):
  as Standard Kit 1 would play them (only IF the E343 fell back to it; not documented): 36=Bass Drum x99, 38=Snare M x64, 41=Floor Tom L x25, 42=Hi-Hat Closed x101, 43=Floor Tom H x16, 45=Low Tom x17, 46=Hi-Hat Open x48, 47=Mid Tom L x18, 48=Mid Tom H x9, 49=Crash Cymbal 1 x15, 50=High Tom x3, 52=Chinese Cymbal x1, 55=Splash Cymbal x3, 57=Crash Cymbal 2 x13

