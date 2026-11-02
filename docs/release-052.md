# MApp 0.5.2 (23)

- Film collections use an episode selector without seasons and a Download all button.
- Explicit episode numbers (including Roman numerals in torrent filenames) are used
  for ordering and grouping the same episode across releases. Original torrent/file
  identities remain unchanged. Unidentified entries never expose technical paths.
- Download all builds a complete batch from the selected release/torrent, with no
  duplicate episodes or mixing sources. Film collections keep seasonless folders.
- Automatic no-update checks return without scheduling a UI message. Manual checks
  retain the latest-version dialog.

Verification: release build, Android test build, lint and signing checks passed.
On Xiaomi a82b9b92, card052 checked the actual Star Wars anthology from the reported
screenshot: 12 manifest entries become six episodes, numbered 1–6; no season selector;
episode selection resolves to the correct source video; Download all is enabled and
its batch contains six distinct videos from the chosen source. No video payload was
downloaded by this check. A successful forced automatic update check showed no
latest-version dialog. App installed with -r, preserving existing application data.
Gradle daemons stopped; no test emulators started.

APK SHA-256: f32703f8c5c48619bc6d7f007b559080c1f7a51993da3b9033c59b6fcf58fb11
