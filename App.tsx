import React, { useEffect, useRef, useState } from 'react';
import {
  FlatList,
  Image,
  NativeEventEmitter,
  NativeModules,
  Platform,
  StatusBar,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { requestPermission } from './src/utils/permissions';
import { PERMISSIONS, requestNotifications } from 'react-native-permissions';
import Record from './src/assets/record.png';
import RecordingWaves from './src/components/RecordingWaves';

const { WhisperModule } = NativeModules;
const whisperEvents = new NativeEventEmitter(WhisperModule);

type TranscriptEvent = {
  id: number;
  text: string;
  isFinal: boolean;
  audioMs: number;
  latencyMs: number;
};

type Segment = { key: string; text: string; isFinal: boolean };

function App(): React.JSX.Element {
  const [recording, setRecording] = useState(false);
  const [segments, setSegments] = useState<Segment[]>([]);
  const session = useRef(0);
  const list = useRef<FlatList<Segment>>(null);

  useEffect(() => {
    const sub = whisperEvents.addListener('WhisperTranscript', (e: TranscriptEvent) => {
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
    const errSub = whisperEvents.addListener('WhisperError', (e: { message: string }) =>
      console.warn('Whisper error', e.message),
    );
    return () => {
      sub.remove();
      errSub.remove();
    };
  }, []);

  const startRecording = async () => {
    if (!recording) {
      const hasPermission = await requestPermission(
        Platform.OS === 'android'
          ? PERMISSIONS.ANDROID.RECORD_AUDIO
          : PERMISSIONS.IOS.SPEECH_RECOGNITION,
      );
      if (!hasPermission) {
        return;
      }
      if (Platform.OS === 'android') {
        // Lets the "Transcribing" notification show; recording works without it
        await requestNotifications([]).catch(() => {});
      }
      session.current += 1;
      setRecording(true);
      await WhisperModule.startRecording();
    } else {
      stopRecording();
    }
  };

  const stopRecording = async () => {
    setRecording(false);

    // Resolves after the last utterance is transcribed; segments already arrived as events
    const transcribedText = await WhisperModule.stopRecording();
    console.log('transcribedText', transcribedText);
  };

  return (
    <View style={styles.mainContainer}>
      <StatusBar barStyle={'dark-content'} backgroundColor={'white'} />
      <View style={styles.container}>
        <View style={styles.recorderContainer}>
          <RecordingWaves recording={recording} />
          <TouchableOpacity style={styles.button} onPress={startRecording}>
            {recording ? <View style={styles.stopIcon} /> : <Image source={Record} style={styles.icon} />}
          </TouchableOpacity>
        </View>
        <FlatList
          ref={list}
          data={segments}
          keyExtractor={(item) => item.key}
          contentContainerStyle={styles.listContainer}
          onContentSizeChange={() => list.current?.scrollToEnd({ animated: true })}
          renderItem={({ item }) => (
            <View style={styles.listItem}>
              <Text style={[styles.listItemText, !item.isFinal && styles.partialText]}>{item.text}</Text>
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
