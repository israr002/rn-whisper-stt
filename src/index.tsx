import {
  NativeEventEmitter,
  Platform,
  type EmitterSubscription,
} from 'react-native';
import NativeWhisperStt, { type Spec } from './NativeWhisperStt';

/**
 * whisper.cpp ggml models (MIT, https://huggingface.co/ggerganov/whisper.cpp).
 * English-only models are faster and more accurate for English.
 */
export const MODELS = {
  'tiny.en': {
    fileName: 'ggml-tiny.en-q8_0.bin',
    url: 'https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q8_0.bin',
    sizeBytes: 43_550_795,
  },
  'base.en': {
    fileName: 'ggml-base.en-q8_0.bin',
    url: 'https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q8_0.bin',
    sizeBytes: 81_781_811,
  },
} as const;

export type ModelName = keyof typeof MODELS;

export type RecordingOptions = {
  /** Silero VAD probability that starts speech. Default 0.45 */
  speechThreshold?: number;
  /** VAD probability below which audio counts as silence. Default 0.3 */
  silenceThreshold?: number;
  /** Pause that ends an utterance. Default 700 */
  endSilenceMs?: number;
  /** Shorter utterances wait to merge with the next one. Default 1000 */
  minUtteranceMs?: number;
  /** Force a cut after this long (Whisper's window is 30s). Default 20000 */
  maxUtteranceMs?: number;
  /** Audio kept from before speech onset. Default 800 */
  preRollMs?: number;
  /** Emit partial results while someone is speaking. Default true */
  livePartials?: boolean;
  /** Minimum time between partial results. Default 600 */
  partialIntervalMs?: number;
  /**
   * Android: run a microphone foreground service so transcription continues with the app
   * in the background or the screen off. Default true
   */
  foregroundService?: boolean;
  /** Android foreground-service notification text. */
  notificationTitle?: string;
  notificationText?: string;
};

export type TranscriptEvent = {
  /** Utterance id within the current recording; a partial is replaced by the final with the same id */
  id: number;
  text: string;
  isFinal: boolean;
  /** Length of the audio that was transcribed */
  audioMs: number;
  /** Time from the end of the utterance (or partial snapshot) to the result */
  latencyMs: number;
};

export type DownloadProgressEvent = {
  fileName: string;
  bytesWritten: number;
  totalBytes: number;
  /** 0..1, or -1 when the server didn't send a size */
  progress: number;
};

export type WhisperSttErrorCode =
  | 'E_UNSUPPORTED'
  | 'E_NOT_INITIALIZED'
  | 'E_MODEL'
  | 'E_DOWNLOAD'
  | 'E_PERMISSION'
  | 'E_ALREADY_RECORDING'
  | 'E_RECORDING';

export type ErrorEvent = { code: WhisperSttErrorCode; message: string };

const EVENTS = {
  transcript: 'WhisperSttTranscript',
  error: 'WhisperSttError',
  downloadProgress: 'WhisperSttDownloadProgress',
} as const;

function native(): Spec {
  if (!NativeWhisperStt) {
    const error = new Error(
      Platform.OS === 'ios'
        ? 'react-native-whisper-stt: iOS is not supported yet.'
        : "react-native-whisper-stt: native module not found. Rebuild the app after installing (it doesn't work in Expo Go)."
    ) as Error & { code: WhisperSttErrorCode };
    error.code = 'E_UNSUPPORTED';
    throw error;
  }
  return NativeWhisperStt;
}

let emitter: NativeEventEmitter | undefined;
function events(): NativeEventEmitter {
  emitter ??= new NativeEventEmitter(native());
  return emitter;
}

/** Path of a downloaded model, or null if it hasn't been downloaded yet. */
export function getModelPath(
  model: ModelName = 'tiny.en'
): Promise<string | null> {
  return native().getModelPath(MODELS[model].fileName);
}

/**
 * Downloads a model once and caches it in app storage (no-op if already present).
 * Resolves with the model's absolute path.
 */
export async function downloadModel(
  model: ModelName = 'tiny.en',
  onProgress?: (event: DownloadProgressEvent) => void
): Promise<string> {
  const { fileName, url } = MODELS[model];
  const existing = await native().getModelPath(fileName);
  if (existing) return existing;

  const sub = onProgress
    ? events().addListener(
        EVENTS.downloadProgress,
        (e: DownloadProgressEvent) => {
          if (e.fileName === fileName) onProgress(e);
        }
      )
    : undefined;
  try {
    return await native().downloadModel(url, fileName);
  } finally {
    sub?.remove();
  }
}

export function deleteModel(model: ModelName): Promise<void> {
  return native().deleteModel(MODELS[model].fileName);
}

/**
 * Loads a model. Pass `model` to use a downloaded registry model, or `modelPath` for your own
 * ggml file (e.g. bundled with the app and copied to storage).
 */
export async function init(
  options: { model?: ModelName; modelPath?: string } = {}
): Promise<void> {
  let path = options.modelPath;
  if (!path) {
    const model = options.model ?? 'tiny.en';
    path = (await native().getModelPath(MODELS[model].fileName)) ?? undefined;
    if (!path) {
      const error = new Error(
        `Model '${model}' is not downloaded. Call downloadModel('${model}') first.`
      ) as Error & { code: WhisperSttErrorCode };
      error.code = 'E_MODEL';
      throw error;
    }
  }
  return native().loadModel(path);
}

export function isInitialized(): boolean {
  return NativeWhisperStt?.isInitialized() ?? false;
}

/** Frees the model. Call init() again before recording. */
export function release(): Promise<void> {
  return native().releaseModel();
}

/**
 * Starts continuous transcription from the microphone. Results arrive through
 * addTranscriptListener. Requires RECORD_AUDIO to be granted (the library doesn't prompt).
 */
export function startRecording(options: RecordingOptions = {}): Promise<void> {
  return native().startRecording(options);
}

/** Stops recording; resolves with the full transcript once the last utterance is done. */
export function stopRecording(): Promise<string> {
  return native().stopRecording();
}

export function isRecording(): boolean {
  return NativeWhisperStt?.isRecording() ?? false;
}

export function addTranscriptListener(
  listener: (event: TranscriptEvent) => void
): EmitterSubscription {
  return events().addListener(EVENTS.transcript, listener);
}

export function addErrorListener(
  listener: (event: ErrorEvent) => void
): EmitterSubscription {
  return events().addListener(EVENTS.error, listener);
}
