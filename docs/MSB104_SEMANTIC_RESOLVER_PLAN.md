# MSB 104 Semantic Resolver Plan — 2026-09-30

Scope: 282 unique style voice requests with Yamaha MSB=104 in the current 578-request candidate matrix.

## Evidence
The seven-SF2 candidate matrix shows that MSB=104 requests have no exact bank match in the current inventory, while same-Program candidates often include both musically relevant and clearly unrelated presets. Therefore same-PC alone is unsafe.

Yamaha's PSR-SX900/SX700 Data List identifies voices by MSB, LSB and PC and lists MSB=104 as a real Yamaha voice family used for many variation voices.

## Resolver order
1. Exact MSB+LSB+PC.
2. Same MSB+PC.
3. Same PC candidates scored by semantic/category evidence.
4. Prefer a Yamaha melody SF2 candidate when its name/category is a positive match.
5. Otherwise prefer Tyros 4, Timbres, Merlin, or Colombo only when semantic evidence is positive.
6. If semantic evidence is absent or conflicting, mark the candidate ambiguous and use conservative fallback instead of forcing a same-PC voice.

## Critical safety rules
- Never let an Organ candidate win for a Bass/Guitar request merely because Program matches.
- Never let SFX/noise/telephone/drum candidates win a melodic request merely because Program matches.
- Do not collapse different Yamaha LSB variations into one global program mapping.
- Preserve the native source-bank/source-program/source-name audit logs.
- Do not change CASM, scheduler, transition, or drum routing as part of this resolver work.

## Current audit result
Semantic scoring of the 282 MSB=104 requests produced:
- score 9: 19 requests
- score 6: 52 requests
- score 3: 97 requests
- score 0: 114 requests

Score 0 means the current candidate names do not provide enough positive semantic evidence for an automatic mapping. These entries must not be blindly remapped.

## Next implementation gate
Before modifying native resolver selection, validate the candidate matrix against actual Yamaha Voice names from the official PSR-SX900/SX700 Data List and the style request voice names. Only mappings with positive category/name evidence should become automatic resolver rules.
