# Changelog

All notable changes to PA Analyzer. Versions follow [Semantic Versioning](https://semver.org); releases are the `v*` tags on GitHub, each with the signed arm64 APK.

## [0.2.1] – 2026-10-16

### Added
- EQ: maximum boost (0–12 dB) and cut (1–24 dB) are set with a slider in 0.5 dB steps or typed into a field, instead of three fixed choices.
- EQ: choose the filter types your mixer offers (bell, low shelf, high shelf, high-pass); only enabled types are suggested.
- This changelog.

### Changed
- EQ: the number of bands is a maximum. A band is only added when it clearly improves the result, and the EQ page says how many bands are used and why.
- EQ: allowing more bands never gives a worse suggestion than allowing fewer.
- EQ: bells are placed at the largest correctable deviations; at most one low and one high shelf, as on most mixers.
- EQ: no boost and cut less than 1/3 octave apart (they used to partly cancel each other).
- EQ: a low shelf only boosts when the high-pass is used, so the boost doesn't reach below the speaker's range.

## [0.2.0] – 2026-10-16

Merged to `main`, not released as a tag; its changes ship with 0.2.1.

### Added
- EQ page: parametric EQ suggestions (bells, shelves, 24 dB/oct high-pass) for a mixer or speaker processor, with target curves (flat, tilt, live), number of bands, correction range and boost/cut limits. Shows measured response, EQ and expected result; copy or share as text with an Equalizer APO / REW section.
- About section in Settings (version, build, links).
- CI: attach the APK to a release that already exists for the tag; manual run to release an existing tag.

## [0.1.0] – 2026-10-16

First release.

### Added
- Measurement with a synchronized exponential sine sweep played over Bluetooth (or another output) and recorded with the phone or a USB microphone; adjustable range, duration, level, sample rate, averages and silence.
- Results in one chart: fundamental, THD, harmonics H2…H10, rub & buzz (RMS, peak, noise floor), with curves that can be switched on and off (fundamental and THD by default), cursor, pinch zoom per axis and pan.
- Landscape layout for viewing curves: hidden system bars, side navigation rail and collapsible curve panel.
- Output, input and Bluetooth channel selection in Settings; dedicated Results page.
- Save, rename, delete and reopen measurements; share and import as CSV plus a 32-bit float WAV of the recording.
- Analysis settings (smoothing, IR window, harmonics, rub & buzz order) can be changed after a measurement; the recording is evaluated again.
- CI: unit tests, lint and screenshot tests in one job, signed arm64 release build in another; signing secrets are checked with readable errors (`ANDROID_KEY_PASSWORD` optional); screenshots committed to the branch; tags publish a GitHub release.

### Fixed
- No THD spikes near the end of the sweep: harmonics are only evaluated where the sweep has not started to fade out.

[0.2.1]: https://github.com/KaiFra/PA-Analyzer/compare/v0.1.0...v0.2.1
[0.2.0]: https://github.com/KaiFra/PA-Analyzer/pull/1
[0.1.0]: https://github.com/KaiFra/PA-Analyzer/releases/tag/v0.1.0
