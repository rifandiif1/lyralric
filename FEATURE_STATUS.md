# Lyra Player — Feature Status

## Implemented in source (must still be tested on a real Android device)
- Local music library scan and optional user-selected folder.
- Media3 playback, queue controls, shuffle/repeat, favorites, playlists, recent tracks and play counts.
- Dark mode, equalizer/bass boost, sleep timer, backup import/export, M3U import/export.
- FFT visualizer with animated fallback when audio-session capture is unavailable; permission prompt for analysis.
- Automatic LRCLIB lyric search with local cache and manual LRC import/export.
- Edit individual lyric lines while preserving the line timestamp.
- Enhanced-LRC word timing editor: capture playback position per word and export as Enhanced LRC.
- Shift lyric timestamps and add a timestamp at current playback position.
- Embed lyrics and write title, artist, album, genre, year, track number, composer and optional artwork using jaudiotagger for supported formats, subject to Android document-provider write permissions.
- Lyrics home-screen widget.
- GitHub Actions workflow for debug APK build and artifact upload.

## Limitations / validation still required
- The Android build has not been run in this environment because Gradle/Android SDK downloads are unavailable here. Run the GitHub Actions workflow to verify compilation and download the APK artifact.
- Tag write support depends on audio format and whether Android grants write access to the selected URI. If a MediaStore URI is read-only, choose the original audio file via **Pilih file** and grant access. A failed write is reported in the app status.
- Embedded artwork support depends on the audio container/jaudiotagger format implementation.
- Enhanced LRC word-level timestamps are not supported by every third-party lyric renderer; line-level playback remains supported by Lyra.
- The editor captures timestamps manually while the song plays; it cannot infer exact phoneme/word boundaries automatically.
- Metadata edits are written to the file when possible; Android's MediaStore index may require a rescan or app restart to reflect changed tags in other apps.
- No release signing, Play Store packaging, or physical-device compatibility test is included.
