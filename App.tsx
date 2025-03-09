import React, { useState } from 'react';
import {
  Image,
  Platform,
  StatusBar,
  StyleSheet,
  TouchableOpacity,
  View,
} from 'react-native';
import { requestPermission } from './src/utils/permissions';
import { PERMISSIONS } from 'react-native-permissions';
import Record from './src/assets/record.png';
import RecordingWaves from './src/components/RecordingWaves';

function App(): React.JSX.Element {
  const [recording, setRecording] = useState(false);

  const startRecording = async () => {
    if (!recording) {
      setRecording(true);
      const hasPermission = await requestPermission(
        Platform.OS === 'android'
          ? PERMISSIONS.ANDROID.RECORD_AUDIO
          : PERMISSIONS.IOS.SPEECH_RECOGNITION,
      );
      if (!hasPermission) {
        console.log('Permission denied');
        return;
      }
    } else {
      setRecording(false);
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
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
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
});

export default App;
