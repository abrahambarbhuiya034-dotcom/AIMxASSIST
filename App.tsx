/**
 * AIMxASSIST — v8.1
 * Main React Native UI
 *
 * v8.1 changes:
 *  - Trajectory lines start at the COIN HIT POINT, not the striker centre
 *  - All lines clipped to board boundary — nothing drawn outside the board
 *  - Trajectory cached: recomputed only when striker actually moves (no per-frame recalc)
 *  - Max 2 wall-bounce segments drawn per shot, each fading out
 *  - Floating button now smoothly snaps to nearest screen edge on release
 *  - Autoplay stability threshold raised: less sensitive to CV jitter → fires more reliably
 *  - Popup fades in/out smoothly
 */

import React, {useState, useEffect, useCallback} from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Alert,
  Switch,
  ScrollView,
  Platform,
  StatusBar,
  NativeModules,
  Linking,
} from 'react-native';
import Slider from '@react-native-community/slider';

const {OverlayModule} = NativeModules;

type ShotMode = 'ALL' | 'DIRECT' | 'AI' | 'GOLDEN' | 'LUCKY';

const SHOT_MODES: {mode: ShotMode; label: string; desc: string}[] = [
  {mode: 'ALL',    label: 'All Lines', desc: 'Show every prediction simultaneously'},
  {mode: 'DIRECT', label: 'Direct',    desc: 'Striker straight line only'},
  {mode: 'AI',     label: 'AI Aim',    desc: 'Striker + coin chain reactions'},
  {mode: 'GOLDEN', label: 'Golden',    desc: 'Up to one cushion bounce'},
  {mode: 'LUCKY',  label: 'Lucky',     desc: 'Up to two cushion bounces'},
];

const LINE_LEGEND = [
  {color: '#FFD700', label: '#1 Best (Gold)'},
  {color: '#00E5FF', label: '#2 (Cyan)'},
  {color: '#FF8A00', label: '#3 (Orange)'},
  {color: '#D946EF', label: '#4 (Purple)'},
  {color: '#22C55E', label: '#5 (Green)'},
];

