# WatchoutRF Desktop

A spectrum analyzer for RTL-SDR dongles, built for coordinating wireless microphones and in-ear monitors at live shows. It sweeps a frequency range, shows the spectrum and a waterfall, lets you name the frequencies your devices use, and exports scans to Shure Wireless Workbench.

This is the macOS desktop app. The Android app lives in [samh-va/Watchout-RF](https://github.com/samh-va/Watchout-RF).

For how SDR, the FFT and the processing pipeline work, see [WatchoutRF_Documentation.md](WatchoutRF_Documentation.md) (in Spanish).

## Features

- **Sweep any range from 24 MHz to 1766 MHz**, with presets for FM, VHF TV, UHF TV, IEM/wireless mic, cellular 700 and ISM 900.
- **Live spectrum and waterfall.** The trace redraws as the sweep moves across the range instead of waiting for the sweep to finish.
- **Max Hold** trace, with a reset button.
- **Auto Peaks**: the strongest signals are marked automatically. Switch them off when you only want your own markers.
- **Markers** for your wireless devices: a name, an exact frequency, a colour and an optional device note such as `ULXD4 ch 3`.
- **Shows**: save the whole setup (range, settings, markers) under a show name, then search and reload it later.
- **Export to Wireless Workbench 7** as a CSV scan file.
- **TV channel band plan** above the spectrum.
- **Demo mode** with synthetic signals when no dongle is connected. A dongle plugged in later is picked up within a few seconds.

## Requirements

- macOS on Apple Silicon.
- An RTL-SDR dongle with an R820T/R820T2 tuner (developed with a Nooelec NESDR SMART).
- To build from source: JDK 17 and Homebrew's `librtlsdr`.

```bash
brew install openjdk@17 librtlsdr
```

The packaged app bundles `librtlsdr` and `libusb`, so a Mac that only runs the installed app needs neither Homebrew nor a JDK.

## Run from source

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew run
```

## Build the installer

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew packageDmg
```

The installer is written to `build/compose/binaries/main/dmg/WatchoutRF-1.0.0.dmg`, and the app itself to `build/compose/binaries/main/app/WatchoutRF.app`.

Before packaging, Gradle runs `scripts/bundle_native_libs.sh`, which copies `librtlsdr` and `libusb` from Homebrew into `resources/`, makes them relocatable and re-signs them.

The app is not signed with an Apple Developer ID. On another Mac, open it the first time with right-click → Open.

## Using the app

1. Plug in the dongle and start the app. The home screen shows whether it was found.
2. Open the spectrum screen. Scanning starts by itself.
3. Pick a frequency range at the top of the left panel.

### Left panel

| Control | What it does |
|---|---|
| Frequency presets | Choose the range to sweep. Changing it clears the traces and markers. |
| Scan mode | Continuous, or a single sweep. |
| Max Hold | Keeps the highest level seen at each frequency. |
| Auto Peaks | Shows or hides the automatically detected peak markers. |
| Marker list | Your markers. Click the pencil to rename one, or a colour dot to recolour it. |
| SCAN / STOP | Starts or stops sweeping. |
| Reset Hold | Clears the Max Hold trace. |
| Add Marker | Adds a marker where the cursor is on the spectrum. |
| Clear / Manage | Removes all markers, or opens the marker manager. |
| Shows | Saves or loads a whole setup. |
| Export WWB7 | Saves the current scan for Wireless Workbench. |

Drag the thin grip between the left panel and the spectrum to make the panel wider. The width is remembered.

### Markers

Click or drag on the spectrum to place the cursor, then press **Add Marker**. For an exact frequency, open **Manage** and type it in MHz (`566.35` and `566,35` both work), with a label and, if you want, the device on that frequency.

### Shows

Open **Shows**, type a show name and press **SAVE**. A show stores:

- the frequency range and resolution,
- scan mode, Max Hold, Auto Peaks and gain settings,
- every marker with its name, frequency, colour and device,
- optional notes, and the name of the dongle that was connected.

The search box matches the show name, notes, date, and marker names and devices. **LOAD** restores the setup and starts the traces fresh. Saving under an existing name replaces that show.

Shows are plain text files, one per show, in:

```
~/Library/Application Support/WatchoutRF/shows
```

Copy that folder to back up your shows or move them to another Mac.

### Export to Wireless Workbench

**Export WWB7** writes one `frequency_MHz,level_dBm` line per point. If Max Hold is on, the Max Hold trace is exported; otherwise the current trace. In Wireless Workbench, import the file as a scan.

## Things to know

- **Levels are not calibrated.** The dBm scale is relative: use it to compare signals and find clear spectrum, not to read absolute power.
- **Sweep speed is limited by the tuner.** Each retune takes about 26 ms, so the 470–698 MHz range takes about 3.7 s per sweep. Narrower ranges are proportionally faster.
- **There is a small blind spot at the centre of each tuner hop** (about 25 kHz wide), where the receiver's own DC spike is removed.
- **Only the first dongle is used** if several are connected.

## Development

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test
```

The unit tests cover the FFT, sweep stitching, averaging, peak detection, the WWB export and show storage. One extra test runs real tuner hops when a dongle is attached and is skipped otherwise.

### Project layout

```
src/main/kotlin/
  Main.kt, RtlSdr.kt            app entry point, librtlsdr binding (JNA)
  com/watchoutrf/desktop/
    data/sdr/                   dongle access
    data/dsp/                   FFT, sweep stitching, averaging, peak detection
    data/export/                Wireless Workbench export
    data/shows/                 saved shows
    domain/model/               ranges, markers, scan settings
    ui/                         Compose screens and components
scripts/bundle_native_libs.sh   bundles librtlsdr and libusb for packaging
```

Built with Kotlin, Compose Multiplatform for Desktop, and librtlsdr through JNA.

## Third-party libraries

The packaged app includes [librtlsdr](https://osmocom.org/projects/rtl-sdr) (GPL-2.0) and [libusb](https://libusb.info) (LGPL-2.1). Check their terms before distributing the app to others.
