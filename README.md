# Sunus

**Sonos Bridge** for Android, successor to [tomkainz/sonos-bridge](https://github.com/tomkainz/sonos-bridge)

An Android app that plays your phone's audio live on a Sonos speaker. The phone acts like a Bluetooth source, except the audio goes over Wi-Fi and no extra computer is involved.

The first Sonos Bridge needed a Linux machine as the Bluetooth receiver. Here the phone does that job itself.

Current release: version 1.5, beta.

## Built with AI

I built this app with the help of AI. Plenty of people are skeptical about that, and I understand why. It works anyway.

## How it works

The app captures the audio and serves it at `http://<phone>:8090/live.wav` as an endless WAV stream (PCM, 16 bit, 48 kHz, uncompressed). Via UPnP on port 1400 the Sonos is given this address and pulls the stream. Only Sonos speakers on the network may fetch the stream; other devices get an error. Every few seconds the app checks whether the speaker is still on the stream and reconnects it after a dropout. Choosing a different source in the Sonos app ends the stream as well.

The app finds speakers via SSDP. If the router filters multicast, it scans the local subnet on port 1400. As a last resort the IP can be entered by hand.

## Capture modes

### All apps

Captures all media audio, including apps that block normal recording. The phone itself stays silent.

A small helper (`shell/SubmixRecorder`) runs with shell privileges and reads the `REMOTE_SUBMIX` source. The app starts it over an ADB connection to itself, so you need neither root nor a PC. scrcpy works on the same principle with `--audio-source=output`.

One-time setup:

1. In the app, tap **Start pairing**. The **Wireless debugging** page opens.
2. Turn it on and tap **Pair device with pairing code**.
3. Leave the dialog open, pull down the notification shade and enter the 6-digit code in the **Pair Sunus** notification.

The app finds the port itself via mDNS and, if that fails, by scanning the phone's own ports. If neither works, the notification asks for the code and the port shown in the dialog together, for example `123456 37123`. You enter the code in the notification because Android closes the pairing dialog as soon as you leave Settings. There is no public intent for the Wireless debugging page; the app takes the same route as a long press on the Quick Settings tile. Where that isn't available, Developer options open instead.

During pairing the app uses the shell once to grant itself `WRITE_SECURE_SETTINGS` and the `PROJECT_MEDIA` app op. That lets it turn Wireless debugging back on after a reboot or a Wi-Fi change, as Shizuku has done since version 13.6. It also sets `adb_allowed_connection_time` to 0, so the pairing does not expire after a week without use. That setting applies to every ADB authorization on the phone, including computers; "Revoke USB debugging authorizations" in Developer options still removes them all. Requirements: Developer options stay enabled, and the Wi-Fi network was confirmed once with "Always allow on this network". The app remembers the ADB port. With a known port the connection is up in a fraction of a second; otherwise it searches via mDNS and, in parallel, probes the local ports for the ADB service. This mode requires Android 12.

### Standard

This mode needs no setup. It uses the official `AudioPlaybackCapture` API, and Android asks for consent on every start. Once "All apps" has been set up, that prompt no longer appears. Apps may opt out of this capture; Netflix and Audible do, for example. The phone keeps playing the audio as well. If you don't want that, turn down the phone's media volume; users report that this does not affect the stream to the Sonos.

## Sound settings

Below the volume, the **Equalizer** card adjusts the selected speaker like the Sonos app does: bass and treble from −10 to +10, Loudness, and four presets (Flat, Bass, Warm, Vocal). Home theater speakers such as the Arc, Beam or Ray also get Night Sound and Speech Enhancement; on other speakers these stay hidden. The settings live on the speaker, so they also apply to everything else it plays. Tapping the speaker icon next to the volume slider mutes the speaker.

## Now playing

Under "⋯", **Show what's playing** adds a card with the title, artist and cover of whichever app is playing, with play/pause, next and previous. It uses Android's media sessions, so it works with any music app that has one. Android calls the permission notification access; Sunus uses only the media controls and does not read your notifications. If "All apps" is set up, the app turns it on through its ADB shell. Otherwise Android's settings open, and for an APK installed by hand you may first have to choose App info > ⋮ > Allow restricted settings.

## Automation

Under "⋯", **Control by other apps** lets Tasker, Home Assistant and others control Sunus. It is off by default, because while it is on, any app on the phone can start the stream.

| Action | Extra | What it does |
|---|---|---|
| `de.tomkainz.sonoslive.action.START` | optional `speaker` | Starts the stream |
| `de.tomkainz.sonoslive.action.STOP` | | Stops the stream |
| `de.tomkainz.sonoslive.action.SELECT_SPEAKER` | `speaker` | Selects a speaker |

`speaker` is a room name or an IP address on the local network. Send the action as an activity intent, for example:

```
adb shell am start -n de.tomkainz.sonoslive/.AutomationActivity -a de.tomkainz.sonoslive.action.START --es speaker "Living Room"
```

Android restricts background apps from starting activities, so the sending app may need a permission for that, such as "Display over other apps". In Standard mode, or before "All apps" is set up, START opens Sunus, because Android may ask for consent or the setup has to be done there.

## Requirements

- Android 10 or newer
- Phone and Sonos on the same Wi-Fi
- UPnP enabled in the Sonos app under Account > Privacy & Security > Connection Security (default)
- From Android 17: the local network permission

Tested with Android 17 and a Sonos Play:1 running S2 software.

## Compatibility

| Speaker | Android | Status |
|---|---|---|
| Play:1 (S2) | 17 | Works, used by the author |
| Arc Ultra, One SL | 16 | The stream did not start on 1.0 Beta ([#1](https://github.com/tomkainz/sunus/issues/1)). 1.5 Beta has a fix that no user has confirmed yet |

[BitChord](https://github.com/kushagrasinghx/BitChord) 1.9 was captured in both modes in the emulator, with its sound showing up in the stream and stopping when paused. Now playing also shows and controls it. This has not been tried on a real speaker.

Tried it on another speaker or phone? Please open an issue, whether it worked or not.

## Latency

The stream is lossless but reaches the speaker an estimated one to three seconds late, most of it probably buffered by the Sonos itself. That is fine for music. With video, picture and sound drift apart.

## Error codes

If a start fails, the app shows a code such as `SL-A1` and writes an error report with the device, Android version and the helper's log lines. You can share the report straight from the message or under "⋯", or save it to Downloads.

## Installation

Download the APK from the [Releases page](https://github.com/tomkainz/sunus/releases).

## Building

You need JDK 21 and the Android SDK with platform 37. Set the SDK path via `ANDROID_HOME` or as `sdk.dir` in `local.properties`.

```
gradlew.bat assembleRelease
```

On Linux and macOS, use `./gradlew` instead of `gradlew.bat`. `testDebugUnitTest` runs the unit tests.

Release builds are signed with `keystore/sonoslive.jks`, whose password is read from `sunus.keystore.password` in `local.properties`. Neither is in the repository; without them, `gradlew.bat assembleDebug` produces an installable debug APK.

AGP 9.3.1, Kotlin Compose plugin 2.4.10, compileSdk 37, minSdk 29.

## Structure

- `Sonos.kt`: discovery, UPnP control, groups, volume and sound settings
- `LiveStreamServer.kt`: WAV stream for any number of clients
- `CaptureEngine.kt`: capture in both modes
- `LiveService.kt`: foreground service and connection watchdog
- `adb/`: pairing and starting the helper
- `Diagnose.kt`: error codes and error report
- `shell/`: the helper that runs as a shell process
- `Ui.kt`, `MainActivity.kt`: interface and permissions

## Open questions

- Does "All apps" keep running with the screen locked?
- Can Spotify be captured in Standard mode?
- In the emulator, Standard mode stays at full level with the phone's media volume at zero, while All apps follows the media volume and goes silent at zero. The app warns about a low volume when All apps starts. Is it the same on real phones?
- The latency is estimated, not measured.
- Night Sound and Speech Enhancement have only been tried against a simulated speaker, not a real soundbar.

## License

Sunus is released under the [MIT License](LICENSE).

Three source files are adapted from Apache-2.0 projects and stay under the Apache License 2.0, as noted in their headers: `shell/ShellRuntime.java` and `shell/ShellContext.java` (from scrcpy) and `adb/AdbKeyManager.java` (from the libadb-android example).

## Third-party code

| Component | Used for | License |
|---|---|---|
| [libadb-android](https://github.com/MuntashirAkon/libadb-android) 3.1.1 | ADB connection and pairing | GPL-3.0-or-later or Apache-2.0, used under Apache-2.0; parts BSD-3-Clause and MIT |
| [spake2-java](https://github.com/MuntashirAkon/spake2-java) 2.2.1 (via libadb-android) | pairing code exchange | LGPL-3.0; native part also ISC, MIT, LGPL-2.1, Apache-2.0 |
| [sun-security-android](https://github.com/MuntashirAkon/sun-security-android) 1.1 | ADB certificate | GPL-2.0 with Classpath exception |
| [Bouncy Castle](https://www.bouncycastle.org) 1.81 (via libadb-android) | cryptography | MIT |
| [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) 6.2 | access to the system TLS stack | Apache-2.0 |
| [scrcpy](https://github.com/Genymobile/scrcpy) | template for the shell helper | Apache-2.0 |
| AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines | app framework | Apache-2.0 |

All libraries are used unmodified. The Classpath exception keeps GPL-2.0 from extending to Sunus. To swap the LGPL library for a modified version, build Sunus from source. The source code of sun-security-android and spake2 is in [`third_party`](third_party).

Copyright notices and the full license texts are in [`app/src/main/assets/licenses`](app/src/main/assets/licenses) and in the app under "⋯" > **Open-source licenses**.
