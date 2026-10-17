#!/usr/bin/env python3
"""Objective A/B analysis of the offline audio lab renders (float32 stereo WAV, 48 kHz).

Level and timbre are kept apart: peak/RMS are absolute (as rendered); centroid, high-frequency share, transient ratio and
the log-spectral distance (LSD) are level-invariant (spectra are normalised to equal energy before comparing).
A louder render is never reported as a better-sounding one.
"""
import argparse, csv, json, math, os, struct, sys
from pathlib import Path
import numpy as np

SR = 48000
EPS = 1e-12


def read_wav(path):
    b = Path(path).read_bytes()
    assert b[:4] == b'RIFF' and b[8:12] == b'WAVE'
    pos, fmt, data = 12, None, None
    while pos + 8 <= len(b):
        tag, size = b[pos:pos + 4], struct.unpack('<I', b[pos + 4:pos + 8])[0]
        body = b[pos + 8:pos + 8 + size]
        if tag == b'fmt ': fmt = struct.unpack('<HHIIHH', body[:16])
        elif tag == b'data': data = body
        pos += 8 + size + (size & 1)
    code, ch, rate, _, _, bits = fmt
    if code == 3 and bits == 32: x = np.frombuffer(data, dtype='<f4').astype(np.float64)
    elif code == 1 and bits == 16: x = np.frombuffer(data, dtype='<i2').astype(np.float64) / 32768.0
    else: raise ValueError('unsupported wav')
    return rate, x.reshape(-1, ch)


def write_wav16(path, rate, stereo):
    pcm = (np.clip(stereo, -1, 1) * 32767).astype('<i2')
    d = pcm.tobytes()
    hdr = b'RIFF' + struct.pack('<I', 36 + len(d)) + b'WAVEfmt ' + struct.pack('<IHHIIHH', 16, 1, 2, SR, SR * 4, 4, 16) + b'data' + struct.pack('<I', len(d))
    Path(path).write_bytes(hdr + d)


def db(x): return 20 * math.log10(max(x, EPS))


def rms(x): return float(np.sqrt(np.mean(np.square(x)))) if len(x) else 0.0


def seg(x, a_ms, b_ms): return x[int(SR * a_ms / 1000):int(SR * b_ms / 1000)]


def spectrum(mono, a_ms, b_ms):
    s = seg(mono, a_ms, b_ms)
    if len(s) < 256: return None, None
    w = np.hanning(len(s))
    p = np.abs(np.fft.rfft(s * w)) ** 2
    return np.fft.rfftfreq(len(s), 1.0 / SR), p


def centroid(f, p): return float((f * p).sum() / (p.sum() + EPS))


def hf_share(f, p, fc): return float(100.0 * p[f >= fc].sum() / (p.sum() + EPS))


def rolloff(f, p, frac=0.85):
    c = np.cumsum(p) / (p.sum() + EPS)
    return float(f[min(np.searchsorted(c, frac), len(f) - 1)])