export default function App() {
  const [hasOverlay, setHasOverlay]           = useState(false);
  const [overlayActive, setOverlayActive]     = useState(false);
  const [autoDetect, setAutoDetect]           = useState(false);
  const [selectedMode, setSelectedMode]       = useState<ShotMode>('ALL');
  const [sensitivity, setSensitivity]         = useState(1.0);
  const [detectThreshold, setDetectThreshold] = useState(36);

  const [autoPlay, setAutoPlayState]          = useState(false);
  const [autoPlayDelay, setAutoPlayDelay]     = useState(2.0);
  const [accessibilityReady, setAccessibilityReady] = useState(false);

  useEffect(() => {
    refreshStatus();
    const t = setInterval(refreshStatus, 2000);
    return () => clearInterval(t);
  }, []);

  const refreshStatus = useCallback(async () => {
    try {
      const can = await OverlayModule.canDrawOverlays();
      setHasOverlay(can);
    } catch { setHasOverlay(true); }

    try {
      const active = await OverlayModule.isAutoDetectActive();
      setAutoDetect(active);
    } catch {}

    try {
      const ready = await OverlayModule.isAccessibilityReady();
      setAccessibilityReady(ready);
    } catch { setAccessibilityReady(false); }

    try {
      const ap = await OverlayModule.isAutoPlayEnabled();
      setAutoPlayState(ap);
    } catch {}
  }, []);

  const requestOverlay = useCallback(() => {
    try {
      OverlayModule.requestOverlayPermission();
      setTimeout(refreshStatus, 1500);
    } catch {
      Alert.alert('Permission Needed',
        'Please grant "Display over other apps" in Settings.',
        [{text: 'Open Settings', onPress: () => Linking.openSettings()}]);
    }
  }, [refreshStatus]);

  const toggleOverlay = useCallback(async () => {
    if (!hasOverlay) { requestOverlay(); return; }
    try {
      if (overlayActive) {
        if (autoPlay) {
          try { await OverlayModule.setAutoPlay(false); } catch {}
          setAutoPlayState(false);
        }
        await OverlayModule.stopOverlay();
        setOverlayActive(false);
        setAutoDetect(false);
      } else {
        await OverlayModule.startOverlay();
        setOverlayActive(true);
      }
    } catch (e: any) {
      Alert.alert('Error', e.message || 'Could not toggle overlay');
    }
  }, [hasOverlay, overlayActive, autoPlay, requestOverlay]);

  const toggleAutoDetect = useCallback(async () => {
    if (!overlayActive) {
      Alert.alert('Start Overlay First',
        'Turn on the Aim Overlay before enabling auto-detect.');
      return;
    }
    try {
      if (autoDetect) {
        await OverlayModule.stopScreenCapture();
        setAutoDetect(false);
      } else {
        await OverlayModule.requestScreenCapture();
        setTimeout(refreshStatus, 2500);
      }
    } catch (e: any) {
      Alert.alert('Error', e.message || 'Could not toggle screen capture');
    }
  }, [overlayActive, autoDetect, refreshStatus]);

  const toggleAutoPlay = useCallback(async () => {
    if (!overlayActive || !autoDetect) {
      Alert.alert('Prerequisites Missing',
        'Start the Overlay and enable Auto-Detect first, then turn on AutoPlay.');
      return;
    }
    if (!accessibilityReady && !autoPlay) {
      Alert.alert(
        'Accessibility Required',
        'Enable "AIMxASSIST" in Android Accessibility Settings so the app can ' +
        'swipe the striker automatically.',
        [
          {text: 'Open Accessibility', onPress: () => OverlayModule.requestAccessibilityPermission()},
          {text: 'Cancel', style: 'cancel'},
        ]);
      return;
    }
    try {
      const next = !autoPlay;
      await OverlayModule.setAutoPlay(next);
      setAutoPlayState(next);
      if (next) {
        Alert.alert(
          'AutoPlay Enabled',
          'Now switch to your carrom game. The app fires automatically once it detects a stable board — it will NOT fire while you are on this screen.',
          [{text: 'Got it'}]);
      }
    } catch (e: any) {
      if (e.code === 'ERR_NO_ACCESSIBILITY') {
        Alert.alert('Accessibility Not Ready',
          'Enable "AIMxASSIST" in Accessibility Settings first.',
          [{text: 'Open Settings', onPress: () => OverlayModule.requestAccessibilityPermission()}]);
      } else {
        Alert.alert('Error', e.message || 'Could not toggle autoplay');
      }
    }
  }, [overlayActive, autoDetect, accessibilityReady, autoPlay]);

  const shootNow = useCallback(async () => {
    if (!overlayActive || !autoDetect) {
      Alert.alert('Not Ready', 'Overlay and Auto-Detect must be active first.');
      return;
    }
    if (!accessibilityReady) {
      Alert.alert('Accessibility Not Ready',
        'Enable "AIMxASSIST" in Accessibility Settings first.',
        [{text: 'Open Settings', onPress: () => OverlayModule.requestAccessibilityPermission()}]);
      return;
    }
    try {
      await OverlayModule.shootNow();
    } catch (e: any) {
      Alert.alert('Shot Failed', e.message || 'Make sure you are in the carrom game with a stable board');
    }
  }, [overlayActive, autoDetect, accessibilityReady]);

  const handleModeSelect = useCallback((mode: ShotMode) => {
    setSelectedMode(mode);
    try { OverlayModule.setShotMode(mode); } catch {}
  }, []);

  const handleSensitivityChange = useCallback((val: number) => {
    setSensitivity(val);
    try { OverlayModule.setSensitivity(val); } catch {}
  }, []);

  const handleThresholdChange = useCallback((val: number) => {
    setDetectThreshold(val);
    try { OverlayModule.setDetectionThreshold(val); } catch {}
  }, []);

  const handleAutoPlayDelayChange = useCallback(async (val: number) => {
    setAutoPlayDelay(val);
    try { await OverlayModule.setAutoPlayDelay(Math.round(val * 1000)); } catch {}
  }, []);

  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor="#0D0D1A" />

      <View style={styles.header}>
        <Text style={styles.logo}>AIMxASSIST</Text>
        <Text style={styles.subtitle}>Auto-Detect Carrom Aim Assist • v8.1</Text>
      </View>

      <ScrollView style={styles.scroll}
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}>

        {!hasOverlay && (
          <TouchableOpacity style={styles.permBanner} onPress={requestOverlay}>
            <Text style={styles.permBannerText}>
              Grant "Display over other apps" to use the overlay
            </Text>
            <Text style={styles.permBannerCta}>Tap to grant →</Text>
          </TouchableOpacity>
        )}

        {/* Overlay + Auto-Detect */}
        <View style={styles.card}>
          <View style={styles.row}>
            <View style={{flex: 1, paddingRight: 8}}>
              <Text style={styles.cardTitle}>Aim Overlay</Text>
              <Text style={styles.cardSub}>
                {overlayActive
                  ? 'Running — aim lines visible on top of Carrom Pool'
                  : 'Start to draw aim lines over any carrom game'}
              </Text>
            </View>
            <Switch value={overlayActive} onValueChange={toggleOverlay}
              trackColor={{false: '#333', true: '#FFD700'}}
              thumbColor={overlayActive ? '#FFF' : '#888'} />
          </View>

          <View style={[styles.row, {marginTop: 14}]}>
            <View style={{flex: 1, paddingRight: 8}}>
              <Text style={styles.cardTitle}>Auto-Detect (CV)</Text>
              <Text style={styles.cardSub}>
                {autoDetect
                  ? 'Reading screen — striker, coins and pockets detected automatically'
                  : 'Pure-Java detector: reads screen pixels in real-time (one-time permission)'}
              </Text>
            </View>
            <Switch value={autoDetect} onValueChange={toggleAutoDetect}
              trackColor={{false: '#333', true: '#00E5FF'}}
              thumbColor={autoDetect ? '#FFF' : '#888'} />
          </View>
        </View>

        {/* AutoPlay */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>AutoPlay</Text>
          <Text style={styles.cardSub}>
            Automatically swipes the striker. Fires ONLY when the carrom board
            is stable — never while you are on this screen.
            Requires Overlay + Auto-Detect + Accessibility.
          </Text>

          {!accessibilityReady && (
            <TouchableOpacity style={styles.permBanner}
              onPress={() => OverlayModule.requestAccessibilityPermission()}>
              <Text style={styles.permBannerText}>
                "AIMxASSIST" not enabled in Accessibility Settings
              </Text>
              <Text style={styles.permBannerCta}>Tap to open Settings →</Text>
            </TouchableOpacity>
          )}

          <View style={[styles.row, {marginTop: 10}]}>
            <View style={{flex: 1, paddingRight: 8}}>
              <Text style={styles.cardTitle}>Auto Shoot</Text>
              <Text style={styles.cardSub}>
                {autoPlay
                  ? 'ACTIVE — switch to carrom game, fires on stable board'
                  : 'Off — tap to start automatic shooting'}
              </Text>
            </View>
            <Switch value={autoPlay} onValueChange={toggleAutoPlay}
              trackColor={{false: '#333', true: '#22C55E'}}
              thumbColor={autoPlay ? '#FFF' : '#888'} />
          </View>

          <View style={[styles.rowSpread, {marginTop: 12}]}>
            <Text style={styles.cardTitle}>Min Delay Between Shots</Text>
            <Text style={styles.valueLabel}>{autoPlayDelay.toFixed(1)} s</Text>
          </View>
          <Slider style={styles.slider}
            minimumValue={0.5} maximumValue={5.0} step={0.1}
            value={autoPlayDelay} onValueChange={handleAutoPlayDelayChange}
            minimumTrackTintColor="#22C55E" maximumTrackTintColor="#333"
            thumbTintColor="#22C55E" />
          <View style={styles.rowSpread}>
            <Text style={styles.sliderEndLabel}>Fast (0.5 s)</Text>
            <Text style={styles.sliderEndLabel}>Slow (5 s)</Text>
          </View>

          <TouchableOpacity
            style={[styles.shootNowBtn,
              (!overlayActive || !autoDetect || !accessibilityReady) && styles.btnDisabled]}
            onPress={shootNow}
            disabled={!overlayActive || !autoDetect || !accessibilityReady}>
            <Text style={styles.shootNowText}>▶  Shoot Best Shot Now</Text>
          </TouchableOpacity>
        </View>

        {/* Prediction Lines */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Prediction Lines</Text>
          <Text style={styles.cardSub}>
            Up to 5 lines — ranked by AI ghost-ball score. Gold = best shot.
          </Text>
          <View style={styles.shotGrid}>
            {SHOT_MODES.map(({mode, label, desc}) => (
              <TouchableOpacity key={mode}
                style={[styles.shotBtn,
                  selectedMode === mode && styles.shotBtnActive]}
                onPress={() => handleModeSelect(mode)}>
                <Text style={[styles.shotLabel,
                  selectedMode === mode && styles.shotLabelActive]}>{label}</Text>
                <Text style={styles.shotDesc}>{desc}</Text>
              </TouchableOpacity>
            ))}
          </View>

          <View style={styles.legend}>
            {LINE_LEGEND.map(item => (
              <LegendDot key={item.label} color={item.color} label={item.label} />
            ))}
            <LegendDot color="#FFFFFF" label="Striker path" />
            <LegendDot color="#00E5FF" label="Wall bounce" />
            <LegendDot color="#22C55E" label="Coin → pocket" />
          </View>
        </View>

        {/* Shot Power */}
        <View style={styles.card}>
          <View style={styles.rowSpread}>
            <Text style={styles.cardTitle}>Shot Power</Text>
            <Text style={styles.valueLabel}>{sensitivity.toFixed(1)}x</Text>
          </View>
          <Slider style={styles.slider}
            minimumValue={0.3} maximumValue={3.0} step={0.1}
            value={sensitivity} onValueChange={handleSensitivityChange}
            minimumTrackTintColor="#FFD700" maximumTrackTintColor="#333"
            thumbTintColor="#FFD700" />
          <View style={styles.rowSpread}>
            <Text style={styles.sliderEndLabel}>Soft</Text>
            <Text style={styles.sliderEndLabel}>Hard</Text>
          </View>
        </View>

        {/* Detection Sensitivity */}
        <View style={styles.card}>
          <View style={styles.rowSpread}>
            <Text style={styles.cardTitle}>Detection Sensitivity</Text>
            <Text style={[styles.valueLabel, {color: '#00E5FF'}]}>{detectThreshold}</Text>
          </View>
          <Text style={styles.cardSub}>
            Lower = detects more circles (more false positives).
            Raise to 35–45 to reduce ghost circles.
          </Text>
          <Slider style={styles.slider}
            minimumValue={12} maximumValue={50} step={1}
            value={detectThreshold} onValueChange={handleThresholdChange}
            minimumTrackTintColor="#00E5FF" maximumTrackTintColor="#333"
            thumbTintColor="#00E5FF" />
        </View>

        {/* How To Use */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>How to Use</Text>
          <Text style={styles.howToStep}>1. Grant "Draw over apps" permission above</Text>
          <Text style={styles.howToStep}>2. Turn on the Aim Overlay</Text>
          <Text style={styles.howToStep}>3. Enable Auto-Detect (one-time screen capture permission)</Text>
          <Text style={styles.howToStep}>4. Open Carrom Pool — lines appear automatically</Text>
          <Text style={styles.howToStep}>5. Gold line = best shot. Cyan = 2nd best, etc.</Text>
          <Text style={styles.howToStep}>6. For AutoPlay: enable "AIMxASSIST" in Android</Text>
          <Text style={styles.howToStep}>   Accessibility Settings, flip Auto Shoot ON, then</Text>
          <Text style={styles.howToStep}>   switch to your carrom game — it fires by itself</Text>
          <Text style={styles.howToTip}>
            AutoPlay waits for a stable board before firing — it will never
            activate while you are on this app. Use "Shoot Best Shot Now"
            for a single manual trigger anytime.
          </Text>
        </View>

        <View style={styles.footer}>
          <Text style={styles.footerText}>
            AIMxASSIST v8.1 • Ghost-Ball AI • Stable AutoPlay • Android 7+
          </Text>
        </View>
      </ScrollView>
    </View>
  );
}

function LegendDot({color, label}: {color: string; label: string}) {
  return (
    <View style={styles.legendItem}>
      <View style={[styles.legendSwatch, {backgroundColor: color}]} />
      <Text style={styles.legendLabel}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root:          {flex: 1, backgroundColor: '#0D0D1A'},
  header:        {
    paddingTop: Platform.OS === 'android' ? StatusBar.currentHeight ?? 24 : 44,
    paddingBottom: 16, paddingHorizontal: 20,
    backgroundColor: '#13132A', borderBottomWidth: 1, borderBottomColor: '#222244',
  },
  logo:          {color: '#FFD700', fontSize: 26, fontWeight: '900', letterSpacing: 1},
  subtitle:      {color: '#8888BB', fontSize: 12, marginTop: 2},
  scroll:        {flex: 1},
  scrollContent: {padding: 16, paddingBottom: 40},
  permBanner:    {
    backgroundColor: '#2A1A00', borderWidth: 1, borderColor: '#FFD700',
    borderRadius: 10, padding: 14, marginBottom: 12,
  },
  permBannerText: {color: '#FFC', fontSize: 13},
  permBannerCta:  {color: '#FFD700', fontSize: 13, fontWeight: '700', marginTop: 4},
  card:           {
    backgroundColor: '#16162E', borderRadius: 14, padding: 16,
    marginBottom: 14, borderWidth: 1, borderColor: '#222244',
  },
  cardTitle:     {color: '#FFFFFF', fontSize: 16, fontWeight: '700', marginBottom: 4},
  cardSub:       {color: '#8888BB', fontSize: 12, marginBottom: 8},
  row:           {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between'},
  rowSpread:     {flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center'},
  shotGrid:      {flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8},
  shotBtn:       {
    width: '47%', backgroundColor: '#1E1E3A', borderRadius: 10, padding: 12,
    borderWidth: 1.5, borderColor: '#333355', alignItems: 'flex-start',
  },
  shotBtnActive:  {borderColor: '#FFD700', backgroundColor: '#26260A'},
  shotLabel:      {color: '#AAA', fontSize: 14, fontWeight: '700'},
  shotLabelActive:{color: '#FFD700'},
  shotDesc:       {color: '#666688', fontSize: 10, marginTop: 3},
  legend:         {flexDirection: 'row', flexWrap: 'wrap', gap: 10, marginTop: 12},
  legendItem:     {flexDirection: 'row', alignItems: 'center'},
  legendSwatch:   {width: 12, height: 4, borderRadius: 2, marginRight: 6},
  legendLabel:    {color: '#AAA', fontSize: 11},
  slider:         {width: '100%', height: 36},
  sliderEndLabel: {color: '#666688', fontSize: 11},
  valueLabel:     {color: '#FFD700', fontSize: 16, fontWeight: '700'},
  shootNowBtn:    {
    marginTop: 12, paddingVertical: 12, borderRadius: 10,
    backgroundColor: '#0A2A10', alignItems: 'center',
    borderWidth: 1.5, borderColor: '#22C55E',
  },
  shootNowText:   {color: '#22C55E', fontSize: 15, fontWeight: '700'},
  btnDisabled:    {opacity: 0.35},
  howToStep:      {color: '#CCCCEE', fontSize: 13, marginBottom: 5, paddingLeft: 4},
  howToTip:       {
    color: '#FFD700', fontSize: 12, marginTop: 8,
    backgroundColor: '#22220A', padding: 10, borderRadius: 8,
  },
  footer:         {alignItems: 'center', marginTop: 10},
  footerText:     {color: '#444466', fontSize: 11},
});
