import React, { useState } from 'react';
import {
  FlatList,
  Image,
  NativeModules,
  Platform,
  StatusBar,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { requestPermission } from './src/utils/permissions';
import { PERMISSIONS } from 'react-native-permissions';
import Record from './src/assets/record.png';
import RecordingWaves from './src/components/RecordingWaves';

const { WhisperModule } = NativeModules;

function App(): React.JSX.Element {
  const [recording, setRecording] = useState(false);
  const [transcriptions, setTranscriptions] = useState<string[]>([]);

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
      setRecording(true);
      await WhisperModule.startRecording();
    } else {
      stopRecording();
    }
  };

  const stopRecording = async () => {
    setRecording(false);

    const transcribedText = await WhisperModule.stopRecording();
    console.log('transcribedText', transcribedText);

    if (transcribedText) {
      setTranscriptions((prev) => [ ...prev,transcribedText]); // Add new text at the top
    }
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
          data={transcriptions}
          keyExtractor={(_, index) => index.toString()}
          contentContainerStyle={styles.listContainer}
          renderItem={({ item }) => (
            <View style={styles.listItem}>
              <Text style={styles.listItemText}>{item}</Text>
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
});

export default App;
