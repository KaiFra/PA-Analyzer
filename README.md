# PA Analyzer

Android app for measuring a PA system with your phone: it plays an exponential sine sweep over Bluetooth, records it with the microphone and shows, in one chart,

- **Fundamental**: frequency response of the whole chain (Bluetooth, amp, speakers, room, mic)
- **Distortion**: THD and the individual harmonics H2…H10
- **EQ suggestions**: parametric EQ settings (bells, shelves, a high-pass) to type into your mixer or speaker processor
- **Rub & buzz**: energy far above the excitation frequency (default: above the 10th harmonic), RMS and peak, with a noise-floor reference, to find rattles, rubbing voice coils and loose parts

<p>
  <img src="docs/screenshots/results-portrait.png" width="200" alt="Results: fundamental and THD">
  <img src="docs/screenshots/compare.png" width="200" alt="Comparing before and after EQ">
  <img src="docs/screenshots/eq.png" width="200" alt="EQ suggestion">
  <img src="docs/screenshots/measure.png" width="200" alt="Measure page">
  <img src="docs/screenshots/settings.png" width="200" alt="Settings page">
</p>
<p>
  <img src="docs/screenshots/results-landscape.png" width="790" alt="Results in landscape with harmonics and rub &amp; buzz">
</p>

<sub>Screenshots show a simulated loudspeaker (bass roll-off, rising bass distortion, a rattle around 120 Hz). They are rendered by the screenshot tests and updated by CI.</sub>

## Using it

1. Connect the phone to the PA's Bluetooth receiver. The app picks the Bluetooth output automatically; choose another output, a USB measurement mic or one stereo channel under **Settings → Devices**.
2. Turn the PA **down** first. Place the phone where you want to measure (on axis, ~1 m from the speaker is a good start).
3. Press **Start measurement** on the **Measure** page. The sweep plays, the analysis runs on the phone, and the **Results** page opens.
4. Raise the level until the impulse-response signal-to-noise ratio is comfortably above 40 dB, without input clipping (the app warns about both).

### Results

