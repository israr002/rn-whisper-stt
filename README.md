# WhisperSTT - On-Device Speech Recognition

WhisperSTT is a React Native application that provides on-device speech-to-text transcription using the Whisper model from OpenAI, optimized for mobile devices through TensorFlow Lite.

## Demo Video
<video src="https://github.com/israr002/rn-whisper-stt/blob/main/src/assets/whisperstt.mp4?raw=true" controls="controls" style="max-width: 730px;">
</video>

## Features

- **On-Device Inference**: All speech processing happens locally on your device
- **Compact Model**: Uses a 40MB TFLite model (whisper-tiny-en) for efficient on-device inference
- **Privacy-First**: No audio data leaves your device


## Technical Details

- Utilizes TensorFlow Lite for efficient on-device model inference
- Implements native modules for audio recording and processing
- Uses the Whisper model architecture for accurate speech recognition
- Real-time transcription capabilities

## Getting Started

### Prerequisites

- Node.js and npm or yarn
- Android Studio (for Android development)
- Xcode (for iOS development)
- CocoaPods (for iOS dependencies)

### Installation

1. Clone the repository:
```sh
git clone https://github.com/israr002/rn-whisper-stt.git
cd whisperstt
```

2. Install JavaScript dependencies:
```sh
npm install
# OR
yarn install
```

3. For iOS, install CocoaPods dependencies:
```sh
cd ios
bundle install
bundle exec pod install
cd ..
```

### Running the App

#### Android

```sh
npm run android
# OR
yarn android
```

#### iOS

```sh
npm run ios
# OR
yarn ios
```

## How It Works

The app captures audio through the device's microphone, processes it using native modules, and then feeds the audio data into the Whisper TFLite model for transcription. The transcribed text is then displayed to the user.

The entire process occurs on-device, ensuring privacy and allowing for offline usage.


