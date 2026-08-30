# Scribe

A privacy-first Android voice keyboard (IME) built on Moonshine Voice.
Deliberately an MVP — see the hard rules before adding scope.

## Scope / MVP

- A voice-input IME, always listening while the keyboard is shown: speech
  lands in the focused field as partials (`onText` → composing text) and
  committed lines (`onLine`).
- A minimal Compose setup activity: enable the IME, grant the microphone
  permission, download the speech model. Default Material 3 look, dynamic
  system colors, no custom styling.
- English (en-US), on-device recognition only.

Out of scope: settings screens, other languages, non-voice keyboards,
transcript history, cloud services.

## Architecture / layout

- `app/src/main/kotlin/works/resolve/scribe/`
  - `ScribeInputMethodService.kt` — the IME host: always listening while
    the input view is shown (`syncDictation` reconciles a
    `listeningWanted` intent with the facts), with one serialized worker
    for all blocking Moonshine calls. `DictationState` is the single state
    shared by engine logic and the input view.
  - `MainActivity.kt`, `ui/setup/SetupScreen.kt`, `ui/ime/ImeKeyboard.kt`,
    `ui/theme/` — the Compose setup flow and minimal IME UI.
  - `MoonshineModel.kt` — the single owner of the model spec (arch,
    language): setup downloads here, the IME checks presence, and
    `DictationEngine` loads from the same cache directory.
  - `DictationEngine.kt` — our own AudioRecord capture loop feeding the
    base `Transcriber`; all of `load()`/`stop()`/`close()` and the capture
    thread are blocking and never touch the main thread.
  - `ime/ImePolicies.kt` — pure, unit-tested policy helpers; put new logic
    here, not in the service.
- `app/src/main/res/` — icons and `xml/input_method.xml` (single en-US
  voice subtype).

## Commands

```bash
./gradlew test assembleDebug lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Authoritative references

- Moonshine docs — never trust remembered APIs: https://moonshine-voice.readthedocs.io
  ([adding the library](https://moonshine-voice.readthedocs.io/en/latest/using/adding-the-library/),
  [transcription](https://moonshine-voice.readthedocs.io/en/latest/using/transcription/)).
- Pinned SDK sources: the resolved `ai.moonshine:moonshine-voice` AAR in
  the Gradle cache (version in `gradle/libs.versions.toml`) when the docs
  are unclear.
- Local upstream checkout: `~/Projects/moonshine/` — see
  `.agents/skills/moonshine-voice/SKILL.md` and the sample under
  `examples/android/Transcriber/`. Don't copy its code or branded assets.

## Hard rules

- **API 37 only** (compileSdk = minSdk = targetSdk). No compatibility
  branches, `Build.VERSION` guards, or lowered SDK versions.
- **Toolchain stays bleeding-edge.** Never downgrade AGP, Kotlin, Gradle,
  or libraries to fix something — find the current-API way.
- **Moonshine contract:** construct → configure → `load()` → `start()` on
  our own `DictationEngine` (base `Transcriber` underneath).
  `load()`/`stop()`/`close()` block; keep them on the service's single
  worker executor, never the main thread. All native Moonshine calls are
  never concurrent: `load()`/`close()` run on the worker, every stream op
  on the single capture thread, and `stop()`/`close()` join that thread
  without timeout before any further native call.
- **`onText` is a changing partial (composing); `onLine` is the final line
  (commit).** Never persist or treat partials as final.
- **Never log or persist transcripts or audio.** Recognition is on-device;
  the INTERNET permission exists only for the one-time model download.
- **IME UI stays minimal:** one ComposeView from `onCreateInputView()`,
  standard Material 3 components, no Fragments or persistent UI state.
