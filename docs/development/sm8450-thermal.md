# Snapdragon 8 Gen 1 (SM8450): heat and frame rate

Measured on 2026-10-30 on a Redmi K50 Gaming (`ingres`, 21121210G), Android 14,
HyperOS 2.0.3, 12 GB, 1080x2400 120 Hz panel, Adreno 730. DroidDeck 0.6.7
(`-antutu` variant, package `com.antutu.benchmark.full`), runtime r9, session
1600x720, scaling Linear, frame generation off, graphics driver Auto
(Banners-Turnip). Xiaomi's Performance Mode was on; the phone was on USB power.

`Skul: The Hero Slayer` (Steam app 1147560) was used because its title screen and
gameplay are light enough that the frame rate follows the limit instead of the CPU.

## The thermal HAL is not usable on this ROM

HyperOS never initialises ThermalHAL. `dumpsys thermalservice` reports
`Thermal Status: 0` and no temperatures, and `PowerManager.getThermalHeadroom()`
returns NaN, even while `cpu-1-5` reads 94 °C. `ThermalGuard` therefore also
reads the hottest CPU zone from `cpuTempZones()` and the GPU zone from
`gpuTempSource()`, and caps when any signal is hot.

## Frame limit

Steam's client setting `gamescope_app_target_framerate` (protobuf field 21002,
`SteamClient.Settings.SetSetting`) is the frame limit. The panel stayed at
120 Hz for both runs; 60 divides 120, so pacing stays even.

| Limit | FPS on screen | CPU (`cpu-1-5`) | GPU (`gpuss-0/1`) | Load |
|---|---|---|---|---|
| 120 | 120 | 82-95 °C | 69-70 °C | CPU 58-65 %, GPU 59-63 % |
| 60 (title) | 59.0 | 60-74 °C | 54-58 °C | CPU 60-75 %, GPU 27-59 % |
| 60 (gameplay) | 56.7 | — | — | 0.7 % off cadence |

At 60 fps the mid and big cores sat at 2.2-2.5 GHz and 1.7-2.5 GHz; the little
cores stayed at their 1.78 GHz cap. The 120 fps run was already at the top of
the thermal range and would throttle; 60 fps is the better sustained setting
for this SoC. 40 fps (a third of the panel) costs more smoothness than the
temperature it saves.

## Recommended settings for this device

- Steam Frame Limit: **60** (Quick Access Menu > Performance) on the 120 Hz
  panel. 120 is usable for short sessions and light games.
- Session resolution 1600x720, Linear scaling. The panel is 20:9, so this fills
  it without pillarboxing.
- Graphics driver Auto (Banners-Turnip); frame generation off; GPU clock pin
  off; ThermalGuard on.
- Xiaomi Performance Mode on for sessions. It raises the cluster ceilings to
  1.78 / 2.50 / 3.00 GHz; the device still throttles under a 120 fps load.
- Keep the phone out of direct sun and off a bed; at 60 fps the back stays
  comfortable, at 120 fps it does not.

## Reproducing

```sh
export DROIDDECK_PACKAGE=com.antutu.benchmark.full
export ADB=$HOME/Android/platform-tools/adb
tools/droiddeckctl start steam --wait
tools/droiddeckctl launch 1147560 --wait
tools/droiddeckctl pacing --seconds 30
tools/droiddeckctl cdp eval 'JSON.stringify({fps:window.settingsStore.clientSettings.gamescope_app_target_framerate})'
```

Enable **Setup > Diagnostics > Debugging tools** first for `cdp` and `guest`.
The frame limit is written through `SteamClient.Settings.SetSetting` with the
varint bytes for `21002 * 8` and the rate; the code is in
`app/src/main/assets/linuxfs/usr/local/bin/droiddeck-steam-compat`.
