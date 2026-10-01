#pragma once
#include <string>
#include <vector>
namespace managed_sf2_audition {
struct Result { std::vector<unsigned char> wav; std::string evidence; };
// Has no player instance, production stream/font handle or arranger state argument.
Result render(const std::string& privateSnapshot, int bank, int pc, int key, int velocity);
}
