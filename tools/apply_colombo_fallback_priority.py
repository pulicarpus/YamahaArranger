from pathlib import Path

p = Path("app/src/main/java/com/yourapp/ui/MainViewModel.kt")
s = p.read_text(encoding="utf-8")
old = '''    private fun fallbackMelodyScore(name: String): Int {
        val n = name.lowercase()
        return when {
            n.contains("yamaha") && n.contains("tyros") -> 90
            n.contains("tyros") -> 85
            n.contains("colombo") -> 80
            n.contains("timbres") -> 70
            n.contains("merlin") -> 50
            n.contains("cp80") -> 40
            else -> 0
        }
    }'''
new = '''    private fun fallbackMelodyScore(name: String): Int {
        val n = name.lowercase()
        return when {
            // ColomboGMGS2_BM is the broad BASSMIDI/GM2/GS compatibility
            // layer (892 presets in the audited user inventory). Keep the
            // Yamaha SX700/SX900 font as PRIMARY, but prefer Colombo for the
            // single secondary slot so the runtime can exercise broad family
            // fallback instead of stopping at the much smaller Tyros set.
            n.contains("colombo") && (n.contains("bm") || n.contains("bassmidi")) -> 100
            n.contains("colombo") -> 98
            n.contains("yamaha") && n.contains("tyros") -> 90
            n.contains("tyros") -> 85
            n.contains("timbres") -> 70
            n.contains("merlin") -> 50
            n.contains("cp80") -> 40
            else -> 0
        }
    }'''
if old not in s:
    raise SystemExit("expected fallbackMelodyScore block not found; refusing to modify source")
s = s.replace(old, new, 1)
p.write_text(s, encoding="utf-8")
print("Applied Colombo-first secondary fallback priority")
