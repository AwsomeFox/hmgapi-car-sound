# Car Sound and the car's amplifier

Car Sound can make its boosts two ways (Car page, "Boosts and speed volume are made by"):

- **Android alone**: Android's equalizer and loudness enhancer, on Android's audio only. Android
  4.4's equalizer turns its whole output down by its largest boost, so a boost there is mostly
  the rest of the music getting quieter, and the speed volume is squeezed by a compressor.
- **The car's amplifier**: the car's own Bass, Mid, Treble and volume (the ones in the car's
  Sound settings and on the knob). The equalizer's shape becomes steps of Bass, Mid and Treble
  plus cuts in Android's equalizer; the speed and loudness lifts are more steps; the speed
  volume is steps of the car's volume. The amplifier comes after Android and has its own
  headroom, so a step there is a real lift, on every source (radio and Bluetooth too).

With the amplifier, Car Sound sets the car's Bass/Mid/Treble itself while it's on and puts yours
back when it's turned off or removed. Your volume stays yours: Car Sound adds steps on top and
follows you when you turn the knob.

Left alone, Car Sound uses the amplifier where its profile is marked verified, and Android alone
elsewhere.

## What differs between cars: the amplifier profile

The car reports its amplifier type (`getAmpType()`): 0 the head unit's own, 1 an external Mobis
amplifier, 2 an external Harman-family amplifier, 3 an external Bose. Car Sound has a profile
for each type in `car-sound/src/engine/com/carsound/engine/Amp.java` (`builtIn`). **None is
measured.** The Bose profile (type 3) was tuned by ear on a 2024 Ioniq 5 Limited and is marked
`"verified": true`; the others are the same guesses, `"verified": false`, until a car test or a
car report fills them in.

| Value | What it is | How to find it |
|---|---|---|
| `bass_step_mB`, `treble_step_mB` | how much one step lifts (100 = 1 dB) | `amp.sh sweep`: by ear, or an SPL app with pink noise |
| `max_bass_steps`, `max_treble_steps` | the most Car Sound adds | the step before it booms, distorts or the amplifier limits |
| `taper_from`, `taper_to`, `taper_steps` | from which volume the maximum shrinks, to what | a loud bass-heavy track: the volume where steps start to distort |
| `min_interval_s`, `hysteresis` | how often the amplifier may be raised; how far past a step a lift must go | a drive: lifts shouldn't flicker |
| `method`, `type_offset` | how the amplifier is written: `bmt` (setBassMidTreb, like the car's Sound page) or `sound` (setSound per setting, plus `type_offset`) | see "Saved or not" below |
| `android_rest` | also make the part between two steps with Android's equalizer | listening |
| `mid_step_mB`, `bass_shape`, `mid_shape`, `treble_shape`, `max_preset_steps` | how the equalizer's own shape is split: how far each control reaches into the five bands (percent of a step at 60, 230, 910, 3600, 14000 Hz), and the most steps the shape may take | compare a preset with the amplifier on and off at the same volume: it should sound the same, only louder in the bass |
| `vol_step_mB`, `max_vol_steps`, `vol_cap`, `vol_ui` | the speed volume as the car's volume: dB per step, the most steps, the highest volume it may reach, and setVolume's UI option (0: no pop-up) | `sh sim.sh 65` parked: how much louder the steps are |
| `vol_group_aa`, `vol_group_cp` | the volume groups of Android Auto and CarPlay music | the car report's volume groups |
| `speed_comp`, `speed_comp_on`, `speed_comp_name` | the car's own speed compensation setting (47 Bose DSC, 43 Speed Dependent Volume) and its "on" value | the car report's sound settings and their availability |

Try values without a rebuild:

```sh
sh amp.sh profile '{"bass_step_mB": 150, "max_bass_steps": 4}'
sh amp.sh profile clear
```

The values that hold up go into `builtIn()` for that amplifier type, with `"verified": true`.

## Testing in the car (parked)

1. Install Car Sound, play music over Android Auto or CarPlay, and stay parked.
2. `sh amp.sh status`: the amplifier type, your tone and the volume. If your Bass is already high,
   steps above 20 can't be sent: set the car's tone to Flat on the Car page first.
3. `sh amp.sh sweep bass`, then `sh amp.sh sweep treble`. For each step note:
   - how much louder the bass (or treble) gets;
   - any click, dropout or mute when it changes;
   - whether a pop-up or the car's Sound page appears.
4. Turn the volume up with a bass-heavy track and `sh amp.sh hold 6 0 0`: note the volume
   where it starts to distort. That's `taper_from`.
5. `sh amp.sh report`. The report is in `out/`.

### Saved or not

The car keeps its sound settings across restarts, so each write may be saved to its memory,
and memory wears with writes. Car Sound raises the amplifier at most once every
`min_interval_s`, only when a lift crosses a whole step, and counts the writes (status
`amp_writes`, the Loudness page). Whether a write is saved shows in the car's own log: after a
sweep, look in the car report's last section for lines about saving settings next to each
`setBassMidTreb`. If the car has a write it doesn't save (one constant in the car's software
hints at an offset of 4096 for "not saved" settings), `{"method": "sound", "type_offset": 4096}`
would use it: try it parked with `sh amp.sh hold 3 0 0`, check the Bass actually changes, and
make a report.

## Car reports

Car Sound > **Save car report** (the left rail) writes a text file, with a copy in `CarSound/`
on a USB stick if one is plugged in. Over adb: `sh amp.sh report`.

It holds the head unit's model and software, the amplifier type and profile, every sound
setting with its value and whether the car has it, the volume groups, Android's audio effects
and output, Car Sound's status and log, and the car audio service's own log lines about tone,
volume and the amplifier. It leaves out serial numbers, the VIN, Bluetooth names and addresses,
locations and song details. Read it before you post it.

Post it with the "Car report" issue form, with what you heard in the sweep.
