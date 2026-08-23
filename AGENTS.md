# Amanuensis

A privacy-first Android voice keyboard (IME) built on Moonshine Voice. This
is an MVP on purpose; see the hard rules before adding scope.

## Scope / MVP

- A voice-input IME: tap the mic, speak, text lands in the focused field as
  partial (`onText` → composing) and final (`onLine` → committed) segments.
- A minimal Compose setup activity guiding the user through enabling the IME,
  granting the microphone permission, and downloading the speech model
  (default Material 3 look, dynamic system colors; no custom styling).
- English (en-US) only, on-device recognition only.

Out of scope for the MVP: settings screens, other languages, non-voice
keyboards, transcript history, cloud services.

## Architecture / layout

- `app/src/main/kotlin/works/resolve/amanuensis/`
  - `AmanuensisInputMethodService.kt` — the IME host and engine state machine
    (IDLE/LOADING/STOPPING/READY/LISTENING/FAILED), with a single serialized
    background executor for blocking Moonshine calls and a request-generation
    guard for stop-vs-start races. It supplies the lifecycle owners required
    by the Compose input view.
  - `MainActivity.kt` + `ui/setup/SetupScreen.kt` + `ui/ime/ImeKeyboard.kt` +
    `ui/theme/` — the Compose setup flow and minimal Material 3 IME UI.
  - `MoonshineModel.kt` — shared model-cache helper: setup checks/downloads
    the model here; the IME checks presence without a blocking `load()`. The
    spec must mirror `MicTranscriber`'s defaults so both hit the same cache
    directory.
  - `ime/ImePolicies.kt` — pure, unit-tested policy helpers (separator
    logic, enter-key decision, auto-start decision). Keep new logic pure
    here, not in the service.
- `app/src/main/res/` — icons and `xml/input_method.xml` (single en-US voice
  subtype).

## Commands

```bash
./gradlew test assembleDebug lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Authoritative references

- Moonshine Voice docs (never trust remembered APIs): https://moonshine-voice.readthedocs.io
  - Adding the library: https://moonshine-voice.readthedocs.io/en/latest/using/adding-the-library/
  - Transcription: https://moonshine-voice.readthedocs.io/en/latest/using/transcription/
- Live SDK sources (pinned version is in `gradle/libs.versions.toml`): inspect
  the resolved `ai.moonshine:moonshine-voice` AAR/classes in the Gradle cache
  when the docs are unclear.
- Local upstream checkout and sample apps: `~/Projects/moonshine/` — see
  `.agents/skills/moonshine-voice/SKILL.md` and the Android sample under
  `examples/android/Transcriber/`. Do not copy their code or branded assets
  blindly; this app has its own UI.

## Hard rules

- **API 37 only.** compileSdk = minSdk = targetSdk = 37 deliberately. Do not
  add legacy compatibility branches, `Build.VERSION` guards for older
  releases, or lower any SDK version.
- **Toolchain stays bleeding-edge.** Do not downgrade AGP, Kotlin, Gradle, or
  library versions to fix something; find the current-API way instead.
- **Moonshine contract:** construct → configure → `load()` → `start()`.
  Constructors are cheap; `load()`/`start()`/`close()` block. Keep every
  blocking call off the main thread and serialized on the single worker
  executor in the service so lifecycle operations cannot interleave.
- **`onText` is a changing partial** (set as composing text); **`onLine` is
  the final line** (commit). Never persist or treat partials as final.
- **Never log or persist transcripts or audio.** Recognition is on-device;
  audio must never leave the device. The INTERNET permission exists only for
  the one-time first-use model download.
- **IME UI stays minimal.** A standalone ComposeView returned from
  `onCreateInputView()` uses standard Material 3 components. No Fragments or
  persistent UI state in the IME process.
