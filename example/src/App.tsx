import React, { useEffect, useRef, useState } from 'react';
import {
  FlatList,
  Image,
  PermissionsAndroid,
  Platform,
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
  startRecording,
  stopRecording,
} from 'react-native-whisper-stt';
import Record from './record.png';
import RecordingWaves from './components/RecordingWaves';

type Segment = { key: string; text: string; isFinal: boolean };

async function requestPermissions(): Promise<boolean> {
  if (Platform.OS !== 'android') {
    return true;
  }
  const mic = await PermissionsAndroid.request(
    PermissionsAndroid.PERMISSIONS.RECORD_AUDIO
  );
  if (Platform.Version >= 33) {
    // Lets the "Transcribing" notification show; recording works without it
    await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
    );
  }
  return mic === PermissionsAndroid.RESULTS.GRANTED;
}

function App(): React.JSX.Element {
  const [status, setStatus] = useState('Preparing model…');
  const [ready, setReady] = useState(false);
  const [recording, setRecording] = useState(false);
  const [segments, setSegments] = useState<Segment[]>([]);
  const session = useRef(0);
  const list = useRef<FlatList<Segment>>(null);

  useEffect(() => {
    (async () => {
      try {
        // Downloads once (~44 MB), then loads from app storage
        await downloadModel('tiny.en', (e) =>
          setStatus(
            e.progress >= 0
              ? `Downloading model… ${Math.round(e.progress * 100)}%`
              : 'Downloading model…'
          )
        );
        setStatus('Loading model…');
        await init({ model: 'tiny.en' });
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
        const next = { key, text: e.text, isFinal: e.isFinal };
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
      await startRecording();
    } catch (e: any) {
      setRecording(false);
      setStatus(`Failed: ${e.message}`);
    }
  };

  return (
    <View style={styles.mainContainer}>
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
        <FlatList
          ref={list}
          data={segments}
          keyExtractor={(item) => item.key}
          contentContainerStyle={styles.listContainer}
          onContentSizeChange={() =>
            list.current?.scrollToEnd({ animated: true })
          }
          renderItem={({ item }) => (
            <View style={styles.listItem}>
              <Text
                style={[
                  styles.listItemText,
                  !item.isFinal && styles.partialText,
                ]}
              >
                {item.text}
              </Text>
            </View>
          )}
        />
      </View>
    </View>
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
  listContainer: {
    flexGrow: 1,
    paddingVertical: 10,
  },
  listItem: {
    marginHorizontal: 20,
    borderBottomWidth: 1,
    borderColor: 'rgba(0,0,0,0.2)',
    paddingVertical: 10,
  },
  listItemText: {
    color: 'black',
    textAlign: 'center',
    fontSize: 16,
  },
  partialText: {
    color: 'gray',
    fontStyle: 'italic',
  },
});

export default App;
