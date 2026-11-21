# Contributing

Small fixes and compatibility notes are welcome. This repository is the public
source for Audio Router. Do not send private device captures.

## Ground rules

- Keep the application id and class names stable unless a change truly requires
  a new identity. Renaming the package breaks updates for existing installs.
- Do not add keystores, `local.properties`, `.env` files, recordings, or build
  outputs. Those paths are gitignored on purpose.
- Tests must use synthetic output names. Do not paste native UI dumps that
  contain real device names, media titles, or hardware identifiers.
- Do not weaken a failing check to make a fixture pass. If a synthetic name
  must stay related across tests, use the same synthetic name everywhere.
- Accessibility automation must stay fail-closed: exact row identity, no
  coordinate clicks, no consent clicks, no Bluetooth resets.

## Checks

From a clean tree, with Java 17 and Android SDK 34:

```
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

`testDebugUnitTest` is the single unit-test variant. Instrumentation in
`androidTest` is emulator-only and is not required for a source change unless
you touch that runner.

## License

Project source is MIT. See LICENSE and NOTICE. Do not relicense the Gradle
Wrapper JAR.
