# Stem Player

A simple Android app for practicing choral parts. Import the separate voice recordings of a piece
(e.g. the Soprano, Alto, Tenor and Bass exports from MuseScore) and play them together, with a
volume slider, mute and solo for each voice.

## Install

Download the latest `StemPlayer.apk` from the
[Releases page](../../releases/latest) on your phone and open it.
Android will ask you to allow your browser to "install unknown apps" — allow it once.

## Using it

1. Tap **New song** and select all the voice files of one piece at once.
2. Check the song and voice names (they are guessed from the file names) and tap **Import**.
3. Open the song, set the volume of each voice, mute or solo voices, and press play.

All voices are mixed into a single audio output, so they always stay perfectly in sync.
Volume and mute settings are remembered per song. All stems of a song must share the same sample rate.

## Building

Requires JDK 17 and the Android SDK.

```
./gradlew assembleRelease
```

Release builds are signed with the key described in `../StemPlayer-signing/keystore.properties`
(outside this repository); without it, the debug key is used.
