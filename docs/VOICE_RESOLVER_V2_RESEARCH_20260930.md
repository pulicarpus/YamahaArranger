# Voice Resolver V2 — Yamaha Bank Research — 2026-09-30

## Purpose

This note records external Yamaha documentation findings that must be used alongside the seven-SF2 candidate audit. It is reference material only; it does not change playback code.

## Yamaha PSR-SX920 evidence

Yamaha's official PSR-SX920 download page provides a PSR-SX920/PSR-SX720 Data List and Reference Manual. The Reference Manual states that the Voice Number display can show the Voice bank and number so users can check the required MIDI bank-select MSB/LSB and Program Change values for external MIDI devices. It also states that the displayed voice numbers start at 1 while actual MIDI Program Change numbering starts at 0.

Therefore the resolver/audit must preserve all three values separately:

- Bank Select MSB
- Bank Select LSB
- MIDI Program Change (0-based)

Do not convert a Yamaha displayed Voice Number directly into MIDI PC without the documented -1 adjustment.

## Voice inventory implication

Yamaha's current PSR-SX920 specification lists a large preset Voice inventory, including S.Articulation, MegaVoice, Sweet!, Cool!, Live!, OrganFlutes and XG voices, plus Drum/SFX kits. This explains why a generic GM SF2 cannot be expected to provide one-to-one waveform fidelity for every Yamaha factory style voice request.

The resolver should therefore distinguish:

1. exact bank/program availability;
2. same Yamaha MSB/program availability;
3. known Yamaha variation-family compatibility;
4. semantic family compatibility;
5. same-PC fallback only when family-safe;
6. conservative final fallback.

## Important limitation

The Yamaha documentation confirms how bank/program information is represented and that the SX920 has a much larger Voice inventory than a basic GM set. It does not by itself prove that two voices with similar names use identical samples. Sample identity must come from the available SF2 inventory or controlled audio/runtime evidence.

## Engineering consequence for MSB 104

The existing corpus audit reports MSB 104 as the largest unresolved family in the seven-SF2 inventory: 282 unique requests / 2,342 entries without same-MSB+Program coverage.

MSB 104 must therefore be handled as a Yamaha-family mapping problem, not as a reason to force same-PC matching.

## Candidate-matrix requirement

`tools/voice_candidate_matrix.py` was added to generate a repeatable matrix from:

- the 569-style voice request CSV;
- any number of SF2 files;
- SF2 `pdta/phdr` metadata.

The tool reports exact, same-MSB, semantic-family and same-PC candidate coverage. It does not load sample data and does not modify engine code.

## Sources

- Yamaha PSR-SX920 official downloads/data-list page: https://usa.yamaha.com/products/musical_instruments/keyboards/arranger_workstations/psr-sx920/downloads.html
- Yamaha PSR-SX920/SX720 Reference Manual: https://data.yamaha.com/files/download/other_assets/5/2328055/PSR-SX920_reference_manual_En_B0.pdf
- Yamaha Indonesia PSR-SX920 specifications: https://id.yamaha.com/id/musical-instruments/keyboards/products/arranger-workstations/psr-sx920/specs.html
