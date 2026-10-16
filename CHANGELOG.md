# Changelog

All notable changes to PA Analyzer. Versions follow [Semantic Versioning](https://semver.org); releases are the `v*` tags on GitHub, each with the signed arm64 APK.

## [0.3.0] – 2026-10-17

### Added
- Average several positions: tick saved measurements and choose **Average**. Levels are aligned at 200 Hz – 2 kHz and power-averaged over the common frequency range (fundamental, distortion, rub & buzz); the average is saved as a new measurement for the EQ page.
- Compare measurements: tick saved measurements and choose **Compare** to draw their fundamental and THD on top of the open one, with level and difference at the cursor and optional level alignment.
- Microphone calibration: load a measurement mic's calibration file (e.g. UMIK-1) in Settings; it corrects fundamental and harmonics of new measurements and re-analyses and is stored with the measurement.
- EQ: adjustable target curve: tilt (−2 to +1 dB per octave around 1 kHz) and bass boost (0–12 dB, 40–300 Hz); flat, gentle tilt and live remain as presets.

### Changed
- EQ: a bass boost in the target is not mistaken for a roll-off of the speaker, and a low shelf may boost when the target asks for more bass.
- About: removed the website link.

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

[0.3.0]: https://github.com/KaiFra/PA-Analyzer/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/KaiFra/PA-Analyzer/compare/v0.1.0...v0.2.1
[0.2.0]: https://github.com/KaiFra/PA-Analyzer/pull/1
[0.1.0]: https://github.com/KaiFra/PA-Analyzer/releases/tag/v0.1.0
