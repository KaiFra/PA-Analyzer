# Roadmap

Ideas for later versions, roughly in order of value.

## Done
- Average several positions, compare and overlay, microphone calibration (0.3.0).

## Setting up the PA
4. **Sub/top time alignment and polarity** – arrival time and phase of sub and tops, suggested delay and polarity. Both must play in one sweep sequence (e.g. left = sub, right = top) because Bluetooth latency changes between connections.
5. **Feedback finder** – ring out monitors: detect frequencies that start to feed back and suggest notch filters.
6. **Maximum level test** – sweeps at rising levels, showing where distortion or compression starts.

## During the event
7. **SPL meter and real-time analyzer** – dB(A)/dB(C), Leq, pink-noise RTA, logging for DIN 15905-5. Needs a one-time level calibration.

## Convenience
8. **Send EQ to the mixer** – write suggested EQ to digital mixers over Wi-Fi (e.g. X32/X Air via OSC). Needs network permission.
9. **PDF report** with charts, settings and EQ.
10. **Room analysis** – reverberation time (RT60) and waterfall for room modes.