- All curves share one dB axis: distortion and rub & buzz are drawn at their absolute level (fundamental + relative level), like in a spectrum. Tap the chips below the chart to show or hide curves; by default only the fundamental and THD are shown.
- Tap or drag on the chart for a cursor. The chips then show the values at the cursor: the fundamental in dB, distortion in dB and % relative to the fundamental, rub & buzz in dB relative to the fundamental.
- Pinch horizontally to zoom the frequency axis, vertically to zoom the level axis; drag with two fingers to pan; double-tap to reset.
- Turn the phone sideways to view curves: the system bars hide, the navigation moves to a slim side rail and the curve list to a side panel you can hide.
- The wrench button is where the **analysis settings** live: smoothing (a slider from none to 1/3 octave), IR window, number of harmonics and rub & buzz start order. By default they also apply to new measurements. The raw recording is kept with every measurement, so it is evaluated again without measuring again (sweep settings are part of the recording and stay fixed).
- **Compare**: tick saved measurements in the list and choose **Compare** to draw their fundamental (and THD, when shown) on top of the open measurement, e.g. before/after EQ, left/right or two venues. The chips show each one's level at the cursor and the difference to the open measurement; tap a chip to remove it. **Align levels** shifts them to the open measurement's level at 200 Hz – 2 kHz.
- **Average several positions**: measure 3–5 spots in the audience area and save each, then tick them in the list of saved measurements and choose **Average**. The positions are aligned in level at 200 Hz – 2 kHz and power-averaged (like REW's RMS average): dips from room reflections at one spot are filled by the others, while resonances of the speaker stay. The average is saved as a new measurement, so the EQ page can use it.
- **Save** keeps the measurement on the phone, together with its recording; the list button opens saved measurements (open, rename, delete) and imports CSV files. **Share** sends the measurement as CSV (readable by spreadsheets) plus the recording as 32-bit float WAV (e.g. for Audacity or REW). Import both files together to get a fully re-analysable copy, for example on another phone.

### Microphone calibration

With a USB measurement microphone (e.g. miniDSP UMIK-1/UMIK-2), load its calibration file under **Settings → Devices → Microphone calibration** (for a UMIK: the 90° file when the mic points up, the 0° file when it points at the speaker). Text files with frequency and dB per line are read (tab, space, comma or semicolon separated; phase columns and comment lines are ignored). The calibration is applied to new measurements and when analysing again: the fundamental is corrected by the mic's deviation, each harmonic by the difference between its excitation frequency and the frequency where it is heard. The measurement details show which calibration was used, and it is stored in the CSV.

Bluetooth latency doesn't matter: the app finds the sweep in the recording itself and reports the measured delay.

### EQ suggestions

The **EQ** page turns the current measurement into filter settings for a mixer or speaker processor:

- Pick a **target**: a preset (flat, gentle tilt, live with more bass) or your own **tilt** (dB per octave around 1 kHz) and **bass boost** (level and frequency). Then set the number of **bands** your mixer has, the **filter types** it offers (bell, low shelf, high shelf, high-pass), the **range** to correct and the maximum **boost** (0–12 dB) and **cut** (1–24 dB), with a slider or by typing the value. Changes apply immediately.
- The app suggests bells and shelves with frequency, gain and Q (plus bandwidth in octaves, for mixers that use it), rounded to values you can dial in, and shows the measured response, the EQ, and the expected result.
- Where the speaker rolls off in the bass it suggests a **high-pass** (24 dB/oct Linkwitz-Riley) and never boosts there; boosts are limited (+3 dB by default) because filling dips costs headroom and room cancellations can't be filled anyway. Peaks are cut first.
- **Copy** or **Share** gives a text version with an Equalizer APO / REW section.

How it works: the response is smoothed to 1/6 octave and aligned with the target in the mid band (median, so a single resonance doesn't shift it). Filters are added one at a time where they help most and then optimized together within the limits (the combined EQ, not only each band, must respect them). The number of bands is a maximum: a band is only added if it clearly improves the result, more allowed bands never give a worse result, and a low shelf only boosts together with the high-pass (no extra excursion below the speaker's range), there is at most one low and one high shelf, and never a boost and a cut on top of each other.

The suggestion is only as good as the measurement: measure where the audience is, apply the filters, and measure again.

See [CHANGELOG.md](CHANGELOG.md) for what changed in each version.

## Settings

| Setting | Default | Notes |
| --- | --- | --- |
| Start / end frequency | 20 Hz – 20 kHz | End frequency at most 0.475 × sample rate |
| Duration | 5 s | 1–15 s. Rounded slightly so all harmonics stay phase-synchronous |
| Level | −20 dBFS | Digital level of the sweep; the PA volume sets the acoustic level |
| Sample rate | 48 kHz | 44.1 / 48 / 96 kHz (96 kHz only if the phone's mic supports it) |
| Averages | 1 | Up to 8 sweeps in one stream, aligned and averaged (+3 dB SNR per doubling) |
| Silence before / after | 0.5 s / 1.5 s | Before: noise estimate. After: must cover the room's reverberation |
| IR window¹ | 300 ms | Gating. Short windows (≈10 ms) remove reflections but lose bass resolution |
| Harmonics up to¹ | 5 | THD sums H2 up to this order (max 10) |
| R&B from order¹ | 10 | Rub & buzz counts everything above this multiple of the sweep frequency |
| Smoothing¹ | 1/12 octave | None, 1/24, 1/12, 1/6, 1/3 octave |
| Bluetooth channel | Both | Play the sweep on left, right or both channels |

¹ Analysis settings: set with the wrench on the Results page, not on the Settings page.

## How it works

- **Sweep**: synchronized exponential sweep (Novak et al.). Because f₁·L is an integer, the impulse response of harmonic *k* appears exactly L·ln(k) before the linear one.
- **Deconvolution**: regularized spectral division by the played sweep. The low band edge uses a causal Butterworth high-pass so no pre-ringing gets cut by the window.
- **Harmonics**: each harmonic impulse response is windowed out and transformed; |H_k(k·f)| / |H₁(f)| is the k-th harmonic distortion at excitation frequency f.
- **Rub & buzz**: a tracking high-pass. The recording is cut into ~85 ms frames; per frame everything above order × instantaneous sweep frequency is compared with the fundamental (RMS and peak), and the same measure applied to the silence before the sweep gives the noise floor.

All signal processing lives in the pure-Kotlin `:dsp` module and is tested on the JVM against simulated systems (linear, low-pass, quadratic/cubic nonlinearity with known harmonic levels, a frequency-limited rattle, latency jitter between averages).

## Limitations

- Phone microphones are not calibrated and usually roll off in the deep bass; compare measurements made with the same phone and position, or use a USB measurement mic with its calibration file. The app records with Android's *unprocessed* source when the phone supports it (no AGC/noise suppression); otherwise it falls back to *voice recognition*.
- Levels are relative (dB re digital full scale), not SPL.
- Background noise counts as rub & buzz; check the noise-floor curve before blaming the speaker.

## Building

Requires JDK 17 and the Android SDK (compileSdk 36).

```
./gradlew :dsp:test :app:testDebugUnitTest   # unit tests (also renders docs/screenshots)
./gradlew :app:lintRelease :app:assembleRelease
```

The app targets arm64-v8a only (`abiFilters`). It currently contains no native code, so the APK itself isn't ABI-specific; the filter keeps any native library a future dependency brings in to arm64, and CI checks that.

## CI

`.github/workflows/ci.yml` runs on every branch push and has two jobs. Work happens on branches (e.g. `release/0.2.0`) that are merged into `main` via pull request.

- **Tests**: unit tests (DSP and app), lint, and the screenshot tests. Changed screenshots are committed to `docs/screenshots` on the branch that was pushed.
- **Release build** (after the tests pass): checks the signing secrets, builds the signed arm64 release APK, verifies signature and ABI, and uploads it as the `release-apk` artifact.

Release signing uses these repository secrets; the build job stops with a specific error if one is missing or wrong (bad base64, wrong keystore password, unknown alias, wrong key password):

| Secret | Content |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Optional: only if the key has its own password (older JKS keystores). Otherwise the keystore password is used. |

Pushing a tag like `v0.1.0` also publishes a GitHub release with the signed APK; the tag becomes the version name.
