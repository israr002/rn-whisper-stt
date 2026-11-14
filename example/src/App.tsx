import React, { useEffect, useRef, useState } from 'react';
import {
  Image,
  PermissionsAndroid,
  Platform,
  SafeAreaView,
  ScrollView,
  Settings,
  StatusBar,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import {
  addErrorListener,
  addTranscriptListener,
  downloadModel,
  init,
  requestMicrophonePermission,
  startRecording,
  stopRecording,
} from 'react-native-whisper-stt';
import Record from './record.png';
import RecordingWaves from './components/RecordingWaves';

type Segment = {
  key: string;
  session: number;
  text: string;
  isFinal: boolean;
};

// base.en (~82 MB) handles names and accents much better than tiny.en (~44 MB)
const MODEL = 'base.en';

async function requestPermissions(): Promise<boolean> {
  const mic = await requestMicrophonePermission();
  if (Platform.OS === 'android' && Platform.Version >= 33) {
    // Lets the "Transcribing" notification show; recording works without it
    await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
    );
  }
  return mic;
}

function App(): React.JSX.Element {
  const [status, setStatus] = useState('Preparing model…');
  const [ready, setReady] = useState(false);
  const [recording, setRecording] = useState(false);
  const [segments, setSegments] = useState<Segment[]>([]);
  const session = useRef(0);
  const scroll = useRef<ScrollView>(null);

  useEffect(() => {
    (async () => {
      try {
        // Downloads once, then loads from app storage
        await downloadModel(MODEL, (e) =>
          setStatus(
            e.progress >= 0
              ? `Downloading model… ${Math.round(e.progress * 100)}%`
              : 'Downloading model…'
          )
        );
        setStatus('Loading model…');
        await init({ model: MODEL });
        setStatus('');
        setReady(true);
      } catch (e: any) {
        setStatus(`Failed: ${e.message}`);
      }
    })();
  }, []);

  useEffect(() => {
    const sub = addTranscriptListener((e) => {
      const key = `${session.current}-${e.id}`;
      setSegments((prev) => {
        const rest = prev.filter((s) => s.key !== key);
        // An empty final means the segment was noise: drop its partial
        if (e.isFinal && !e.text) {
          return rest;
        }
        const index = prev.findIndex((s) => s.key === key);
        const next = {
          key,
          session: session.current,
          text: e.text,
          isFinal: e.isFinal,
        };
        if (index === -1) {
          return [...prev, next];
        }
        rest.splice(index, 0, next);
        return rest;
      });
    });
    const errSub = addErrorListener((e) =>
      console.warn('Whisper error', e.code, e.message)
    );
    return () => {
      sub.remove();
      errSub.remove();
    };
  }, []);

  // Test hook for headless simulator runs: launch with `-autoRecordSeconds 15` (iOS launch arguments
  // are readable through Settings) to record for that long without tapping
  useEffect(() => {
    const seconds =
      Platform.OS === 'ios' ? Number(Settings.get('autoRecordSeconds')) : 0;
    if (!ready || !(seconds > 0)) {
      return;
    }
    session.current += 1;
    setRecording(true);
    requestPermissions()
      .then(() => startRecording())
      .then(
        () =>
          new Promise<void>((resolve) => setTimeout(resolve, seconds * 1000))
      )
      .then(() => stopRecording())
      .then((text) => console.log('transcribedText', text))
      .catch((e) => {
        console.error('autoRecord failed', e?.message, e?.stack);
        setStatus(`Failed: ${e.message}`);
      })
      .finally(() => setRecording(false));
  }, [ready]);

  const toggleRecording = async () => {
    if (!ready) {
      return;
    }
    if (recording) {
      setRecording(false);
      // Resolves after the last utterance is transcribed; segments already arrived as events
      const transcribedText = await stopRecording();
      console.log('transcribedText', transcribedText);
      return;
    }
    if (!(await requestPermissions())) {
      setStatus('Microphone permission is required');
      return;
    }
    session.current += 1;
    setRecording(true);
    try {
      // Optional: startRecording({ prompt: 'Israr Khan, Zerodha' }) nudges Whisper towards names it may misspell
      await startRecording();
    } catch (e: any) {
      setRecording(false);
      setStatus(`Failed: ${e.message}`);
    }
  };

  return (
    <SafeAreaView style={styles.mainContainer}>
      <StatusBar barStyle={'dark-content'} backgroundColor={'white'} />
      <View style={styles.container}>
        <View style={styles.recorderContainer}>
          <RecordingWaves recording={recording} />
          <TouchableOpacity
            style={[styles.button, !ready && styles.buttonDisabled]}
            onPress={toggleRecording}
            disabled={!ready}
          >
            {recording ? (
              <View style={styles.stopIcon} />
            ) : (
              <Image source={Record} style={styles.icon} />
            )}
          </TouchableOpacity>
          {status ? <Text style={styles.status}>{status}</Text> : null}
        </View>
        {/* One running transcript: utterances flow on as sentences, each recording is a paragraph */}
        <ScrollView
          ref={scroll}
          contentContainerStyle={styles.transcriptContainer}
          onContentSizeChange={() =>
            scroll.current?.scrollToEnd({ animated: true })
          }
        >
          <Text style={styles.transcript}>
            {segments.map((item, i) => {
              const prev = segments[i - 1];
              const separator = !prev
                ? ''
                : prev.session !== item.session
                  ? '\n\n'
                  : ' ';
              return (
                <Text
                  key={item.key}
                  style={!item.isFinal ? styles.partialText : undefined}
                >
                  {separator + item.text}
                </Text>
              );
            })}
          </Text>
        </ScrollView>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  mainContainer: {
    flex: 1,
    backgroundColor: 'white',
  },
  container: {
    flex: 1,
    backgroundColor: 'white',
    marginTop: StatusBar.currentHeight,
  },
  recorderContainer: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 20,
  },
  button: {
    borderRadius: 30,
    backgroundColor: 'teal',
    height: 60,
    width: 60,
    alignItems: 'center',
    justifyContent: 'center',
  },
  icon: {
    height: 30,
    width: 30,
    tintColor: 'white',
  },
  buttonDisabled: {
    opacity: 0.4,
  },
  status: {
    marginTop: 12,
    color: 'gray',
    fontSize: 14,
  },
  stopIcon: {
    height: 20,
    width: 20,
    backgroundColor: 'white',
  },
  transcriptContainer: {
    paddingHorizontal: 20,
    paddingVertical: 10,
  },
  transcript: {
    color: 'black',
    fontSize: 17,
    lineHeight: 26,
  },
  partialText: {
    color: 'gray',
    fontStyle: 'italic',
  },
});

export default App;