def onset_metrics(stereo):
    env = np.abs(stereo).max(axis=1)
    hop = SR // 2000
    n = min(len(env), SR // 2) // hop
    w = np.sqrt(np.mean(env[:n * hop].reshape(n, hop) ** 2, axis=1))
    pk = w.max()
    if pk < 1e-6: return dict(first_ms=-1, m40=-1, m20=-1, m10=-1, peak_ms=-1, rise_10_90=-1)
    first = float(np.argmax(env > 1e-5) * 1000.0 / SR)
    t = lambda d: float(np.argmax(w >= pk * 10 ** (d / 20)) * 0.5)
    t10, t90 = t(-20), t(-0.915)  # 10 % and 90 % of linear peak
    return dict(first_ms=round(first, 2), m40=t(-40), m20=t(-20), m10=t(-10), peak_ms=float(np.argmax(w) * 0.5), rise_10_90=round(t90 - t10, 2))


def f0_drift_cents(mono, key):
    f0 = 440.0 * 2 ** ((key - 69) / 12.0)
    freqs = []
    for a in range(200, 1500, 100):
        s = seg(mono, a, a + 100)
        if len(s) < 2048: continue
        n = 1 << 18
        sp = np.abs(np.fft.rfft(s * np.hanning(len(s)), n))
        f = np.fft.rfftfreq(n, 1.0 / SR)
        m = (f > f0 * 0.96) & (f < f0 * 1.04)
        if not m.any(): continue
        i = np.argmax(np.where(m, sp, 0))
        if 0 < i < len(sp) - 1:  # parabolic peak
            a_, b_, c_ = np.log(sp[i - 1] + EPS), np.log(sp[i] + EPS), np.log(sp[i + 1] + EPS)
            d = 0.5 * (a_ - c_) / (a_ - 2 * b_ + c_ + EPS)
            freqs.append(f[i] + d * (f[1] - f[0]))
        else: freqs.append(f[i])
    if len(freqs) < 3: return float('nan')
    fr = np.array(freqs)
    return float(np.max(np.abs(1200 * np.log2(fr / fr.mean()))))


def metrics(stereo, key):
    mono = stereo.mean(axis=1)
    m = dict(peak_db=round(db(np.abs(stereo).max()), 2), rms_0_500=round(db(rms(seg(stereo, 0, 500))), 2),
             rms_sus=round(db(rms(seg(stereo, 500, 1500))), 2))
    m.update(onset_metrics(stereo))
    r_early, r_mid = rms(seg(stereo, 0, 20)), rms(seg(stereo, 20, 120))
    m['transient_db'] = round(db(r_early) - db(r_mid), 2)
    x = seg(stereo, 0, 50)
    m['crest_50ms'] = round(db(np.abs(x).max()) - db(rms(x)), 2) if len(x) else 0.0
    for tag, a, b in (('att', 0, 100), ('sus', 300, 1000)):
        f, p = spectrum(mono, a, b)
        if f is None: m.update({f'centroid_{tag}': -1, f'hf2k_{tag}': -1, f'hf4k_{tag}': -1, f'hf8k_{tag}': -1, f'rolloff85_{tag}': -1}); continue
        m.update({f'centroid_{tag}': round(centroid(f, p)), f'hf2k_{tag}': round(hf_share(f, p, 2000), 3), f'hf4k_{tag}': round(hf_share(f, p, 4000), 3),
                  f'hf8k_{tag}': round(hf_share(f, p, 8000), 4), f'rolloff85_{tag}': round(rolloff(f, p))})
    m['f0_drift_cents'] = round(f0_drift_cents(mono, key), 2)
    return m


def third_octave(mono, a_ms, b_ms):
    f, p = spectrum(mono, a_ms, b_ms)
    if f is None: return None
    centers = 100 * 2 ** (np.arange(0, 21) / 3.0)
    centers = centers[centers < 14000]
    out = []
    for c in centers:
        lo, hi = c / 2 ** (1 / 6), c * 2 ** (1 / 6)
        out.append(p[(f >= lo) & (f < hi)].sum() + EPS)
    out = np.array(out)
    return centers, 10 * np.log10(out / out.sum())


def lsd(a, b, a_ms=300, b_ms=1000):
    """RMS difference (dB) of level-normalised third-octave spectra. 0 = identical timbre."""
    ta, tb = third_octave(a.mean(axis=1), a_ms, b_ms), third_octave(b.mean(axis=1), a_ms, b_ms)
    if ta is None or tb is None: return float('nan')
    return float(np.sqrt(np.mean((ta[1] - tb[1]) ** 2)))


def sample_error_db(a, b, ms=100):
    n = min(len(a), len(b), SR * ms // 1000)
    x, y = a[:n].ravel(), b[:n].ravel()
    ey = float(np.sum(y * y))
    return 10 * math.log10(max(float(np.sum((x - y) ** 2)), 1e-30) / max(ey, 1e-30)) if ey > 1e-12 else float('nan')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--in', dest='indir', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--tsf-dir', help='dir with the original SF2 files for the (non-equivalent) TinySoundFont reference')
    a = ap.parse_args()
    ind, out = Path(a.indir), Path(a.out)
    (out / 'listen').mkdir(parents=True, exist_ok=True)
    rows = []
    for mf in sorted(ind.glob('manifest_*.csv')):
        rows += list(csv.DictReader(open(mf)))
    if not rows: sys.exit('no manifest')
    data = {}
    for r in rows:
        rate, st = read_wav(ind / r['file'])
        assert rate == SR
        r['key'], r['vel'] = int(r['key']), int(r['vel'])
        r['stereo'] = st
        r['m'] = metrics(st, r['key'])
        data[(r['font'], r['preset'], r['key'], r['vel'], r['variant'])] = r
    # optional TSF reference (NOT BASSMIDI)
    if a.tsf_dir:
        try:
            import tinysoundfont
            fonts = {'colombo': 'ColomboGMGS2_BM.sf2', 'tyros': 'yamaha tyros 4_just_t4_fixed.sf2'}
            pcs = {r['preset']: int(r['pc']) for r in rows}
            for font, fn in fonts.items():
                for preset, pc in pcs.items():
                    for vel in (30, 60, 90, 100, 127):
                        s = tinysoundfont.Synth(gain=0, samplerate=SR); sid = s.sfload(os.path.join(a.tsf_dir, fn)); s.program_select(0, sid, 0, pc)
                        s.noteon(0, 60, vel); x1 = np.frombuffer(s.generate(int(2.0 * SR)), dtype=np.float32).reshape(-1, 2)
                        s.noteoff(0, 60); x2 = np.frombuffer(s.generate(int(1.0 * SR)), dtype=np.float32).reshape(-1, 2)
                        st = np.concatenate([x1, x2]).astype(np.float64)
                        data[(font, preset, 60, vel, 'TSF_ref')] = dict(font=font, preset=preset, key=60, vel=vel, variant='TSF_ref', stereo=st, m=metrics(st, 60), pc=pc, path='tsf', file='')
        except Exception as e:  # reference is optional
            print('TSF reference unavailable:', e)
    # ---- CSV ----
    cols = None
    with open(out / 'results.csv', 'w', newline='') as f:
        w = None
        for k, r in sorted(data.items(), key=lambda kv: (kv[0][0], kv[0][1], kv[0][2], kv[0][3], kv[0][4])):
            line = dict(path=r['path'], font=r['font'], preset=r['preset'], key=r['key'], vel=r['vel'], variant=r['variant'], **r['m'])
            if w is None: w = csv.DictWriter(f, fieldnames=list(line)); w.writeheader()
            w.writerow(line)
    G = lambda font, preset, variant, vel=100, key=60: data.get((font, preset, key, vel, variant))
    fonts = sorted({k[0] for k in data}); presets = []
    for r in rows:
        if r['preset'] not in presets: presets.append(r['preset'])
    md = []
    P = md.append
    P('# Offline audio lab report\n')
    P('Renders: real BASS/BASSMIDI (Linux x86_64) at 48 kHz float, same stream flags/attributes/FONTEX2 keyboard mapping as BassMidiPlayer. '
      '`PLAYER_*` = the production `BassMidiPlayer` code. `TSF_ref` = TinySoundFont, NOT equivalent to BASSMIDI (context only).\n')
    # T1
    P('\n## T1. Neutral vs app controllers vs production player (key C4, velocity 100)\n')
    P('Level columns are absolute (dBFS); timbre columns are level-invariant. dLevel = RMS(0-500 ms) difference; LSD = level-normalised spectral distance (dB).\n')
    P('| font | preset | variant | peak dB | RMS0-500 dB | centroid att Hz | centroid sus Hz | >4k sus % | transient dB | -10dB ms | rise10-90 ms | f0 drift cents |')
    P('|---|---|---|---|---|---|---|---|---|---|---|---|')
    summary = {'app_vs_neutral_lsd': [], 'app_vs_neutral_dlevel': [], 'player_vs_raw_lsd': [], 'player_vs_raw_dlevel': [], 'player_vs_raw_err': []}
    for font in fonts:
        for preset in presets:
            for var in ('RAW_neutral', 'RAW_app', 'PLAYER_app', 'TSF_ref'):
                r = G(font, preset, var)
                if not r: continue
                m = r['m']
                P(f"| {font} | {preset} | {var} | {m['peak_db']} | {m['rms_0_500']} | {m['centroid_att']} | {m['centroid_sus']} | {m['hf4k_sus']} | {m['transient_db']} | {m['m10']} | {m['rise_10_90']} | {m['f0_drift_cents']} |")
            n, ap_, pl = G(font, preset, 'RAW_neutral'), G(font, preset, 'RAW_app'), G(font, preset, 'PLAYER_app')
            if n and ap_:
                summary['app_vs_neutral_lsd'].append(lsd(n['stereo'], ap_['stereo'])); summary['app_vs_neutral_dlevel'].append(ap_['m']['rms_0_500'] - n['m']['rms_0_500'])
            if ap_ and pl:
                summary['player_vs_raw_lsd'].append(lsd(pl['stereo'], ap_['stereo'])); summary['player_vs_raw_dlevel'].append(pl['m']['rms_0_500'] - ap_['m']['rms_0_500'])
                summary['player_vs_raw_err'].append(sample_error_db(pl['stereo'], ap_['stereo']))
    # T2 differences
    P('\n## T2. What the app configuration changes (per preset, velocity 100)\n')
    P('| font | preset | app-vs-neutral dLevel dB | app-vs-neutral LSD dB | player-vs-raw_app dLevel dB | player-vs-raw_app LSD dB | player-vs-raw_app sample error dB (first 100 ms) |')
    P('|---|---|---|---|---|---|---|')
    for font in fonts:
        for preset in presets:
            n, ap_, pl = G(font, preset, 'RAW_neutral'), G(font, preset, 'RAW_app'), G(font, preset, 'PLAYER_app')
            if not (n and ap_): continue
            row = [f"{ap_['m']['rms_0_500'] - n['m']['rms_0_500']:+.2f}", f"{lsd(n['stereo'], ap_['stereo']):.2f}"]
            row += [f"{pl['m']['rms_0_500'] - ap_['m']['rms_0_500']:+.2f}", f"{lsd(pl['stereo'], ap_['stereo']):.2f}", f"{sample_error_db(pl['stereo'], ap_['stereo']):.1f}"] if pl else ['n/a'] * 3
            P(f'| {font} | {preset} | ' + ' | '.join(row) + ' |')
    # T3 velocity
    P('\n## T3. Velocity response (RAW_neutral, C4)\n')
    P('| font | preset | velocity | RMS0-500 dB | centroid att Hz | centroid sus Hz | >4k att % | >4k sus % | transient dB | -10dB ms |')
    P('|---|---|---|---|---|---|---|---|---|---|')
    for font in fonts:
        for preset in presets:
            for vel in (30, 60, 90, 100, 127):
                r = G(font, preset, 'RAW_neutral', vel)
                if r: m = r['m']; P(f"| {font} | {preset} | {vel} | {m['rms_0_500']} | {m['centroid_att']} | {m['centroid_sus']} | {m['hf4k_att']} | {m['hf4k_sus']} | {m['transient_db']} | {m['m10']} |")
    # T4 sweep
    P('\n## T4. Controller / configuration sweep (velocity 100, C4; deltas against RAW_neutral of the same preset)\n')
    P('| font | preset | variant | dLevel dB | LSD dB | dCentroid sus Hz | d>4k sus %-pts | dTransient dB | d-10dB ms | f0 drift cents |')
    P('|---|---|---|---|---|---|---|---|---|---|')
    sweep_rows = []
    for font in fonts:
        for preset in presets:
            base = G(font, preset, 'RAW_neutral')
            if not base: continue
            for (f_, p_, k_, v_, var), r in sorted(data.items()):
                if f_ == font and p_ == preset and k_ == 60 and v_ == 100 and var.startswith('S_'):
                    m, b = r['m'], base['m']
                    row = dict(font=font, preset=preset, variant=var, dlevel=m['rms_0_500'] - b['rms_0_500'], lsd=lsd(base['stereo'], r['stereo']),
                               dcent=m['centroid_sus'] - b['centroid_sus'], dhf=m['hf4k_sus'] - b['hf4k_sus'], dtr=m['transient_db'] - b['transient_db'], dm10=m['m10'] - b['m10'], drift=m['f0_drift_cents'])
                    sweep_rows.append(row)
                    P(f"| {font} | {preset} | {var} | {row['dlevel']:+.2f} | {row['lsd']:.2f} | {row['dcent']:+d} | {row['dhf']:+.3f} | {row['dtr']:+.2f} | {row['dm10']:+.1f} | {row['drift']} |")
    # T5 TSF
    if any(k[4] == 'TSF_ref' for k in data):
        P('\n## T5. BASSMIDI (RAW_neutral) vs TinySoundFont reference (context only)\n')
        P('| font | preset | velocity | BASS centroid att | TSF centroid att | BASS >4k sus % | TSF >4k sus % | BASS -10dB ms | TSF -10dB ms | LSD dB |')
        P('|---|---|---|---|---|---|---|---|---|---|')
        for font in fonts:
            for preset in presets:
                for vel in (60, 100):
                    b, t = G(font, preset, 'RAW_neutral', vel), G(font, preset, 'TSF_ref', vel)
                    if b and t: P(f"| {font} | {preset} | {vel} | {b['m']['centroid_att']} | {t['m']['centroid_att']} | {b['m']['hf4k_sus']} | {t['m']['hf4k_sus']} | {b['m']['m10']} | {t['m']['m10']} | {lsd(b['stereo'], t['stereo']):.2f} |")
    # T6 keys
    P('\n## T6. Register (RAW_neutral, velocity 100)\n')
    P('| font | preset | key | RMS0-500 dB | centroid att Hz | >4k sus % | -10dB ms | f0 drift cents |')
    P('|---|---|---|---|---|---|---|---|')
    for font in fonts:
        for preset in ('GrandPiano', 'Strings'):
            for key in (36, 48, 60, 72, 84):
                r = G(font, preset, 'RAW_neutral', 100, key)
                if r: m = r['m']; P(f"| {font} | {preset} | {key} | {m['rms_0_500']} | {m['centroid_att']} | {m['hf4k_sus']} | {m['m10']} | {m['f0_drift_cents']} |")
    # ---- automatic findings ----
    f_ = []
    fin = lambda s: f_.append('- ' + s)
    mx = lambda v: max([abs(x) for x in v if not (isinstance(x, float) and math.isnan(x))] or [float('nan')])
    if summary['player_vs_raw_lsd']:
        fin(f"PLAYER_app vs RAW_app (same BASSMIDI, app config): max LSD {mx(summary['player_vs_raw_lsd']):.2f} dB, max |dLevel| {mx(summary['player_vs_raw_dlevel']):.2f} dB, "
            f"median first-100ms sample error {np.nanmedian(summary['player_vs_raw_err']):.1f} dB -> "
            + ('production player adds nothing audible on top of BASSMIDI with the app controllers' if mx(summary['player_vs_raw_lsd']) < 1.0 and mx(summary['player_vs_raw_dlevel']) < 0.5 else 'production player DIFFERS from raw BASSMIDI with the same controllers: inspect T2'))
    if summary['app_vs_neutral_lsd']:
        fin(f"RAW_app vs RAW_neutral: max LSD {mx(summary['app_vs_neutral_lsd']):.2f} dB, level change {np.nanmedian(summary['app_vs_neutral_dlevel']):+.2f} dB (median) -> "
            + ('app controllers change mostly level, not timbre' if mx(summary['app_vs_neutral_lsd']) < 1.5 else 'app controllers change TIMBRE: inspect T4'))
    big = sorted([r for r in sweep_rows if r['lsd'] == r['lsd']], key=lambda r: -r['lsd'])[:8]
    for r in big: fin(f"sweep {r['font']}/{r['preset']}/{r['variant']}: LSD {r['lsd']:.2f} dB, dCentroid {r['dcent']:+d} Hz, d>4k {r['dhf']:+.3f} pts, dLevel {r['dlevel']:+.2f} dB")
    drifts = [r['m']['f0_drift_cents'] for r in data.values() if r['variant'] in ('RAW_neutral', 'RAW_app', 'PLAYER_app') and r['preset'] in ('GrandPiano', 'EPiano', 'NylonGuitar', 'SteelGuitar', 'Strings', 'Organ') and r['m']['f0_drift_cents'] == r['m']['f0_drift_cents']]
    if drifts: fin(f"pitch stability (informational): peak-frequency drift over 0.2-1.5 s, median {np.median(drifts):.1f} cents, 95th pct {np.percentile(drifts, 95):.1f} cents. This includes the preset's own detune/beating; compare the SAME preset across variants (T4, CC1 rows) to isolate controller-induced vibrato")
    P('\n## Automatic findings\n'); md += f_
    (out / 'report.md').write_text('\n'.join(md) + '\n')
    # ---- plots ----
    try:
        import matplotlib; matplotlib.use('Agg'); import matplotlib.pyplot as plt
        fig, axes = plt.subplots(len(fonts), 2, figsize=(13, 4.2 * len(fonts)), squeeze=False)
        for i, font in enumerate(fonts):
            for j, preset in enumerate(('GrandPiano', 'EPiano')):
                ax = axes[i][j]
                for var, col in (('RAW_neutral', 'tab:blue'), ('RAW_app', 'tab:orange'), ('PLAYER_app', 'tab:green'), ('TSF_ref', 'tab:gray')):
                    r = G(font, preset, var)
                    if not r: continue
                    t = third_octave(r['stereo'].mean(axis=1), 300, 1000)
                    if t: ax.plot(t[0], t[1], label=var, color=col, lw=1.6, ls='--' if var == 'TSF_ref' else '-')
                ax.set_xscale('log'); ax.set_title(f'{font} {preset} v100 C4 - level-normalised 1/3-octave spectrum'); ax.set_xlabel('Hz'); ax.set_ylabel('dB (share of total)'); ax.grid(alpha=.3); ax.legend(fontsize=8)
        fig.tight_layout(); fig.savefig(out / 'fig_spectra.png', dpi=110); plt.close(fig)
        fig, axes = plt.subplots(1, 2, figsize=(13, 4.5))
        for font, ls in zip(fonts, ('-', '--')):
            for preset in presets:
                xs = [v for v in (30, 60, 90, 100, 127) if G(font, preset, 'RAW_neutral', v)]
                if xs:
                    axes[0].plot(xs, [G(font, preset, 'RAW_neutral', v)['m']['centroid_att'] for v in xs], ls, marker='o', label=f'{font} {preset}')
                    axes[1].plot(xs, [G(font, preset, 'RAW_neutral', v)['m']['rms_0_500'] for v in xs], ls, marker='o')
        axes[0].set_title('Attack-window spectral centroid vs velocity (BASSMIDI neutral)'); axes[0].set_xlabel('velocity'); axes[0].set_ylabel('Hz'); axes[0].legend(fontsize=6, ncol=2); axes[0].grid(alpha=.3)
        axes[1].set_title('Level vs velocity'); axes[1].set_xlabel('velocity'); axes[1].set_ylabel('RMS 0-500 ms dBFS'); axes[1].grid(alpha=.3)
        fig.tight_layout(); fig.savefig(out / 'fig_velocity.png', dpi=110); plt.close(fig)
        fig, axes = plt.subplots(len(fonts), 2, figsize=(13, 4 * len(fonts)), squeeze=False)
        for i, font in enumerate(fonts):
            for j, preset in enumerate(('GrandPiano', 'EPiano')):
                ax = axes[i][j]
                for var, col in (('RAW_neutral', 'tab:blue'), ('RAW_app', 'tab:orange'), ('PLAYER_app', 'tab:green'), ('TSF_ref', 'tab:gray')):
                    r = G(font, preset, var)
                    if not r: continue
                    env = np.abs(r['stereo']).max(axis=1)[:int(.08 * SR)]; k = SR // 1000
                    e = np.convolve(env, np.ones(k) / k, mode='same'); e = 20 * np.log10(e / (e.max() + EPS) + EPS)
                    ax.plot(np.arange(len(e)) * 1000 / SR, e, label=var, color=col)
                ax.set_ylim(-60, 3); ax.set_title(f'{font} {preset} v100 - first 80 ms envelope (dB re peak)'); ax.set_xlabel('ms after NOTE_ON'); ax.grid(alpha=.3); ax.legend(fontsize=8)
        fig.tight_layout(); fig.savefig(out / 'fig_onset.png', dpi=110); plt.close(fig)
        if sweep_rows:
            fig, ax = plt.subplots(figsize=(13, 0.28 * len(sweep_rows) + 2))
            labels = [f"{r['font'][:3]}/{r['preset']}/{r['variant'][2:]}" for r in sweep_rows]
            ax.barh(range(len(sweep_rows)), [r['lsd'] if r['lsd'] == r['lsd'] else 0 for r in sweep_rows]); ax.set_yticks(range(len(sweep_rows))); ax.set_yticklabels(labels, fontsize=5)
            ax.set_xlabel('LSD vs neutral (dB, level-normalised)'); ax.set_title('Which controller / config changes the timbre'); fig.tight_layout(); fig.savefig(out / 'fig_sweep.png', dpi=110); plt.close(fig)
    except Exception as e:
        print('plots skipped:', e)
    # ---- listening set (16-bit): neutral vs app vs player, velocity 60 and 100 ----
    for (font, preset, key, vel, var), r in data.items():
        if key == 60 and vel in (60, 100) and var in ('RAW_neutral', 'RAW_app', 'PLAYER_app'):
            write_wav16(out / 'listen' / f'{font}_{preset}_v{vel}_{var}.wav', SR, r['stereo'][:int(2.5 * SR)])
    print((out / 'report.md').read_text())


if __name__ == '__main__':
    main()
