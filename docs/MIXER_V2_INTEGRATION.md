# E343 + Compact Mixer V2 integration

Branch: `experiment/e343-compact-mixer-v2`.
Parents: E343 `9915a491824e4f61871daa0f621a534ac0f25d0b` and compact UI `4ab3b81a67f812437ca9307133e31ee61a58cf54`; common ancestor `2704d0bb8e2aee214af9e36ebccf2f7c2a3958af`.

The E343 patch has been accepted on real hardware by the user. Its ViewModel, MIDI router and recorder tests are preserved byte-for-byte. Automatic output activation/reconnect must not send keyboard Bank/PC; explicit voice selection still does. CC11, channel volume balance, notes, locks and other MIDI behavior remain unchanged.

Compact V2: 48–56dp strips, subtle tinted outlines, dark console surfaces, 4dp fader rails and 28x16dp caps. Fader input/hit area remains full strip width with its unchanged 0–127 semantics and gesture mapping. RIGHT1/2/3 and LEFT are blue, STYLE and style channels green, MASTER amber. Six fixed strips remain separate from the style horizontal scroll. RIGHT1/2/3 and MASTER labels are written explicitly; standard style names use unique short labels with full names available in DETAIL. Labels have no ellipsis. MUTE/SOLO remain separate full-width 48dp buttons. VOICE/PAN/EXP/REV/CHO remain accessible through DETAIL. The unused 144dp header above bus faders is replaced by a single 48dp DETAIL button. No artificial meters are present.

Only mixer UI/layout production files differ from E343 checkpoint. RELEASE, RIGHT OFF, CASM, scheduler, Android/BASSMIDI engine and MIDI routing are unchanged. Main, READY, style checkpoint and SF2 master branch are not modified.

Required validation: existing JVM contracts plus landscape geometry/label tests, native recorder, strict real Linux BASSMIDI PCM (synthetic SF2), frozen checkpoint and integration isolation guard, both ABI build, APK signature/native library provenance. Original isolated-branch guards retain their original content and SHAs; this integration uses an additive guard with a pinned E343 base because the old guards intentionally reject combining UI with MIDI changes. The new guard also checks those old files are intact.

Host layout tests cover 640–1280dp landscape widths and 360–800dp heights. Static action contracts check fixed/scroll separation and DETAIL/voice/mute/solo volume bindings; these are not Compose instrumentation or physical Redmi Pad evidence. Android UI rendering, touch/large-font behavior, and confirmation that E343 audio remains normal with this combined APK require device testing.

Delivery is one integration APK containing arm64-v8a and armeabi-v7a. Do not produce separate UI/audio candidates for this integration.
