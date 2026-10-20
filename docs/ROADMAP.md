# Roadmap

Ideas for later versions, roughly in order of value.

## Done
- Average several positions, compare and overlay, microphone calibration (0.3.0).
- Live spectrum analyzer with waterfall, microphone or phone audio (0.4.0).

## Setting up the PA
4. **Sub/top time alignment and polarity** – arrival time and phase of sub and tops, suggested delay and polarity. Both must play in one sweep sequence (e.g. left = sub, right = top) because Bluetooth latency changes between connections.
5. **Feedback finder** – ring out monitors: detect frequencies that start to feed back and suggest notch filters.
6. **Maximum level test** – sweeps at rising levels, showing where distortion or compression starts.

## During the event
- **Live transfer function** – phone audio (what goes to the PA) against the microphone in real time, with the Bluetooth delay found automatically, to tune the EQ with music instead of sweeps.
- **1/3-octave bars** in the live analyzer, for pink-noise measurements.
7. **SPL meter and real-time analyzer** – dB(A)/dB(C), Leq, pink-noise RTA, logging for DIN 15905-5. Needs a one-time level calibration.

## Convenience
8. **Send EQ to the mixer** – write suggested EQ to digital mixers over Wi-Fi (e.g. X32/X Air via OSC). Needs network permission.
9. **PDF report** with charts, settings and EQ.
10. **Room analysis** – reverberation time (RT60) and waterfall for room modes.
