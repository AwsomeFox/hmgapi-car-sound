# Changelog

## 0.1.1

- Cars without the Bose system: the Bose page is hidden, and Car Sound never touches the Bose
  speed compensation setting. The engine asks the car whether it has Bose DSC the same way the
  car's own Sound settings do.
- Head units whose equalizer has other bands than Android's usual five: the five settings are
  spread over the bands it has, by frequency, within the levels it takes.
- The status says when the car's audio can't be tuned (Android's audio effects fail) and when
  HMG-API has no speed for the car.
- Finds Android Auto and CarPlay audio from a sub-process of either app too.

## 0.1.0

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
