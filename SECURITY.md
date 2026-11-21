# Security

Audio Router is an accessibility-service experiment. Enabling it lets the app
read and click the system media-output chooser. That is powerful. Enable it
only if you accept that scope.

## Reporting

Use GitHub private vulnerability reporting if it is enabled on this
repository, or open an issue that describes the behavior without attaching:

- device serials, MAC addresses, or account identifiers
- network addresses
- screenshots that show real output names, media titles, or personal data
- logs that include those values

A short description and a synthetic reproduction are enough.

## What this app does not do

The app does not request Internet, microphone, or privileged audio-policy
permissions. It does not reset Bluetooth, place calls, or route microphone
audio. It is not a privileged `MODIFY_AUDIO_ROUTING` client and it does not
use root or Shizuku.

Do not send credentials, keystores, or `local.properties` to this repository.
