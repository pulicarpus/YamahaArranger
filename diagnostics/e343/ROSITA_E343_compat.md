## ROSITA.STY

| Dst ch (0-based / 1-based) | Bank MSB | LSB | PC on the wire (0-based) / list Program (1-based) | Style voice name(s) | Sections | Sent by the app? | E343 status | E343 list entry / nearest |
|---|---|---|---|---|---|---|---|---|
| 8 / 9 | 127 | 0 | 0 / 1 | Rhythm1 | MainA, MainB, MainC, MainD, FillAA | OVERRIDDEN at tick 0 (not sent); SENT (section setup); UNPROVEN: this part has no CASM entry for its source ch 9; the setup path (applyVoicesFromCasm) only sends parts named by CASM | **EXACT** | #188 Standard Kit 1 (PANEL, DRUMKIT) |
| 8 / 9 | 127 | 0 | 16 / 17 | Rhythm1 | MainB | SENT (section setup) | **EXACT** | #191 Rock Kit (PANEL, DRUMKIT) |
| 9 / 10 | 127 | 0 | 0 / 1 | Rhythm2 | 10 sections | OVERRIDDEN at tick 0 (not sent); SENT (section setup) | **EXACT** | #188 Standard Kit 1 (PANEL, DRUMKIT) |
| 9 / 10 | 127 | 0 | 8 / 9 | Rhythm2 | MainA | SENT (section setup) | **EXACT** | #190 Room Kit (PANEL, DRUMKIT) |
| 10 / 11 | 0 | 112 | 33 / 34 | Bass | 9 sections | SENT (section setup) | **EXACT** | #45 Finger Bass (PANEL, VOICE) |
| 11 / 12 | 0 | 112 | 3 / 4 | Chord1 | MainD | SENT (section setup) | **EXACT** | #4 Honky-tonk Piano (PANEL, VOICE) |
| 11 / 12 | 0 | 112 | 27 / 28 | Chord1 | 6 sections | SENT (section setup) | **EXACT** | #39 Clean Guitar (PANEL, VOICE) |
| 11 / 12 | 0 | 112 | 48 / 49 | Chord1 | FillDD | SENT (section setup) | **EXACT** | #54 String Ensemble (PANEL, VOICE) |
| 12 / 13 | 0 | 112 | 3 / 4 | Chord2 | MainB, MainC, FillBB, FillCC | SENT (section setup) | **EXACT** | #4 Honky-tonk Piano (PANEL, VOICE) |
| 12 / 13 | 0 | 112 | 27 / 28 | Chord2 | MainA, MainD, FillAA | SENT (section setup) | **EXACT** | #39 Clean Guitar (PANEL, VOICE) |
| 13 / 14 | 0 | 112 | 48 / 49 | Pad | 7 sections | SENT (section setup) | **EXACT** | #54 String Ensemble (PANEL, VOICE) |
| 13 / 14 | 0 | 112 | 52 / 53 | Pad | FillDD | SENT (section setup) | **EXACT** | #66 Choir (PANEL, VOICE) |
| 13 / 14 | 0 | 112 | 54 / 55 | Pad | MainD | SENT (section setup) | **EXACT** | #68 Air Choir (PANEL, VOICE) |
| 14 / 15 | 0 | 112 | 27 / 28 | Phrase1 | MainA, FillAA | SENT (section setup) | **EXACT** | #39 Clean Guitar (PANEL, VOICE) |
| 15 / 16 | 0 | 112 | 33 / 34 | Phrase2 | MainC, FillCC, EndingB | SENT (section setup) | **EXACT** | #45 Finger Bass (PANEL, VOICE) |

Drum notes on dst ch 9 (1-based) with request 127/0/PC0 (list 1) -> kit #188 Standard Kit 1:
  34=Open Rim Shot x6, 53=Ride Cymbal Cup x20
  notes with NO sound in this kit: none

Drum notes on dst ch 9 (1-based) with request 127/0/PC16 (list 17) -> kit #191 Rock Kit:
  34=Open Rim Shot (std1) x2, 53=Ride Cymbal Cup (std1) x8
  notes with NO sound in this kit: none

Drum notes on dst ch 10 (1-based) with request 127/0/PC0 (list 1) -> kit #188 Standard Kit 1:
  33=Bass Drum Soft x16, 34=Open Rim Shot x17, 35=Bass Drum Hard x7, 36=Bass Drum x11, 37=Side Stick x10, 42=Hi-Hat Closed x58, 43=Floor Tom H x4, 45=Low Tom x1, 46=Hi-Hat Open x3, 47=Mid Tom L x3, 48=Mid Tom H x6, 50=High Tom x3, 53=Ride Cymbal Cup x3, 54=Tambourine x2, 55=Splash Cymbal x7, 57=Crash Cymbal 2 x2, 60=Bongo H x8, 62=Conga H Mute x24, 63=Conga H Open x4, 64=Conga L x10
  notes with NO sound in this kit: none

Drum notes on dst ch 10 (1-based) with request 127/0/PC8 (list 9) -> kit #190 Room Kit:
  33=Bass Drum Soft (std1) x8, 37=Side Stick (std1) x8, 42=Hi-Hat Closed (std1) x18, 60=Bongo H (std1) x4, 62=Conga H Mute (std1) x4
  notes with NO sound in this kit: none

