# Scribe

Scribe is a small, privacy-first voice input keyboard for Android. I wrote it
mostly for personal use because I wanted a good voice keyboard for the latest
GrapheneOS.

When Scribe is selected, it listens while the keyboard is visible and inserts
speech into the focused text field. Partial results appear as composing text;
finished lines are committed automatically.

Speech recognition is provided by
[Moonshine Voice](https://moonshine-voice.readthedocs.io) and runs entirely on
the device.

## Current scope

- English (en-US) dictation
- Live partial transcription
- Delete, enter, and return-to-previous-keyboard controls
- Android 16 (API 36) or newer
- No settings, history, cloud services, or conventional keyboard

This is an intentionally minimal, personal-use project rather than a polished
general-purpose keyboard.

## Setup

1. Install and open Scribe.
2. Enable it in Android's keyboard settings.
3. Grant microphone permission.
4. Download the speech model from the setup screen.
5. Select Scribe as the current keyboard and start speaking.

An internet connection is needed only to download the model. Afterward,
recognition works offline.

## Privacy

Audio and transcripts are never logged, stored, or uploaded. The app's internet
permission is used only for the model download.

## Build

Requires JDK 17 and the Android API 37 SDK.

```bash
./gradlew test assembleDebug lintDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## License

Scribe is licensed under the [GNU GPLv3](LICENSE). See
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for dependency attribution.
