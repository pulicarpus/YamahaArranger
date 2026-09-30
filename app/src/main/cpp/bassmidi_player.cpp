    auto gmCategory = [](int p) -> int {
        // GM Program Change uses zero-based PC values here.
        // 0..7   Piano
        // 8..15  Chromatic Percussion
        // 16..23 Organ
        // 24..31 Guitar
        // 32..39 Bass
        // 40..47 Strings
        // 48..55 Ensemble
        // 56..63 Brass
        // 64..71 Reed
        // 72..79 Pipe
        // 80..87 Synth Lead
        // 88..95 Synth Pad
        // 96..103 Synth Effects
        // 104..111 Ethnic
        // 112..119 Percussive
        // 120..127 Sound Effects
        if (p <= 7) return 4;
        if (p <= 15) return 0;
        if (p <= 23) return 5;
        if (p <= 31) return 3;
        if (p <= 39) return 2;
        if (p <= 47) return 1;
        if (p <= 55) return 1;
        if (p <= 63) return 7;
        if (p <= 71) return 8;
        if (p <= 79) return 9;
        if (p <= 87) return 12;
        if (p <= 95) return 11;
        if (p <= 103) return 12;
        if (p <= 111) return 10;
        return 0;
    };

    const int wantedCategory =
        category(lower) != 0 ? category(lower) : gmCategory(requestedProgram);

    int bestScore = -1;
    const MelodicPresetEntry* best = nullptr;

    for (const auto& p : melodyFallbackPresetCache_) {
        std::string pn = p.name;
        std::transform(pn.begin(), pn.end(), pn.begin(),
                       [](unsigned char ch) { return static_cast<char>(std::tolower(ch)); });

        const int candidateCategory = category(pn);
        int score = 0;

        // Category is the strongest semantic signal.
        if (wantedCategory != 0 && candidateCategory == wantedCategory) {
            score += 1000;
        } else if (wantedCategory != 0 && candidateCategory != 0) {
            // Do not let a same-bank/same-program candidate from an unrelated
            // family beat a genuinely compatible voice.
            score -= 500;
        }

        // Keep bank proximity useful, but deliberately weaker than semantic
        // identity so a wrong-family preset cannot win merely because it is
        // in the requested source bank.
        if (p.bank == requestedSourceBank) score += 80;

        // Exact/partial voice-name evidence is stronger than raw PC distance.
        if (!lower.empty() && pn == lower) score += 1500;
        if (!lower.empty() && pn.find(lower) != std::string::npos) score += 350;

        // Program proximity is only a tie-breaker after semantic identity.
        score += std::max(0, 64 - std::abs(p.program - requestedProgram));

        if (score > bestScore) {
            bestScore = score;
            best = &p;
        }
    }

    if (best && bestScore >= 1000) {
        sourceBank = best->bank;
        sourceProgram = best->program;
        matchedName = best->name;
        LOGI("VOICE RESOLVE semantic requestedBank=%d prog=%d name='%s' -> bank=%d prog=%d '%s' score=%d category=%d",
             requestedBank, requestedProgram, voiceName.c_str(),
             sourceBank, sourceProgram, matchedName.c_str(),
             bestScore, wantedCategory);
        return true;
    }

    // 4) Same-PC fallback is allowed only inside the requested semantic
    // family. This prevents a Yamaha variation PC from selecting an unrelated
    // instrument merely because the number happens to match.
    for (const auto& p : melodyFallbackPresetCache_) {
        if (p.program != requestedProgram) continue;
        std::string pn = p.name;
        std::transform(pn.begin(), pn.end(), pn.begin(),
                       [](unsigned char ch) { return static_cast<char>(std::tolower(ch)); });
        if (wantedCategory != 0 && category(pn) != wantedCategory) continue;

        sourceBank = p.bank;
        sourceProgram = p.program;
        matchedName = p.name;
        LOGI("VOICE RESOLVE fallback category-safe PC requestedBank=%d prog=%d name='%s' -> bank=%d prog=%d '%s' category=%d",
             requestedBank, requestedProgram, voiceName.c_str(),
             sourceBank, sourceProgram, matchedName.c_str(), wantedCategory);
        return true;
    }

    // 5) Final fallback is conservative: only a piano-family Program 0.
    best = nullptr;
    for (const auto& p : melodyFallbackPresetCache_) {
        std::string pn = p.name;
        std::transform(pn.begin(), pn.end(), pn.begin(),
                       [](unsigned char ch) { return static_cast<char>(std::tolower(ch)); });
        if (p.program == 0 && category(pn) == 4) {
            if (p.bank == requestedSourceBank) {
                best = &p;
                break;
            }
            if (!best) best = &p;
        }
    }
    if (!best) {
        LOGI("VOICE RESOLVE no safe fallback candidate requestedBank=%d prog=%d name='%s'",
             requestedBank, requestedProgram, voiceName.c_str());
        return false;
    }

    sourceBank = best->bank;
    sourceProgram = best->program;
    matchedName = best->name;
    LOGI("VOICE RESOLVE fallback conservative piano requestedBank=%d prog=%d name='%s' -> bank=%d prog=%d '%s'",
         requestedBank, requestedProgram, voiceName.c_str(),
         sourceBank, sourceProgram, matchedName.c_str());
    return true;
}