import React, { useRef, useEffect } from 'react';
import { Animated, Easing, StyleSheet, View } from 'react-native';

function RecordingWaves({ recording }: { recording: boolean }) {
  const waveAnim1 = useRef(new Animated.Value(0)).current;
  const waveAnim2 = useRef(new Animated.Value(0)).current;

  const startWaveAnimation = () => {
    Animated.loop(
      Animated.parallel([
        Animated.timing(waveAnim1, {
          toValue: 1,
          duration: 1500,
          easing: Easing.ease,
          useNativeDriver: true,
        }),
        Animated.timing(waveAnim2, {
          toValue: 1,
          duration: 2000,
          delay: 500,
          easing: Easing.ease,
          useNativeDriver: true,
        }),
      ])
    ).start();
  };

  if (recording) {
    startWaveAnimation();
  } else {
    waveAnim1.setValue(0);
    waveAnim2.setValue(0);
  }

  return (
    <View style={styles.waveContainer}>
      {recording && (
        <>
          <Animated.View
            style={[
              styles.wave,
              {
                transform: [{ scale: waveAnim1 }],
                opacity: waveAnim1.interpolate({
                  inputRange: [0, 1],
                  outputRange: [1, 0],
                }),
              },
            ]}
          />
          <Animated.View
            style={[
              styles.wave,
              {
                transform: [{ scale: waveAnim2 }],
                opacity: waveAnim2.interpolate({
                  inputRange: [0, 1],
                  outputRange: [1, 0],
                }),
              },
            ]}
          />
        </>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  waveContainer: {
    position: 'absolute',
    width: 100,
    height: 100,
    alignItems: 'center',
    justifyContent: 'center',
  },
  wave: {
    position: 'absolute',
    width: 100,
    height: 100,
    borderRadius: 50,
    backgroundColor: 'rgba(0,128,128,1)',
  },
});

export default RecordingWaves;
