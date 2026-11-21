<p align="center"><img src="fastlane/metadata/android/en-US/images/icon.png" width="150"></p>
<h1 align="center"><b>Ausence</b></h1>
<h4 align="center">A local player for audio lectures, with chapters and searchable transcripts.</h4>
<p align="center">
    <a href="https://www.gnu.org/licenses/gpl-3.0">
        <img src="https://img.shields.io/badge/license-GPL%20v3-2B6DBE.svg?style=flat">
    </a>
    <img alt="Minimum SDK Version" src="https://img.shields.io/badge/API-24%2B-1450A8?style=flat">
    <img alt="Based on Auxio" src="https://img.shields.io/badge/based%20on-Auxio-64B5F6?style=flat">
</p>

## About

Ausence is a fork of [Auxio](https://github.com/OxygenCobalt/Auxio) for listening to long talks,
lectures and audiobooks rather than music. Think of how audiobooks and podcasts work in
Spotify: you can see a recording's chapters, read along with what's being said, and search
for a topic or a phrase across your whole library, then jump straight to that moment.

Like Auxio, it's completely local and offline. It plays files stored on your phone and never
uploads anything.

## Features

Everything Auxio does, plus:

- **A calmer, Apple Music-style look**: pure black by default, large artwork that eases back
  when paused, secondary actions tucked into menus, and a library that opens on your teachers
  and series. Five extra colour palettes (Mono, Emerald, Gold, Crimson, Ocean) are in Settings.

- **Chapters**: a list of a recording's sections, with titles and short summaries. Tap one
  to jump to it. The current chapter is highlighted while you listen.
- **Transcripts**: a time-synced transcript that follows playback. Tap a line to jump
  there. Recordings with only untimed text (for example an audiobook's source text) show it
  as readable paragraphs.
- **Spotify-style navigation**: a bottom bar with Home (lectures in progress and finished),
  Search (chapters appear like songs, with their length), Your Library (series, teachers,
  audio, playlists) and Create (new or imported playlist, or a new folder of lectures).
- **Audiobook-style progress**: every lecture remembers where you stopped and picks up there
  (a few seconds back) next time, even after closing the app. Lists show a thin progress bar
  with the time left, album pages show how many lectures you've finished, and the ⋯ menu can
  mark a lecture as finished or unplayed.
- **Chapters on the player**: the seek bar is split at chapter breaks with the current chapter's
  name between the times, and the sheet under the player lists the chapters (instead of the
  queue).
- **Live transcript card**: the line being spoken and the next one, right on the player.
- **Search inside lectures**: the search screen finds matching chapter titles and
  summaries, and words spoken in the transcripts. Tap a result to start playing at that point.

## Preparing your files

Chapters and transcripts are read from tags embedded in the audio files, so there are no
sidecar files to keep track of.

| What | MP4 / M4A | MP3 (ID3v2) | FLAC / Ogg / Opus |
| --- | --- | --- | --- |
| Chapters | `----:com.apple.iTunes:SECTIONS_JSON` | `TXXX:SECTIONS_JSON` | `SECTIONS_JSON` |
| Transcript | `©lyr` | `USLT` | `LYRICS` / `UNSYNCEDLYRICS` |

**Chapters** are a JSON list, with times in seconds:

```json
[
  {"start": 0, "end": 312.5, "title": "Introduction", "summary": "What the talk covers", "type": "intro"},
  {"start": 312.5, "title": "The seven planes", "lecture": "Lecture 2", "approximate": true}
]
```

Only `start` and `title` are required. `lecture` groups chapters under a heading, which is
useful when one file holds several talks or book chapters. `approximate` marks a time
that's only an estimate, and the app shows it with a `~`.

**Transcripts** use LRC lines: `[mm:ss.xx] text`, or `[hh:mm:ss.xx] text`. Minutes can go
past 59 for long recordings. Plain text with no timestamps is shown as untimed paragraphs.

## Building

You need the Android SDK with NDK `28.2.13676358` and CMake, plus Java 21. The project uses
submodules, so clone with `git clone --recurse-submodules`, then apply the fix for the bundled
Media3 copy:

```bash
git -C media apply ../patches/media-fix-hagc-merge.patch
./gradlew assembleDebug
```

The debug APK ends up in `app/build/outputs/apk/debug/` and installs as "Ausence Debug".
Ausence uses its own package name (`app.ausence`), so it installs alongside Auxio.

**Release builds:** `./gradlew assembleRelease` signs the APK if there's a `keystore.properties`
file in the project root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). That file
and the keystore are kept out of git.

**Building on Windows:** upstream Auxio doesn't support Windows, because its FFmpeg decoder
and TagLib steps run Unix shell scripts. To build here:

- the FFmpeg decoder is left out (Android's own decoders handle M4A, MP3, FLAC, Ogg and Opus)
- TagLib is built once by hand with `musikr/src/main/cpp/build_taglib.sh`, run from Git
  Bash with Ninja and the SDK's CMake on `PATH`

## Credits

Ausence is built on [Auxio](https://github.com/OxygenCobalt/Auxio) by
[OxygenCobalt](https://github.com/OxygenCobalt) and its contributors, which handles all the
playback, library and UI. If you like it, consider
[supporting Auxio's developer](https://github.com/sponsors/OxygenCobalt).

## License

[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](http://www.gnu.org/licenses/gpl-3.0.en.html)

Ausence, like Auxio, is Free Software: you can use, study, share and improve it as you like.
Specifically, you can redistribute it and/or modify it under the terms of the
[GNU General Public License](https://www.gnu.org/licenses/gpl.html) as published by the Free
Software Foundation, either version 3 of the License, or (at your option) any later version.
