# Scribe

Scribe is a voice keyboard for Android. Instead of typing, you tap the
microphone, speak, and your words appear as text in any app — with all speech
recognition running entirely on your device.

It is powered by [Moonshine Voice](https://moonshine-voice.readthedocs.io),
an on-device speech recognition toolkit.

## Features

- Voice dictation into any text field, via Android's standard input-method
  (keyboard) mechanism. You decide where to dictate — no field type is
  blocked.
- Live partial transcription while you speak; each finished speech segment is
  committed as final text.
- Basic editing keys: delete, enter, and switch back to your previous
  keyboard.
- On-device recognition: your speech never leaves the device, and nothing is
  recorded, stored, or uploaded.

## Requirements

- Android 17 (API 37) or newer. Older versions are not supported.

## Setup

1. Install the app and open it.
2. Follow the setup screen: enable **Scribe** in system input-method
   settings, grant microphone access, and select it as your keyboard.
3. In any app's text field, switch to Scribe and tap the microphone.

The first dictation downloads the speech recognition model, so it needs an
internet connection that one time. After that, recognition works fully
offline.

## Privacy

All speech recognition runs locally on the device. Audio and transcripts are
never logged, stored, or sent anywhere. The app's internet permission is used
only for the initial model download.

## Building

Requirements: a recent Android SDK (API 37) and JDK 17+.

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
Run the unit tests and linter with `./gradlew test lintDebug`.

## Current limitations

This is an intentional MVP:

- English (en-US) recognition only.
- The recognition model is downloaded on first use rather than bundled.
- Controls are deliberately minimal — no settings, languages, or themes.

## Attribution

Speech recognition is provided by
[Moonshine Voice](https://moonshine-voice.readthedocs.io) by
[Moonshine AI](https://github.com/moonshine-ai).
