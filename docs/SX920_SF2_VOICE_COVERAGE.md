# SX920 Factory Styles × Multi-SF2 Voice Coverage

- Styles: **569**
- Voice setup entries: **7612**
- Unique bank MSB:LSB + PC requests: **578**
- Managed SF2 files inspected: **7**
- Total inspected presets: **1624**

## Coverage rule used
- **Same bank MSB + PC**: requested Yamaha MSB is present in an SF2 with the same program. For Yamaha rhythm MSB 126/127, the analysis also tests SF2 drum bank 128, because the inspector/resolver currently represents Yamaha drum banks through bank 128.
- **Same PC only**: program exists somewhere in an SF2, but not under the requested/mapped MSB. This is *not* treated as a safe automatic match.

## Result

- Same bank/MSB + PC: **250 / 578** unique requests
- Same PC only: **328 / 578** unique requests
- No matching PC anywhere: **0 / 578** unique requests

At the individual voice-entry level:
- Same bank/MSB + PC: **4,512 / 7,612**
- Same PC exists somewhere: **7,612 / 7,612**

The entry-level numbers are intentionally not used as a resolver score because repeated voices across styles/channels would overweight common requests.

## By requested MSB

| MSB | Entries | Unique requests | Same bank/MSB + PC entries |
|---:|---:|---:|---:|
| 0 | 570 | 149 | 570 |
| 8 | 3664 | 97 | 3268 |
| 64 | 1 | 1 | 1 |
| 104 | 2342 | 282 | 0 |
| 126 | 312 | 17 | 252 |
| 127 | 723 | 32 | 410 |

## Inventory

| SF2 | Presets |
|---|---:|
| ColomboGMGS2_BM.sf2 | 892 |
| DRUMKIT YAMAHA PSR-SX700 & SX900 PRIME.sf2 | 21 |
| MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2 | 93 |
| merlin_GMpro(v3.15).sf2 | 136 |
| Timbres Of Heaven GM_GS_XG_SFX V 3.4 Final.sf2 | 339 |
| yamaha tyros 4_just_t4_fixed.sf2 | 142 |
| Yamaha_PSR-SX700_CP80.sf2 | 1 |
| **Total** | **1624** |

## Interpretation

1. The current same-PC fallback is confirmed to be unsafe as a general resolver rule. Every unique style request has some same-PC candidate somewhere, but only 250/578 preserve the requested bank family under this audit rule.
2. **MSB 104** is the largest unresolved family: 282 unique requests and 2,342 entries have no same-MSB+PC coverage in the seven inspected SF2s. These require Yamaha/Tyros semantic/variation mapping rather than blind same-PC fallback.
3. **MSB 126/127** are rhythm/SFX-related in the Yamaha style corpus and need explicit drum-role handling. They must not compete with melodic presets.
4. The 21-preset Yamaha drum SF2 is represented at SF2 bank 128 in the inspector inventory. Yamaha rhythm requests therefore need a deliberate 126/127 → drum-bank-128 mapping step rather than literal raw-bank equality.
5. This audit does **not** prove that a same-PC candidate sounds musically equivalent. It only establishes inventory coverage. Final selection still needs role + Yamaha bank family + voice-name/semantic matching.
6. This report does not change playback code or select a final source SF2.

## Next engineering step

Build a resolver candidate matrix from these 578 unique requests:

**role → Yamaha bank family → exact/variation candidate → semantic voice-name family → GM/XG fallback**

Only after that matrix is reviewed should Voice Resolver V2 be changed.
