# YamahaArranger — Unified Arranger Engine Blueprint

Authoritative baseline:
fix/yamaha-panel-sustain-clean
f85ba7641fd73c34cdb57a4fc9984a05ac5d1b93

## Target runtime

Yamaha STY / PRS / SFF / SFF GE
-> SMF + Yamaha parser
-> Section model
-> Part model
-> CASM policy
-> Style Transition Engine
-> continuous musical clock
-> Note ownership + CASM transformation
-> Voice Resolver
-> BASSMIDI
-> Oboe
-> Android audio

## Transition model

A future transition object should explicitly carry:

current section
requested section
switch precision
fill direction
fill section
successor
start tick
one-shot or loop

Rules:

- Main -> Main may become Main -> Fill -> Main.
- Fill is one-shot.
- Intro is one-shot.
- Ending is one-shot.
- Section changes never require stop/restart.
- Section changes never call global all-notes-off.
- Only outgoing style-owned notes are reconciled.
- The musical clock never resets during a seamless transition.

GigLad documents section successors and switch precision; vArranger documents Auto Fill and live section control; One Man Band documents glitch-free style switching and live assignable controls. urlGigLad documentationhttps://www.deltarray.com/documentation/giglad/ urlvArranger featureshttps://www.varranger.com/features/ urlOne Man Band featureshttps://www.1manband.nl/features.htm

## Yamaha section vocabulary

- Intro I / II / III
- Main A / B / C / D
- Fill In A / B / C / D
- Break
- Ending I / II / III

Modern Yamaha workflows also distinguish fill transitions. The parser must not throw away direction information when present. citeturn1search24turn1search26

## Note ownership

Every sounding style note should eventually have a unique instance identity:

section generation
source track
source channel
source event index

The ownership record should store:

source note
destination channel
output note
velocity
CASM policy

This prevents repeated identical notes from overwriting each other's NOTE_OFF state.

## CASM boundary

CASM owns:

source channel
destination channel
source chord
NTR
NTT
High Key
Note Limits
RTR
Bass On
chord mute rules

CASM does not decide which SF2 preset is best.

## Voice Resolver boundary

Voice identity:

source channel
bank MSB
bank LSB
program
voice name
semantic category

Resolution order:

1. exact Yamaha bank/program;
2. explicit user/style mapping;
3. strong semantic name/category;
4. same-family category;
5. semantically compatible numeric fallback;
6. final melodic Piano fallback.

Percussion never falls through into a melodic preset.

The existing MIDI Voyager research is the reference for this separation.

## Realtime audio rules

The Oboe callback must never:

- parse files;
- scan SF2 headers;
- allocate large objects;
- wait on a UI/file mutex;
- synchronously load samples;
- perform expensive preset resolution.

Voice changes should be prepared before the first note where possible. Non-blocking preload is preferred.

## Yamaha E343 performance controls

The PSR-E343 MIDI reference identifies CC64 as Sustain and CC72 as Release Time, along with bank select, volume, pan, expression, effect depth and Program Change. urlYamaha PSR-E343 MIDI Referencehttps://usa.yamaha.com/files/download/other_assets/4/329464/psre343_en_mr_a0.pdf

The current panel sustain ledger and CC72 release implementation remain the baseline.

## Regression matrix

| Test | Expected |
|---|---|
| Main A -> Main B | correct fill, no global NOTE_OFF |
| Main B -> Main D | directional fill when available |
| Main D -> Main A | no premature instrument stop |
| Intro -> Main | Intro exactly once |
| Fill -> Main | no audible pause |
| Ending -> Stop | Ending exactly once |
| 2/4 | correct bar boundary |
| 3/4 | correct bar boundary |
| 4/4 | correct bar boundary |
| 6/8 | correct bar boundary |
| repeated same note | independent NOTE_OFF |
| drum + melody | both audible |
| String variation | semantic String resolution |
| missing drum kit | percussion-safe fallback |
| SF2 reload | no stale routing |

## Definition of done

The arranger engine is stable when:

- transitions never require stop/restart;
- style transitions never call global all-notes-off;
- repeated notes never collapse into one ownership record;
- Intro/Ending one-shot behavior is deterministic;
- directional fills are preserved;
- CASM transformations are deterministic;
- voice resolution logs exact/fallback reason;
- BASSMIDI render path has no blocking UI/file mutex;
- 2/4, 3/4, 4/4 and 6/8 pass transition tests;
- E343 MIDI controls remain compatible.
