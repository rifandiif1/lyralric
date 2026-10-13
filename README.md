# Lyra Player

Android music player built with Kotlin, Jetpack Compose and Media3.

## Features
- Local music library and Media3 playback controls.
- Live FFT spectrum visualizer with animated fallback.
- LRCLIB automatic lyric search, local lyric cache, LRC import/export and line editor.
- Enhanced-LRC word-by-word timing editor.
- Audio tag editing for title, artist, album, genre, year, track number, composer, lyrics and optional artwork (format and write permission dependent).
- Playlists, favorites, recent tracks, sleep timer, equalizer, bass boost, backup/restore, M3U and lyrics widget.

## Build APK with GitHub Actions
1. Upload the contents of this project to a GitHub repository.
2. Open **Actions** and run **Build Lyra Player APK** (or push to `main`/`master`).
3. Open the completed workflow run and download the `LyraPlayer-debug-apk` artifact.

The APK build has not been verified in this offline editing environment. Run the workflow to check compilation. Audio tag writes depend on file format and Android URI permissions; if needed, use **Pilih file** in the metadata editor to select the original audio document.

See [FEATURE_STATUS.md](FEATURE_STATUS.md) for implemented features and remaining validation limitations.
