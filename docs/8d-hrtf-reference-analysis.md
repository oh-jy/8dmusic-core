# 8D Audio Converter HRTF reference analysis

Research date: 2026-11-05. The upstream sources below refer to the mutable
`main` branch; the examined Cargo manifest reports version 1.2.0. GitHub API
rate limiting and a failed clone prevented verification of its commit ID.
Findings are based on source inspection, not listening or performance tests.

## Comparison

- Upstream loads an embedded HRIR sphere through the Rust `hrtf` crate and
  processes files offline, not as an Android real-time stream: [initialization](https://github.com/Artezon/8D-Audio-Converter-HRTF/blob/main/src/main.rs#L311-L315), [processing and playback](https://github.com/Artezon/8D-Audio-Converter-HRTF/blob/main/src/main.rs#L357-L416). This project uses a lightweight parametric binaural model in `app/src/main/cpp/NativeSpatialExport.cpp`, shared with its native player.
- Upstream has circular, figure-eight, vertical, spiral and deterministic
  pseudo-random paths, plus configurable parameter ranges: [paths](https://github.com/Artezon/8D-Audio-Converter-HRTF/blob/main/src/hrtf_8d.rs#L76-L136), [ranges](https://github.com/Artezon/8D-Audio-Converter-HRTF/blob/main/src/main.rs#L148-L186). Its figure-eight is a circular orbit with vertical modulation, not a conventional planar figure-eight. This project has a fixed orbit with elevation/depth modulation and deterministic direction changes; see `core/src/main/kotlin/j5i/core/Curve.kt`.
- Upstream distance changes gain only: [formula](https://github.com/Artezon/8D-Audio-Converter-HRTF/blob/main/src/hrtf_8d.rs#L27-L33). This project's model also changes high-frequency attenuation and dry/wet balance; its depth percentage is not a physical distance in meters.
- Upstream uses an 80 Hz crossover and a 150 Hz low-shelf filter: [crossover](https://github.com/Artezon/8D-Audio-Converter-HRTF/blob/main/src/crossover.rs#L11-L35), [low shelf](https://github.com/Artezon/8D-Audio-Converter-HRTF/blob/main/src/hrtf_8d.rs#L175-L182). This project's spatial path first downmixes stereo to mono, while raw playback preserves channels; see `app/src/main/cpp/NativeSpatialPlayer.cpp`.
- Both use algorithmic reverberation. This project already exposes room size,
  mix and RT60 in `core/src/main/kotlin/j5i/core/Params.kt`.

## Implementation cautions

Upstream's [reverb reset](https://github.com/Artezon/8D-Audio-Converter-HRTF/blob/main/src/reverb.rs#L8-L29) appears to replace configured room, damping and width values with defaults when processing begins. Its [low-shelf processing](https://github.com/Artezon/8D-Audio-Converter-HRTF/blob/main/src/hrtf_8d.rs#L311-L314) appears to share filter state between channels. The [initial angle](https://github.com/Artezon/8D-Audio-Converter-HRTF/blob/main/src/main.rs#L317-L318) also appears to undergo [two degree-to-radian conversions](https://github.com/Artezon/8D-Audio-Converter-HRTF/blob/main/src/hrtf_8d.rs#L61-L71). These are static observations, not runtime-verified defects.

The upstream [MIT license](https://github.com/Artezon/8D-Audio-Converter-HRTF/blob/main/LICENSE) does not establish separate redistribution rights for its bundled HRIR dataset or all dependencies. Verify provenance, dataset terms, and each actually used dependency before reuse.

## Suggested order

1. Add a small number of clearly defined paths, retaining the existing orbit as the default and keeping visual, playback, radio and export behavior consistent.
2. Prototype low-shelf adjustment and, separately, stereo-preserving low-frequency crossover with phase and level checks.
3. Prototype an optional measured-HRIR mode after dataset licensing, sample-rate handling, real-device performance and listening validation. Do not replace the current renderer on source inspection alone.
