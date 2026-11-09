# react-native-whisper-stt

Real-time, on-device speech-to-text for React Native, powered by [whisper.cpp](https://github.com/ggml-org/whisper.cpp).

- **Live transcription**: text appears while people speak and is finalized at each pause
- **On-device and offline**: audio never leaves the phone
- **Built for long sessions**: keeps transcribing with the app in the background or the screen off (Android)
- **Fast on mid-range phones**: ~100–400 ms per utterance with the 44 MB `tiny.en` model

> **Status:** Android works. iOS is in progress; the package will be published to npm once iOS is supported.
> Calls on iOS currently reject with `E_UNSUPPORTED`.

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

Then rebuild your app (`npx react-native run-android`). It requires the React Native New Architecture (the default since 0.76) and does not work in Expo Go.

The library's Android manifest adds `RECORD_AUDIO`, `INTERNET` (model download), the microphone foreground-service permissions, `WAKE_LOCK` and `POST_NOTIFICATIONS`. Your app must **request `RECORD_AUDIO` at runtime** before recording.

## Usage

```tsx
import { PermissionsAndroid } from 'react-native';
import {
  addTranscriptListener,
  downloadModel,
  init,
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

// 3. Record
await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO);
await startRecording();
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
| `startRecording(options?)` | Starts continuous transcription. |
| `stopRecording()` | Stops, waits for the last utterance and resolves with the full transcript. |
| `isRecording()` | Whether a recording session is active. |
| `addTranscriptListener(cb)` | `{ id, text, isFinal, audioMs, latencyMs }` per partial/final. |
| `addErrorListener(cb)` | `{ code, message }` for errors during recording. |

### Recording options

| Option | Default | Description |
|---|---|---|
| `endSilenceMs` | `700` | Pause that ends an utterance. Lower gives faster finals but more fragmented sentences. |
| `livePartials` | `true` | Emit partial results while someone is speaking. |
| `partialIntervalMs` | `600` | Minimum time between partials. |
| `speechThreshold` / `silenceThreshold` | `0.45` / `0.3` | VAD sensitivity (speech start / end). |
| `preRollMs` | `800` | Audio kept from before speech onset, so soft starts aren't cut. |
| `minUtteranceMs` | `1000` | Shorter utterances are merged with the next one. |
| `maxUtteranceMs` | `20000` | Forced cut for very long monologues. |
| `foregroundService` | `true` | Android: keep transcribing in the background / screen off. |
| `notificationTitle` / `notificationText` | `"Transcribing"` / `"Listening and transcribing on device"` | Text of the foreground-service notification. |

### Error codes

`E_UNSUPPORTED`, `E_NOT_INITIALIZED`, `E_MODEL`, `E_DOWNLOAD`, `E_PERMISSION`, `E_ALREADY_RECORDING`, `E_RECORDING`. Promises reject with these as `error.code`.

## Models

| Model | Size | Notes |
|---|---|---|
| `tiny.en` (default) | 44 MB | Fastest. Recommended for live transcription. |
| `base.en` | 82 MB | Larger and ~1.5–2× slower. |

Both are 8-bit (q8_0) ggml models from [ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp). To use your own ggml file, pass `init({ modelPath })`.

## Background recording (Android)

With `foregroundService: true`, a microphone foreground service shows a "Transcribing" notification and holds a partial wake lock. Transcription then continues with the app in the background or the screen off. Recording must be **started while the app is in the foreground**, which is an Android requirement.

Some manufacturers (for example vivo) confine background apps to the slow CPU cores even with a foreground service. The library checks which cores it may use before each run and adapts its thread count, so background latency stays close to foreground.

## Performance

Measured on a vivo V2231 (MediaTek Dimensity 7200) with `tiny.en`:

| | |
|---|---|
| Whisper run per utterance | ~100–400 ms |
| Final text after the speaker stops | ~0–0.5 s |
| Model load + warm-up | ~0.2 s |

## Contributing

whisper.cpp is a git submodule, so clone with submodules:

```sh
git clone --recursive https://github.com/israr002/rn-whisper-stt.git
# or, in an existing clone:
git submodule update --init
yarn
yarn example android
```

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT. Bundled and downloaded components:

- [whisper.cpp](https://github.com/ggml-org/whisper.cpp): MIT
- [Silero VAD](https://github.com/snakers4/silero-vad) (`silero_vad.onnx`): MIT
- [OpenAI Whisper](https://github.com/openai/whisper) model weights: MIT
- [ONNX Runtime](https://github.com/microsoft/onnxruntime): MIT
