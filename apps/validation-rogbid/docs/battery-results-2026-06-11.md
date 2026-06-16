# Rogbid Battery Trial Results - 2026-06-11/12

## Controls

- Device: Rogbid Model X, serial `46734915123233`.
- Duration: 1800 seconds per run.
- Battery sample interval: 60 seconds.
- Wi-Fi: disabled for the first idle/PCM pair and dual-camera run, enabled
  for the second idle/PCM pair.
- Vendor cleaner: `settings system isClear=0` for all valid runs.
- App: foreground service with partial wake lock, starting only after `plugged=0`.

The first attempted idle run was invalid: `com.wiite.cleantask` force-stopped
`app.solstone.validation.rogbid` shortly after `SCREEN_OFF` while `isClear=1`. After
setting `isClear=0`, a 35-second screen-off synthetic run completed cleanly.

## Valid Idle Baseline

Artifact directory on the validation host:
`/tmp/rogbid-battery-trials/20260612T024646Z-idle`

- Trial window: 2026-06-11 22:15:22 to 22:45:22.
- Battery: 100% to 96%, delta `-4` points.
- Voltage: 4259 mV to 4240 mV, delta `-19` mV.
- Temperature: 28.0 C to 26.8 C.
- Plugged samples: `0`.
- `isClear`: `0`.

## Valid Wi-Fi-Off `dual_pcm` Run

Artifact directory on the validation host:
`/tmp/rogbid-battery-trials/20260612T034107Z-dual_pcm`

- Trial window: 2026-06-11 23:10:21 to 23:40:21.
- Battery: 100% to 96%, delta `-4` points.
- Voltage: 4260 mV to 4222 mV, delta `-38` mV.
- Temperature: 29.0 C to 28.7 C.
- Plugged samples: `0`.
- `isClear`: `0`.
- `audio_input_count=2`.
- `pcm_input_0_bytes=57593856`, about 55 MiB for 30 minutes of 16 kHz 16-bit mono PCM.
- `pcm_input_1_bytes=0`; the second concurrent `AudioRecord` stream did not produce samples.

## Valid Wi-Fi-On Idle Run

Artifact directory on the validation host:
`/tmp/rogbid-battery-trials/20260612T145939Z-idle`

- Trial window: 2026-06-12 10:28:39 to 10:58:40.
- Battery: 100% to 97%, delta `-3` points.
- Voltage: 4272 mV to 4247 mV, delta `-25` mV.
- Temperature: 27.8 C to 27.0 C.
- Plugged samples: `0`.
- Network-connected samples: `31/31`.
- Active Wi-Fi samples: `31/31`.
- Wi-Fi transport samples: `31/31`.
- `isClear`: `0`.

## Valid Wi-Fi-On `dual_pcm` Run

Artifact directory on the validation host:
`/tmp/rogbid-battery-trials/20260612T161803Z-dual_pcm`

- Trial window: 2026-06-12 11:46:13 to 12:16:13.
- Battery: 100% to 97%, delta `-3` points.
- Voltage: 4285 mV to 4250 mV, delta `-35` mV.
- Temperature: 27.5 C to 27.6 C.
- Plugged samples: `0`.
- Network-connected samples: `31/31`.
- Active Wi-Fi samples: `31/31`.
- Wi-Fi transport samples: `31/31`.
- `isClear`: `0`.
- `audio_input_count=2`.
- `pcm_input_0_bytes=0`, `pcm_input_0_zero_reads=88523`.
- `pcm_input_1_bytes=57597952`, about 55 MiB for 30 minutes of 16 kHz 16-bit mono PCM.

## Valid Wi-Fi-Off `dual_camera` Run

Artifact directory on the validation host:
`/tmp/rogbid-battery-trials/20260612T172457Z-dual_camera`

- Trial window: 2026-06-12 12:53:01 to 13:23:01.
- Battery: 100% to 94%, delta `-6` points.
- Voltage: 4299 mV to 4200 mV, delta `-99` mV.
- Temperature: 29.4 C to 31.8 C.
- Plugged samples: `0`.
- Network-connected samples: `0/31`.
- Active Wi-Fi samples: `0/31`.
- Wi-Fi transport samples: `0/31`.
- `isClear`: `0`.
- `camera_count=2`, `camera_capture_count=2`.
- `camera_interval_seconds=5`.
- `camera_cycles=360`.
- `camera_0_frames=360`, `camera_0_bytes=7317586`, `camera_0_failures=0`.
- `camera_1_frames=360`, `camera_1_bytes=7604824`, `camera_1_failures=0`.
- `camera_total_frames=720`, `camera_total_bytes=14922410`, `camera_total_failures=0`.
- Pull summary reported `camera_file_count=720` and `camera_kb=16544`.

## Readout

At the battery-percentage level:

- Wi-Fi off idle: `-4` points over 30 minutes, about `-8` points/hour.
- Wi-Fi off one-active-PCM: `-4` points over 30 minutes, about `-8` points/hour.
- Wi-Fi on idle: `-3` points over 30 minutes, about `-6` points/hour.
- Wi-Fi on one-active-PCM: `-3` points over 30 minutes, about `-6` points/hour.
- Wi-Fi off dual-camera stills every 5 seconds: `-6` points over 30
  minutes, about `-12` points/hour.

Voltage slopes were steeper with PCM in both pairs, and steepest with repeated
camera use:

- Wi-Fi off idle: `-38` mV/hour.
- Wi-Fi off one-active-PCM: `-76` mV/hour.
- Wi-Fi on idle: `-50` mV/hour.
- Wi-Fi on one-active-PCM: `-70` mV/hour.
- Wi-Fi off dual-camera stills every 5 seconds: `-198` mV/hour.

The five-run set validates the local fixed-window drain harness, verifies that
Wi-Fi stayed active during the Wi-Fi-on trials and stayed inactive during the
dual-camera trial, and does not show an obvious large battery penalty from
either Wi-Fi or one active raw PCM stream over a 30-minute window. The
dual-camera still-capture run does show a larger drain signal and a temperature
rise, so camera use looks meaningfully more expensive than the idle and PCM
conditions. Because this is one run per condition and the percentage gauge is
coarse, treat the result as directional.

This still does not validate true two-mic concurrent capture. Both `dual_pcm`
runs produced one active raw PCM stream and one zero-byte stream; the active
route flipped between runs. The second Wi-Fi-on run added read diagnostics and
showed the silent route returned repeated zero-length reads rather than hard
read errors.

The camera run does validate both camera IDs as usable under the screen-off
foreground-service harness. It captured 360 frames per camera over 30 minutes,
with no failures and about 14.2 MiB of JPEG payload.
