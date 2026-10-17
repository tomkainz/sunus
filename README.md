# Sunus

**Sonos Bridge** for Android, successor to [tomkainz/sonos-bridge](https://github.com/tomkainz/sonos-bridge)

An Android app that plays your phone's audio live on a Sonos speaker. The phone takes the role of a Bluetooth source, only over Wi-Fi and without an extra computer.

The first Sonos Bridge needed a Linux machine as the Bluetooth receiver. The phone now does that job itself.

Current release: version 1.0, beta.

## Built with AI

This app was built with the help of AI, and it works anyway. There is always skepticism about that, but something can be useful all the same.

## How it works

The app captures the audio and serves it at `http://<phone>:8090/live.wav` as an endless WAV stream (PCM, 16 bit, 48 kHz, uncompressed). Via UPnP on port 1400 the Sonos is given this address and pulls the stream. Every few seconds the app checks whether the speaker is still on the stream and reconnects it after a dropout. Choosing a different source in the Sonos app ends the stream as well.

The app finds speakers via SSDP. If the router filters multicast, it scans the local subnet on port 1400. As a last resort the IP can be entered by hand.

## Capture modes

The app's interface is in German. Button names are given below in German with a translation.

### Alle Apps (all apps)

Captures all media audio, including apps that block normal recording. The phone itself stays silent.

A small helper (`shell/SubmixRecorder`) runs with shell privileges and reads the `REMOTE_SUBMIX` source. It is started over an ADB connection that the app opens to itself. No root and no PC needed. scrcpy works on the same principle with `--audio-source=output`.

One-time setup:

1. In the app, tap **Kopplung starten** (start pairing). The **Wireless debugging** page opens.
2. Turn it on and tap **Pair device with pairing code**.
3. Leave the dialog open, pull down the notification shade and enter the 6-digit code in the **Sunus koppeln** notification.

The app finds the port itself via mDNS. The code is entered through the notification because Android closes the pairing dialog as soon as you leave Settings. There is no public intent for the Wireless debugging page; the app takes the same route as a long press on the Quick Settings tile. Where that isn't available, Developer options open instead.

While pairing, the app grants itself `WRITE_SECURE_SETTINGS` and the `PROJECT_MEDIA` app op once via the shell. With that it turns Wireless debugging back on by itself after a reboot or a Wi-Fi change, like Shizuku does since version 13.6. Requirements: Developer options stay enabled, and the Wi-Fi network was confirmed once with "Always allow on this network". The app remembers the ADB port; with a known port the connection is up in a fraction of a second, otherwise it searches via mDNS. This mode requires Android 12.

### Standard

Needs no setup and uses the official `AudioPlaybackCapture` API, for which Android asks for consent on every start. Once "Alle Apps" has been set up, that prompt no longer appears. Apps may opt out of this capture; Netflix and Audible do, for example. The phone keeps playing the audio in parallel. If you don't want that, turn down the media volume on the phone; according to user reports the stream to the Sonos is not affected.

## Requirements

- Android 10 or newer
- Phone and Sonos on the same Wi-Fi
- UPnP enabled in the Sonos app under Account > Privacy & Security > Connection Security (default)
- From Android 17: the local network permission

Tested with Android 17 and a Sonos Play:1 running S2 software.

## Latency

The stream is lossless but arrives at the speaker an estimated one to three seconds late. Most of that is probably buffered by the Sonos itself. Fine for music; for video, picture and sound drift apart.

## Error codes

If a start fails, the app shows a code such as `SL-A1` and writes an error report with the device, Android version and the helper's log lines. It can be shared straight from the message or under "⋯", or saved to Downloads.

## Installation

The APK is attached to the release. Copy it to the phone, open it in a file manager and allow installs from unknown sources once.

## Building

You need JDK 21 and the Android SDK with platform 37. Set the SDK path via `ANDROID_HOME` or as `sdk.dir` in `local.properties`.

```
gradlew.bat assembleRelease
```

Release builds are signed with `keystore/sonoslive.jks`. The key is not in the repository; without it, `gradlew.bat assembleDebug` produces an installable debug APK.

AGP 9.3.1, Kotlin Compose plugin 2.4.10, compileSdk 37, minSdk 29.

## Structure

- `Sonos.kt`: discovery, UPnP control, groups, volume
- `LiveStreamServer.kt`: WAV stream for any number of clients
- `CaptureEngine.kt`: capture in both modes
- `LiveService.kt`: foreground service and connection watchdog
- `adb/`: pairing and starting the helper
- `Diagnose.kt`: error codes and error report
- `shell/`: the helper that runs as a shell process
- `Ui.kt`, `MainActivity.kt`: interface and permissions

## Open questions

- Does "Alle Apps" keep running with the screen locked?
- Can Spotify be captured in Standard mode?
- Does the stream stay at full level in Standard mode when the phone's media volume is at zero?
- The latency is estimated, not measured.

## Third-party code

- [libadb-android](https://github.com/MuntashirAkon/libadb-android) (GPL-3.0-or-later or Apache-2.0) for the ADB connection
- [sun-security-android](https://github.com/MuntashirAkon/sun-security-android) (GPL-2.0) for the ADB certificate
- [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) (Apache-2.0)
- `shell/ShellRuntime.java` and `shell/ShellContext.java` follow [scrcpy](https://github.com/Genymobile/scrcpy) (Apache-2.0)
