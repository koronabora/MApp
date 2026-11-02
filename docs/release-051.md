# MApp 0.5.1 (22)

- Card snapshots are cached by server/account/movie in private cache storage, capped
  at 24 cards and 64 MiB. Reopening displays the cached card before network refresh.
- Cached availability is always unknown (light gray). Fresh server status updates
  the source dot without replacing the episode/source controls or resetting selection.
- Full catalog changes preserve the selected season, episode, source and scroll offset.
- Source polling uses a structural revision and receives a small availability response
  when unchanged; polling slows down after initial refresh. Search returns local index
  results immediately and refreshes while the server checks trackers in the background.
- Existing title/season folders, downloaded files, signing identity and updater retained.

Requires MCore 0.5.1 for independent availability and compact source responses.

Release build, signing verification and lint passed. Emulator `index051` passed:
disk card cache, unknown light-gray dot, fresh green dot, source/episode selections
and selector instance preserved. Test emulator exited; no Gradle daemons remain.
Physical phone was not connected during this release's verification.

Published as 0.5.1/code 22 via updater and public HTTPS root. Downloaded live APK
matches 122823624 bytes and SHA-256
`d14dd303fd10b608b083e686c6056ac0fcc9f34d88c18d0bed4602fe746627c7`.
