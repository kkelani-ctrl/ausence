<p align="center"><img src="art/banner.png" alt="Ausence" width="720"></p>
<h1 align="center">Ausence</h1>
<h3 align="center">The player for people who listen to ideas.</h3>
<p align="center">
Lectures, talks and audiobooks, with chapters you can jump to, transcripts that follow the voice,<br>
and search that finds the exact moment something was said. Offline, on your phone.
</p>
<p align="center">
    <img alt="Offline" src="https://img.shields.io/badge/offline-100%25-111111?style=flat">
    <img alt="Android 7+" src="https://img.shields.io/badge/Android-7%2B-111111?style=flat">
    <a href="https://www.gnu.org/licenses/gpl-3.0"><img alt="GPL v3" src="https://img.shields.io/badge/license-GPL%20v3-111111?style=flat"></a>
    <img alt="Based on Auxio" src="https://img.shields.io/badge/built%20on-Auxio-111111?style=flat">
</p>

---

Music players treat a two-hour lecture like a three-minute song: one long bar and a play button.
Ausence treats it the way you actually listen to one, as a series of ideas you want to find,
return to, and collect.

## What makes it different

### 🔎 Search what was *said*, not just titles
Type "astral body" and get every chapter about it and every sentence where it's spoken, across
your whole library. Tap a result and the lecture starts at that exact second.

### 📑 Chapters, everywhere
Every recording is split into titled, summarised chapters.
- The seek bar shows the chapter breaks, with the current chapter's name between the times.
  Drag it and the name follows your finger.
- Swipe up under the player for the full chapter list; tap one to jump there.
- In search, chapters show up like songs: cover, title, lecture and length.

### 🎚️ Playlists made of chapters
Build a playlist of just the parts that matter: one chapter from this talk, two from that book,
a whole episode from another. No audio is cut or copied. Each chapter plays from its start to
its end, then the playlist moves on. Want more context? **Play full episode**, and the playlist
carries on afterwards.

### 📜 Transcripts that follow the voice
A live card on the player shows the sentence being spoken and the next one. Open it and the
transcript fills the screen: large text, the current line lit, the rest faded, scrolling as the
speaker talks. Tap any line to jump there.

### ⏱️ Remembers where you were, in every lecture
Like an audiobook app, each lecture keeps its own place, even after you close the app. Pick it
up again and it resumes a few seconds back, so you don't lose the thread.
- **Home** shows what you're in the middle of, most recent first, and what you've finished.
- Every list shows a thin progress bar and the time left.
- Series pages show how far through you are ("12 of 36 finished").

### 🖤 Quiet by design
Pure black with a white Mono theme by default, or one of 20+ colour palettes. Big artwork that
eases back when you pause. A bottom bar with Home, Search, Your Library and Create. Nothing
else competing for your attention.

### 🔒 Yours, and only yours
Everything stays on your phone: your files, your progress, your playlists. No account, no
server, no tracking.

---

## Who it's for

People who learn by ear: philosophy and history lectures, spiritual and esoteric teachings,
long-form podcasts, recorded classes, audiobooks. If you've ever scrubbed back and forth through
a long recording looking for "that part where he explains…", this is for you.

## Your files

Ausence reads chapters and transcripts from tags inside the audio files themselves, so a
library is just a folder of audio. Nothing extra needs to sit beside the files.

| What | MP4 / M4A | MP3 (ID3v2) | FLAC / Ogg / Opus |
| --- | --- | --- | --- |
| Chapters | `----:com.apple.iTunes:SECTIONS_JSON` | `TXXX:SECTIONS_JSON` | `SECTIONS_JSON` |
| Transcript | `©lyr` | `USLT` | `LYRICS` / `UNSYNCEDLYRICS` |

<details>
<summary><b>Tag formats</b></summary>

**Chapters** are a JSON list, with times in seconds:

```json
[
  {"start": 0, "end": 312.5, "title": "Introduction", "summary": "What the talk covers"},
  {"start": 312.5, "title": "The seven planes", "lecture": "Lecture 2", "approximate": true}
]
```

Only `start` and `title` are required. `lecture` groups chapters under a heading, useful when
one file holds several talks or book chapters. `approximate` marks an estimated time, shown
with a `~`.

**Transcripts** use LRC lines: `[mm:ss.xx] text` or `[hh:mm:ss.xx] text`. Minutes can go past
59 for long recordings. Plain text without timestamps is shown as readable paragraphs.

</details>

## Building

<details>
<summary><b>Build it yourself</b></summary>

You need the Android SDK with NDK `28.2.13676358` and CMake, plus Java 21. Clone with
submodules, apply the fix for the bundled Media3 copy, and build:

```bash
git clone --recurse-submodules https://github.com/kkelani-ctrl/ausence
cd ausence
git -C media apply ../patches/media-fix-hagc-merge.patch
./gradlew assembleDebug
```

The debug APK is in `app/build/outputs/apk/debug/` and installs as "Ausence Debug". Ausence uses
its own package name (`app.ausence`), so it sits alongside Auxio if you have both.

**Release builds:** `./gradlew assembleRelease` signs the APK when a `keystore.properties` file
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) is in the project root. That file and
the keystore are kept out of git.

**On Windows:** the FFmpeg decoder is left out (Android's own decoders handle M4A, MP3, FLAC,
Ogg and Opus), and TagLib is built once with `musikr/src/main/cpp/build_taglib.sh` from Git
Bash, with Ninja and the SDK's CMake on `PATH`.

**Logo:** the mark is drawn in code; see `art/logo.py`, with the SVGs alongside it.

</details>

## Credits

Ausence is built on [Auxio](https://github.com/OxygenCobalt/Auxio) by
[OxygenCobalt](https://github.com/OxygenCobalt) and its contributors, which does the heavy
lifting for playback and the music library. If you like Ausence, consider
[supporting Auxio's developer](https://github.com/sponsors/OxygenCobalt).

## License

Ausence, like Auxio, is Free Software under the
[GNU General Public License v3](https://www.gnu.org/licenses/gpl-3.0.html) or later: you can use,
study, share and improve it as you like.
