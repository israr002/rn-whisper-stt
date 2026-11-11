import { TurboModuleRegistry, type TurboModule } from 'react-native';

export interface Spec extends TurboModule {
  /** Downloads `url` into the app's model directory as `fileName`; resolves with the absolute path. */
  downloadModel(url: string, fileName: string): Promise<string>;
  /** Absolute path of a previously downloaded model, or null. */
  getModelPath(fileName: string): Promise<string | null>;
  deleteModel(fileName: string): Promise<void>;

  // Not init/release: those selectors are reserved by Objective-C memory management
  /** Loads a ggml model from an absolute path and warms it up. */
  loadModel(modelPath: string): Promise<void>;
  isInitialized(): boolean;
  releaseModel(): Promise<void>;

  startRecording(options: {
    speechThreshold?: number;
    silenceThreshold?: number;
    endSilenceMs?: number;
    minUtteranceMs?: number;
    maxUtteranceMs?: number;
    preRollMs?: number;
    livePartials?: boolean;
    partialIntervalMs?: number;
    foregroundService?: boolean;
    notificationTitle?: string;
    notificationText?: string;
  }): Promise<void>;
  /** Resolves with the full transcript once the last utterance is transcribed. */
  stopRecording(): Promise<string>;
  isRecording(): boolean;

  // NativeEventEmitter
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.get<Spec>('WhisperStt');
