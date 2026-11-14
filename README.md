# react-native-whisper-stt

Real-time, on-device speech-to-text for React Native, powered by [whisper.cpp](https://github.com/ggml-org/whisper.cpp).

- **Live transcription**: text appears while people speak and is finalized at each pause
- **On-device and offline**: audio never leaves the phone
- **Built for long sessions**: keeps transcribing with the app in the background or the screen locked, on Android and iOS
- **Fast**: ~70–180 ms per utterance on an iPhone 13 Pro (Metal GPU), ~100–400 ms on a mid-range Android phone
- **iOS and Android**, React Native New Architecture (TurboModule)

## Demo

https://github.com/user-attachments/assets/bbb57d6b-c9cf-4b9d-b64a-01a47f43ae30

## How it works

```
mic (16 kHz) → Silero VAD → utterances cut at pauses → whisper.cpp → partial / final text events
```

- **Voice activity detection** ([Silero VAD](https://github.com/snakers4/silero-vad)) splits speech at natural pauses, so Whisper always sees whole phrases. Accuracy is much better than with fixed 1-second chunks.
- **whisper.cpp with `audio_ctx`** runs the encoder only on the audio that exists instead of a padded 30 s window. That is the main source of the speed.
- **Partials** re-transcribe the in-progress utterance whenever the worker is idle. When the speaker pauses, the latest partial usually already covers everything, so the final arrives almost immediately.

## Installation

```sh
npm install react-native-whisper-stt
# or
yarn add react-native-whisper-stt
```

It requires the React Native New Architecture (the default since 0.76) and does not work in Expo Go.

### iOS

```sh
cd ios && pod install
```

Add to your app's `Info.plist`:

```xml
<!-- Required: shown in the microphone permission prompt -->
<key>NSMicrophoneUsageDescription</key>
<string>Used to transcribe speech on your device.</string>
<!-- Optional: keep transcribing in the background / with the screen locked -->
<key>UIBackgroundModes</key>
<array>
  <string>audio</string>
</array>
```

whisper.cpp ships prebuilt (with Metal) inside the package, so no extra build tools are needed. Minimum iOS 15.1.

### Android

Rebuild your app (`npx react-native run-android`). The library's manifest adds `RECORD_AUDIO`, `INTERNET` (model download), the microphone foreground-service permissions, `WAKE_LOCK` and `POST_NOTIFICATIONS`. On Android 13+, also request `POST_NOTIFICATIONS` so the "Transcribing" notification shows (recording works without it).

## Usage

```tsx
import {
  addTranscriptListener,
  downloadModel,
  init,
  requestMicrophonePermission,
  startRecording,
  stopRecording,
} from 'react-native-whisper-stt';

// 1. Download the model once (~44 MB, cached in app storage) and load it
await downloadModel('tiny.en', (e) => console.log(`${Math.round(e.progress * 100)}%`));
await init({ model: 'tiny.en' });

// 2. Listen for results
const sub = addTranscriptListener(({ id, text, isFinal }) => {
  // A partial (isFinal: false) is replaced by the final with the same id
  console.log(id, isFinal ? 'final' : 'partial', text);
});

// 3. Record (asks for microphone access the first time, on iOS and Android)
if (await requestMicrophonePermission()) {
  await startRecording();
}
// ...
const fullTranscript = await stopRecording();
sub.remove();
```

See [`example/src/App.tsx`](example/src/App.tsx) for a complete app.

## API

| Function | Description |
|---|---|
| `downloadModel(model?, onProgress?)` | Downloads a model once and resolves with its path. Does nothing if it's already downloaded. |
| `getModelPath(model?)` | Path of a downloaded model, or `null`. |
| `deleteModel(model)` | Deletes a downloaded model. |
| `init({ model?, modelPath? })` | Loads a registry model (default `tiny.en`) or your own ggml file. |
| `isInitialized()` | Whether a model is loaded. |
| `release()` | Frees the model. |
| `requestMicrophonePermission()` | Asks for microphone access if not decided yet; resolves with whether it's granted. |
| `startRecording(options?)` | Starts continuous transcription. Needs microphone permission. |
| `stopRecording()` | Stops, waits for the last utterance and resolves with the full transcript. |
| `isRecording()` | Whether a recording session is active. |
| `addTranscriptListener(cb)` | `{ id, text, isFinal, audioMs, latencyMs }` per partial/final. |
| `addErrorListener(cb)` | `{ code, message }` for errors during recording. |

### Recording options

| Option | Default | Description |
|---|---|---|
| `prompt` | none | Vocabulary hint: names and terms you expect, e.g. `"Israr Khan, Zerodha, Kubernetes"`. See below. |
| `endSilenceMs` | `700` | Pause that ends an utterance. Lower gives faster finals but more fragmented sentences. |
| `livePartials` | `true` | Emit partial results while someone is speaking. |
| `partialIntervalMs` | `600` | Minimum time between partials. |
| `speechThreshold` / `silenceThreshold` | `0.45` / `0.3` | VAD sensitivity (speech start / end). |
| `preRollMs` | `800` | Audio kept from before speech onset, so soft starts aren't cut. |
| `minUtteranceMs` | `1000` | Shorter utterances are merged with the next one. |
| `maxUtteranceMs` | `20000` | Forced cut for very long monologues. |
| `foregroundService` | `true` | Android: keep transcribing in the background / screen off. (iOS: see [Background recording](#background-recording).) |
| `notificationTitle` / `notificationText` | `"Transcribing"` / `"Listening and transcribing on device"` | Android: text of the foreground-service notification. |

### Vocabulary hint (`prompt`)

Whisper often misspells rare names, e.g. "Israr" as "Israel". Pass the words you expect and it will prefer them:

```ts
await startRecording({ prompt: 'Israr Khan, Zerodha, Kubernetes' });
```

Whisper treats the prompt as text that came just before the audio, so it nudges spelling without forcing it. Keep it short (a few names or terms, comma-separated): long prompts slow each run down and can make Whisper repeat prompt words.

### Error codes

`E_UNSUPPORTED`, `E_NOT_INITIALIZED`, `E_MODEL`, `E_DOWNLOAD`, `E_PERMISSION`, `E_ALREADY_RECORDING`, `E_RECORDING`. Promises reject with these as `error.code`.

## Models

| Model | Size | Notes |
|---|---|---|
| `tiny.en` (default) | 44 MB | Fastest. Good for most live transcription on Android. |
| `base.en` | 82 MB | Noticeably better with names and accents, and fewer repetition glitches. On iPhones (Metal) it runs about as fast as `tiny.en`; on Android CPUs ~1.5–2× slower. |

Both are 8-bit (q8_0) ggml models from [ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp). Models are downloaded at runtime when you call `downloadModel()` (nothing is downloaded automatically) and kept in app storage, excluded from iCloud backup. To use your own ggml file (another size, a multilingual model, or one bundled with your app), pass `init({ modelPath })`.

## Background recording

### iOS

With `audio` in `UIBackgroundModes` (see [Installation](#ios)), recording continues with the app in the background or the screen locked. As on Android, start recording while the app is in the foreground.

iOS doesn't allow GPU work in the background. The library switches to a CPU copy of the model when the app leaves the foreground (loaded on first need, ~0.1 s) and back to Metal when it returns. Live partials pause in the background, since nobody can see them; final text keeps arriving.

### Android

With `foregroundService: true`, a microphone foreground service shows a "Transcribing" notification and holds a partial wake lock. Transcription then continues with the app in the background or the screen off. Recording must be **started while the app is in the foreground**, which is an Android requirement.

Some manufacturers (for example vivo) confine background apps to the slow CPU cores even with a foreground service. The library checks which cores it may use before each run and adapts its thread count, so background latency stays close to foreground.

## Performance

| | iPhone 13 Pro, `base.en` | vivo V2231 (Dimensity 7200), `tiny.en` |
|---|---|---|
| Whisper run per utterance | ~70–180 ms (Metal) | ~100–400 ms (CPU) |
| Same, app in background | ~80–290 ms (CPU, measured with `tiny.en`) | ~100–400 ms |
| Final text after the speaker stops | ~0–0.2 s | ~0–0.5 s |
| Model load + warm-up | ~0.4 s | ~0.2 s |

On iOS, the first launch after installing compiles the Metal shaders (~10 s on an iPhone 13 Pro, cached by iOS afterwards). The library starts this as soon as the module loads, so it normally finishes while the model downloads. If the model is already on the device, the first `init()` waits for whatever is left of the compile.

## Contributing

whisper.cpp is a git submodule, so clone with submodules:

```sh
git clone --recursive https://github.com/israr002/rn-whisper-stt.git
# or, in an existing clone:
git submodule update --init
yarn
yarn example android
# iOS: builds whisper.cpp into ios/Frameworks/whisper.xcframework first (needs cmake: brew install cmake)
yarn build:ios-whisper
cd example/ios && bundle install && bundle exec pod install && cd ../..
yarn example ios
```

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT. Bundled and downloaded components:

- [whisper.cpp](https://github.com/ggml-org/whisper.cpp): MIT
- [Silero VAD](https://github.com/snakers4/silero-vad) (`silero_vad.onnx` on Android, `ggml-silero-v5.1.2.bin` on iOS): MIT
- [OpenAI Whisper](https://github.com/openai/whisper) model weights: MIT
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) (Android): MIT
