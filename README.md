# Lyra Player — Advanced local music player

This project is an Android local-music-player foundation with an integrated LRC workspace.

## Implemented in this revision
- Local audio library via Android MediaStore.
- Optional user-selected folder scan using Android's Storage Access Framework.
- Search across title, artist, album, and folder.
- Home overview, library, search, full Now Playing screen, playlists, queue, lyrics, and settings sections.
- Now Playing artwork panel reads embedded artwork from the active audio file when available.
- Inline synchronized lyrics preview with the current line highlighted.
- Animated decorative visualizer bars that animate during playback (not a live audio-spectrum analyzer yet).
- Playback via Media3 session service, background playback, media notification/session integration.
- Play/pause, previous/next, seek bar, shuffle, repeat off/all/one.
- Favorites, recently played history, play counts, locally stored playlists.
- Queue inspection and queue item removal.
- LRCLIB search, LRC import/export, timestamp insertion, and bulk timestamp offset.
- Lyrics home-screen widget updates.
- Dark/light theme toggle and sleep timer.
- Audio focus and headset-unplug handling through Media3 audio attributes.

## Important limitations
This is an expanded development build, not a verified release. It has not been compiled on a real Android SDK in this environment. Run the GitHub Actions workflow and address any compiler errors before installing it.

Some features from the requested aspirational list still require dedicated implementation and device testing, rather than just UI toggles:
- Equalizer / bass boost / virtualizer and per-track loudness normalization.
- Crossfade and gapless behavior validation.
- Smart recommendations and advanced rule-based shuffle.
- Full metadata editing and embedded artwork writing.
- Duplicate / broken-file analysis.
- M3U/M3U8 import/export, backup/restore, and cloud backup.
- Android Auto integration.
- Live audio-reactive visualizer, animated album-art themes, and full accessibility QA.
- Full editor timeline, undo/redo, and multi-line timestamp editing.

## Build
Use the GitHub Actions workflow in `.github/workflows/build-apk.yml`. On a successful run, download the APK artifact from the workflow run page.
