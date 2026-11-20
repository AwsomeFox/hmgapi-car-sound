# Changelog

## 0.2.2

First public release.

- A 5-band equalizer (60 Hz to 14 kHz, ±12 dB) and bass boost on Android Auto and CarPlay audio,
  with eight presets tuned for the 2024 Ioniq 5 Limited's Bose system: Recommended, Deep bass,
  Harman car, Highway, Clear, Low volume, Voices and Flat.
- Speed tuning: volume, bass and treble each follow their own curve, set by dragging five points
  (20, 35, 50, 65 and 80 mph). The car's speed comes from HMG-API (`vehicle.read: speed`).
- Bose Dynamic Speed Compensation: leave it as set in the car, off while Car Sound runs, or on.
  "As set in the car" puts back what you had, across restarts.
- A Car Sound tile in the app menu (it shows after the head unit restarts).
- Radio and Bluetooth audio are left as they are; turned off or removed, the sound is as before.
- Needs HMG-API 1.1.0 (its release candidates work).
