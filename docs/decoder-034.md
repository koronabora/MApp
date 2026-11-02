# Phone decoding investigation (2026-11-02)

Device: Xiaomi 23021RAA2Y / topaz, Android 35. Installed release at the start: 0.3.3.

The completed `Novij.Chelovek.pauk.2012.XviD.RUS.Dub.AC3.BDRip.avi`
(1,560,000,512 bytes) has the same full SHA-256 on the SD card and server:

`d130098416afe58dd9f4f4ac8ae3f5760bff397bdaa053dda0f7ebb8cbca9209`

The first 128 MiB also matched 64 torrent-piece SHA-1 hashes. The first 32 MiB
received through the live API matched the server bytes and HTTP SHA-256. ffmpeg
decoded the first 90 seconds without errors on the server.

Two external VLC launches used the same saved file and requested position 55 s.
The default launch reproduced large block artifacts (capture at 8 s after launch).
With the `disable_hardware` extra present, captures at 4, 8 and 15 s showed clean
frames. This identifies a decoding-path problem for this file on this phone;
it does not establish the cause of every previously reported playback failure.

The default-path log identifies `c2.android.mpeg4.decoder`, Android's **software**
MPEG-4 decoder accessed through MediaCodec. Calling this a hardware-decoder defect
would be inaccurate. VLC's `disable_hardware` switch bypasses MediaCodec and uses
VLC's own software decoding path. Default-path logs also show discarded trailing
bytes and dropped frames; those messages alone do not prove file corruption.

MApp 0.3.4 selects VLC software decoding for filenames marked
Xvid/DivX/MPEG-4. Other files retain the default path. A user-selectable override
supports software decoding for files whose codec is not identified in the name.
This filename fallback is not a substitute for codec metadata in the future catalog.

Evidence: workspace `artifacts/phone-default-8s.png`,
`artifacts/phone-software-8s.png`, `artifacts/phone-software-15s.png`, and
`artifacts/phone-decoder-default.log`. Reproduction: `tools/phone-decoder-034.py`.
The phone-only instrumentation mode `phoneDecoder` reads an existing completed
library item and launches the embedded player without movie/history identifiers.
