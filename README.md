# Stem Player

A simple Android app for practicing choral parts. Import the separate voice recordings of a piece
(e.g. the Soprano, Alto, Tenor and Bass exports from MuseScore) and play them together, with a
volume slider, mute and solo for each voice.

## Install

Download the latest `StemPlayer.apk` from the
[Releases page](../../releases/latest) on your phone and open it.
Android will ask you to allow your browser to "install unknown apps" — allow it once.

## Using it

1. Tap **New song** and select all the voice files of one piece at once — optionally together with
   its score as MusicXML (`.mxl` or `.musicxml`, e.g. exported from MuseScore).
2. Check the song and voice names (they are guessed from the file names) and tap **Import**.
3. Open the song, set the volume of each voice, mute or solo voices, and press play.

The player shows either the **Score** or the **Mixer**; switch with the buttons next to the play
controls. While playing, the current measure is highlighted and the score scrolls along. Tap a
measure to jump there. A score can also be added, replaced or removed later from the ⋮ menu.

Measure timing comes from the score itself (tempo markings, repeats, voltas, D.C./D.S., Fine, Coda).
If the audio is as long as the score (audio exported from the same score), score time is used
directly; otherwise the score is stretched to the audio's length.

All voices are mixed into a single audio output, so they always stay perfectly in sync.
Volume and mute settings are remembered per song. All stems of a song must share the same sample rate.

## Building

Requires JDK 17 and the Android SDK.

```
./gradlew assembleRelease
```

Release builds are signed with the key described in `../StemPlayer-signing/keystore.properties`
(outside this repository); without it, the debug key is used.

## Third-party

Scores are rendered with [Verovio](https://www.verovio.org) (LGPL-3.0), bundled unmodified in
`app/src/main/assets/score/verovio-toolkit-wasm.js` (npm `verovio@6.4.1`).
