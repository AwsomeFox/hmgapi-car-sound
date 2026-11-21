# Changelog

## 0.5.0

- **Clip guard:** a meter at the end of Car Sound's chain watches the peak it sends on; while it
  reaches full scale (clipping), the speed volume backs off 1 dB a second, up to 6 dB, and comes
  back once it's clear. The Car page shows the peak and the seconds clipped in the last minute.
- **Speed presets:** Low, Medium (the new default), High and Max, or your own curves.
- **Loudness** (new page): bass and treble lifted when the volume is low, as ears lose them,
  from the car's volume for the source playing. Off by default.
- **The car's amplifier:** the speed and loudness lifts can be made with the car's own Bass and
  Treble, in steps on top of your setting, instead of Android's equalizer. The amplifier comes
  after Android and has its own headroom; the lifts then reach every source. Profiles by
  amplifier type (head unit, Mobis, Harman, Bose) aren't measured yet: see docs/AMPLIFIER.md.
- **Car page** (was Bose): the car's own Bass, Mid and Treble as set or flat while Car Sound is
  on (put back when it's off); the car's speed compensation (Bose DSC, or Speed Dependent
  Volume on other amplifiers, shown only when the car has it); the clip guard.
- **Save car report** (left rail): a text file for a GitHub issue, on a USB stick if one is in,
  without serial numbers, the VIN, Bluetooth names or song details.
- HMG-API 1.1.0-rc.8: Android's audio effects load again (rc.8 starts services in a way that
  drops the library path).
- For testing over adb: `sim.sh` (a simulated speed while parked) and `amp.sh` (amplifier sweeps,
  test profiles, car reports).

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
