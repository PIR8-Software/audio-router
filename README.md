# Audio Router

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Android audio output selector for USB-C displays, lapdocks, and desktop mode.
When the external screen steals media output from the phone speaker or a
Bluetooth device, this app puts a compact chooser on that external screen so
you can pick the output you actually want. No root.

Package `com.roy.bluetoothglassesrouter`. Version name `0.38-session-done-wiring`,
versionCode 39. Project source is MIT; see [LICENSE](LICENSE) and [NOTICE](NOTICE).

## Who it is for

You dock a phone to a USB-C monitor, lapdock, or desktop-mode display, and
media starts playing on the display instead of the phone speaker or the
Bluetooth device you were using. The system output list exists, but it is easy
to miss on the phone and it does not sit on the external screen as a normal
chooser.

Audio Router is for that case. It is not a general equalizer, not a call-audio
router, and not a microphone switch.

## What you get

After you enable the accessibility service, attaching an external display can
open a compact Android-style chooser on that screen. The rows are the outputs
the system chooser is offering right now, not a saved catalog and not a guess.

Each real output row can be marked **Preferred** or **Fallback**. The two roles
are distinct. A saved role stays associated with that exact row name while the
device is away, and is used again only if that same row is offered live.

**Auto-select (5s)** is optional and off by default. On a fresh popup it counts
down visibly, then selects Preferred if that row is live and usable, otherwise
Fallback. If the chosen role is absent, ambiguous, or unreadable, the popup
stays open and says so. Manual selection, Done, and turning Auto-select off
cancel the countdown immediately.

Manual selection refreshes the list and leaves the chooser open until Done.
A confirmed automatic selection closes both owned windows — the custom popup
and the session-owned native picker — without a second Done tap. If the live
source does not confirm the route, both stay open and the reason stays visible.
The app does not claim the route changed unless the live system row shows it.

The service can keep working in the background, including after a reboot once
the phone is unlocked, while the accessibility service stays enabled. Android
does not restart an app after the user force-stops it until the user opens it
again. This app does not hide that.

## Why it needs Accessibility

The app cannot call a public API that sets the system media output for every
other app. That control is a privileged audio-policy operation. Instead, with
the accessibility service you enable yourself, Audio Router inspects the
system media-output chooser and clicks the one verified row that matches your
choice.

That means:

- You must turn the service on in system Settings and accept the warning.
- On Android 13 and newer, sideloaded apps often need **Allow restricted
  settings** on the app info screen before Accessibility can be enabled. The
  app never clicks that dialog for you.
- No root, no Shizuku, no Internet permission, and no Bluetooth reset.
- A native helper (the system volume panel, then the system output picker) may
  briefly appear on the phone display only. It is not supposed to appear on
  the external screen. Placement is whatever the OS actually does; the app
  records observed window identity and does not assume a display id.
- This is not a privileged audio-policy API. It is not call routing and it is
  not microphone routing.
- The click path depends on the exact system chooser UI. A different Android
  version, OEM skin, or chooser layout can make acquisition fail closed (no
  click) even when the phone still shows a media output list.
- There is no universal compatibility claim.

If the system chooser cannot be read, the popup can show the last real list
marked stale. A tap on a stale row rechecks the live list before any click.
Refresh asks the system chooser again. Done closes the popup.

## Compatibility

`minSdk` is 26. `compileSdk` and `targetSdk` are 34. That is the build
contract, not a promise that every Android version in between works.

Owner hardware evidence is narrow: a Pixel 11 running Android 17. The owner
reported a successful real reboot on the accepted **0.35** build (versionCode
36). The reviewed **0.38** source in this repository (versionCode 39) does not
include a reboot proof taken after that update. Do not read the 0.35 reboot as
proof that 0.38 still resumes after boot. Sound and hotplug still need to be
confirmed by ear on your own setup.

## Setup

1. Build or otherwise obtain a debug APK and sideload it. Allow **Install
   unknown apps** only for the installer you use, then revoke it if you do not
   need it.
2. Open the app info page. On Android 13+, if Accessibility is blocked, use
   the menu and **Allow restricted settings**.
3. Settings → Accessibility → installed apps → Audio Router. Read the warning.
   Enable the service only if you accept it.
4. Leave **Open the audio selector popup on display attach** on. Attach the
   display. Choose an output on the external screen. Confirm the sound by ear.

The app requests no runtime permissions. `RECEIVE_BOOT_COMPLETED` is an
install-time normal permission so the platform can deliver boot and update
broadcasts. Nothing starts before the user unlocks the phone.

## Build

Pinned toolchain: Java 17, Android SDK 34, Gradle 8.9 (wrapper), Android
Gradle Plugin 8.7.3.

```
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

`testDebugUnitTest` is the single unit-test variant. A clean run of this
candidate completed 208 tests in 20 suites, with 0 failures, 0 errors, and 0
skipped. `lintDebug` reported 0 errors and 10 warnings. Do not commit
`local.properties`, keystores, or build outputs. Debug APK path after a
successful assemble:

`app/build/outputs/apk/debug/app-debug.apk`

An optional emulator-only instrumentation APK can render a synthetic fixture
popup for screenshots. It refuses non-emulator hardware. It is not the app
you install for daily use. This source drop does not include screenshots.
Capture the fixture on a clean emulator if you want illustrations, and do not
commit pictures from a personal phone.

```
./gradlew :app:assembleDebugAndroidTest
adb -s <emulator-serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <emulator-serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <emulator-serial> shell am instrument -w -e fixture fresh \
  com.roy.bluetoothglassesrouter.test/com.roy.bluetoothglassesrouter.SmokeInstrumentation
```

## Privacy

This repository is source only. It does not include operator notes, device
logs, serials, network addresses, or screenshots from a personal phone.
Tests use synthetic output names. The app has no Internet permission and no
telemetry channel. If you file an issue, do not attach real device names,
media titles, or identifiers. See [SECURITY.md](SECURITY.md).

## License

Copyright (c) 2026 PIR8-Software. Project source is licensed under the MIT
License. The Gradle Wrapper JAR remains Apache License 2.0 and is not
relicensed. See [NOTICE](NOTICE).
