# Sonos Bridge 2.0

Android-App, die den Ton des Handys live auf einem Sonos-Lautsprecher abspielt. Das Handy übernimmt dabei die Rolle eines Bluetooth-Zuspielers, nur über WLAN und ohne zusätzlichen Rechner. Auf dem Handy heißt die App „Sonos Live“.

Die erste Version, [sonos-bridge](https://github.com/tomkainz/sonos-bridge), brauchte einen Linux-Rechner als Bluetooth-Empfänger. Diese Rolle übernimmt jetzt das Handy selbst.

## Funktionsweise

Die App nimmt den Ton auf und stellt ihn unter `http://<handy>:8090/live.wav` als endlosen WAV-Stream bereit (PCM, 16 Bit, 48 kHz, unkomprimiert). Per UPnP auf Port 1400 bekommt der Sonos diese Adresse genannt und holt sich den Stream ab. Alle paar Sekunden prüft die App, ob der Lautsprecher noch am Stream hängt, und verbindet ihn nach einem Abbruch neu. Wer in der Sonos-App eine andere Quelle wählt, beendet damit auch den Stream.

Lautsprecher findet die App per SSDP. Filtert der Router Multicast, sucht sie das eigene Subnetz auf Port 1400 ab. Notfalls lässt sich die IP von Hand eintragen.

## Betriebsarten

### Alle Apps

Erfasst den gesamten Medienton, auch von Apps, die normale Aufnahmen sperren. Das Handy selbst bleibt dabei stumm.

Ein kleiner Helfer (`shell/SubmixRecorder`) läuft mit Shell-Rechten und liest die Quelle `REMOTE_SUBMIX`. Gestartet wird er über eine ADB-Verbindung, die die App zu sich selbst aufbaut. Root braucht es dafür nicht, einen PC auch nicht. Nach demselben Prinzip arbeitet scrcpy mit `--audio-source=output`.

Einmalige Einrichtung:

1. In der App auf „Kopplung starten“ tippen. Die Entwickleroptionen öffnen sich.
2. „Kabelloses Debugging“ einschalten, antippen und „Gerät mit Kopplungscode koppeln“ wählen.
3. Den Dialog offen lassen, die Benachrichtigungsleiste herunterziehen und bei „Sonos Live koppeln“ den 6-stelligen Code eingeben.

Den Port sucht die App selbst per mDNS. Der Code wird über die Benachrichtigung abgefragt, weil Android den Kopplungsdialog schließt, sobald man die Einstellungen verlässt. Nach einem Neustart des Handys muss „Kabelloses Debugging“ wieder eingeschaltet werden, die Kopplung selbst bleibt erhalten. Diese Betriebsart setzt Android 12 voraus.

### Standard

Kommt ohne Einrichtung aus und nutzt die offizielle Schnittstelle `AudioPlaybackCapture`, für die Android bei jedem Start eine Zustimmung verlangt. Apps dürfen sich dieser Aufnahme entziehen, Netflix und Audible tun das zum Beispiel. Am Handy läuft der Ton parallel weiter. Wer das nicht will, dreht dort die Medienlautstärke herunter; nach Erfahrungsberichten bleibt der Stream zum Sonos davon unberührt.

## Voraussetzungen

- Android 10 oder neuer
- Handy und Sonos im selben WLAN
- In der Sonos-App unter Konto > Datenschutz und Sicherheit > Verbindungssicherheit ist UPnP eingeschaltet (Werkseinstellung)
- Ab Android 17 die Berechtigung für das lokale Netzwerk

Getestet mit Android 17 und einem Sonos Play:1 mit S2-Software.

## Verzögerung

Der Stream ist verlustfrei, kommt aber geschätzt ein bis drei Sekunden später am Lautsprecher an. Den größten Teil davon puffert vermutlich der Sonos selbst. Bei Musik stört das nicht, bei Videos laufen Bild und Ton auseinander.

## Installation

Die fertige APK hängt am Release. Aufs Handy kopieren, im Dateimanager öffnen und einmalig die Installation aus unbekannten Quellen erlauben.

## Bauen

Gebraucht werden JDK 21 und das Android-SDK mit Platform 37. Den SDK-Pfad entweder über `ANDROID_HOME` setzen oder in `local.properties` als `sdk.dir` eintragen.

```
gradlew.bat assembleRelease
```

Signiert wird mit `keystore/sonoslive.jks`. Der Schlüssel liegt nicht im Repository; ohne ihn ergibt `gradlew.bat assembleDebug` eine installierbare Debug-APK.

AGP 9.3.1, Kotlin-Compose-Plugin 2.4.10, compileSdk 37, minSdk 29.

## Aufbau

- `Sonos.kt`: Suche, UPnP-Steuerung, Gruppen, Lautstärke
- `LiveStreamServer.kt`: WAV-Stream für beliebig viele Abrufer
- `CaptureEngine.kt`: Aufnahme in beiden Betriebsarten
- `LiveService.kt`: Vordergrunddienst und Verbindungskontrolle
- `adb/`: Kopplung und Start des Helfers
- `shell/`: der Helfer, der als Shell-Prozess läuft
- `Ui.kt`, `MainActivity.kt`: Oberfläche und Berechtigungen

## Noch offen

- Läuft „Alle Apps“ bei gesperrtem Bildschirm dauerhaft weiter?
- Lässt sich Spotify im Standard-Modus aufnehmen?
- Bleibt der Stream im Standard-Modus voll, wenn die Medienlautstärke am Handy auf null steht?
- Die Verzögerung ist geschätzt, nicht gemessen.

## Fremdcode

- [libadb-android](https://github.com/MuntashirAkon/libadb-android) (GPL-3.0-or-later oder Apache-2.0) für die ADB-Verbindung
- [sun-security-android](https://github.com/MuntashirAkon/sun-security-android) (GPL-2.0) für das ADB-Zertifikat
- [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) (Apache-2.0)
- `shell/ShellRuntime.java` und `shell/ShellContext.java` folgen [scrcpy](https://github.com/Genymobile/scrcpy) (Apache-2.0)
