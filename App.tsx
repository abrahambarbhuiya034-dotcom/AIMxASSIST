/**
 * AIMxASSIST - Carrom Pool Aim Assistant
 * Main React Native UI — v5.0 (AutoPlay edition)
 *
 * - Fully automatic aim lines, no touch needed
 * - Board auto-detected via wood-colour CV
 * - Wall-bounce + 2-cushion indirect prediction lines
 * - AutoPlay: auto-shoots striker via Accessibility Service (no root!)
 * - Watermark: created by abraham / Xhay
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

interface MarginSettings {
  d2X: number; d2Y: number;
  e2X: number; e2Y: number;
  insideX: number; insideY: number;
}

const SHOT_MODES: {mode: ShotMode; label: string; desc: string}[] = [
  {mode: 'ALL',    label: 'All Lines', desc: 'Show every prediction simultaneously'},
  {mode: 'DIRECT', label: 'Direct',    desc: 'Striker straight line only'},
  {mode: 'AI',     label: 'AI Aim',    desc: 'Direct + 1-cushion bounce shots'},
  {mode: 'GOLDEN', label: 'Golden',    desc: '1-cushion wall bounce only'},
  {mode: 'LUCKY',  label: 'Lucky',     desc: 'Up to 2-cushion bounces'},
];

export default function App() {
  const [hasOverlay, setHasOverlay]         = useState(false);
  const [overlayActive, setOverlayActive]   = useState(false);
  const [autoDetect, setAutoDetect]         = useState(false);
  const [autoPlay, setAutoPlay]             = useState(false);
  const [accessibilityReady, setAccessibilityReady] = useState(false);
  const [selectedMode, setSelectedMode]     = useState<ShotMode>('ALL');
  const [sensitivity, setSensitivity]       = useState(1.0);
  const [detectThreshold, setDetectThreshold] = useState(36);
  const [margin, setMargin] = useState<MarginSettings>({
    d2X: 0, d2Y: 0, e2X: 0, e2Y: 0, insideX: 0, insideY: 0,
  });
  const [activeMarginTab, setActiveMarginTab] =
    useState<'D2' | 'E2' | 'INSIDE'>('D2');

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
    } catch {}
    try {
      const ap = await OverlayModule.isAutoPlayEnabled();
      setAutoPlay(ap);
    } catch {}
  }, []);

  const requestOverlay = useCallback(() => {
    try {
      OverlayModule.requestOverlayPermission();
      setTimeout(refreshStatus, 1500);
    } catch {
      Alert.alert(
        'Permission Needed',
        'Please grant "Display over other apps" in Settings.',
        [{text: 'Open Settings', onPress: () => Linking.openSettings()}],
      );
    }
  }, [refreshStatus]);

  const toggleOverlay = useCallback(async () => {
    if (!hasOverlay) { requestOverlay(); return; }
    try {
      if (overlayActive) {
        await OverlayModule.stopOverlay();
        setOverlayActive(false);
        setAutoDetect(false);
        setAutoPlay(false);
      } else {
        await OverlayModule.startOverlay();
        setOverlayActive(true);
      }
    } catch (e: any) {
      Alert.alert('Error', e.message || 'Could not toggle overlay');
    }
  }, [hasOverlay, overlayActive, requestOverlay]);

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
    if (!overlayActive) {
      Alert.alert('Start Overlay First', 'Enable the overlay and auto-detect first.');
      return;
    }
    if (!autoDetect) {
      Alert.alert('Enable Auto-Detect First',
        'Auto-Detect must be ON so the app knows when the board is stable.');
      return;
    }
    if (!accessibilityReady) {
      Alert.alert(
        'Accessibility Permission Required',
        'AutoPlay injects swipe gestures to shoot the striker automatically.\n\n' +
        'Steps:\n1. Tap "Open Settings" below\n2. Find "AIMxASSIST" in the list\n' +
        '3. Enable "AIMxASSIST Autoplay"\n4. Come back here and try again.',
        [
          {text: 'Cancel', style: 'cancel'},
          {text: 'Open Settings', onPress: () => {
            try { OverlayModule.requestAccessibilityPermission(); } catch {}
            setTimeout(refreshStatus, 3000);
          }},
        ],
      );
      return;
    }
    try {
      const enabled = !autoPlay;
      await OverlayModule.setAutoPlay(enabled);
      setAutoPlay(enabled);
    } catch (e: any) {
      Alert.alert('AutoPlay Error', e.message);
    }
  }, [overlayActive, autoDetect, accessibilityReady, autoPlay, refreshStatus]);

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

  const handleMarginChange = useCallback(
    (axis: 'X' | 'Y', value: number) => {
      const key = `${activeMarginTab.toLowerCase()}${axis}` as keyof MarginSettings;
      const updated = {...margin, [key]: value};
      setMargin(updated);
      try { OverlayModule.setMarginOffset(updated.d2X, updated.d2Y); } catch {}
    },
    [activeMarginTab, margin],
  );

  const getActiveMargin = () => {
    switch (activeMarginTab) {
      case 'D2':     return {x: margin.d2X, y: margin.d2Y};
      case 'E2':     return {x: margin.e2X, y: margin.e2Y};
      case 'INSIDE': return {x: margin.insideX, y: margin.insideY};
    }
  };

  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor="#0D0D1A" />

      <View style={styles.header}>
        <Text style={styles.logo}>AIMxASSIST</Text>
        <Text style={styles.subtitle}>
          v5.0 • AutoPlay + Indirect Shots • Android 8+
        </Text>
      </View>

      <ScrollView style={styles.scroll}
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}>

        {/* Permission banner */}
        {!hasOverlay && (
          <TouchableOpacity style={styles.permBanner} onPress={requestOverlay}>
            <Text style={styles.permBannerText}>
              Grant "Display over other apps" to use the overlay
            </Text>
            <Text style={styles.permBannerCta}>Tap to grant →</Text>
          </TouchableOpacity>
        )}

        {/* Main controls card */}
        <View style={styles.card}>

          {/* Aim overlay toggle */}
          <View style={styles.row}>
            <View style={{flex: 1, paddingRight: 8}}>
              <Text style={styles.cardTitle}>Aim Overlay</Text>
              <Text style={styles.cardSub}>
                {overlayActive
                  ? 'Running — tap floating icon to show/hide lines'
                  : 'Draw aim lines over Carrom Pool'}
              </Text>
            </View>
            <Switch value={overlayActive} onValueChange={toggleOverlay}
              trackColor={{false: '#333', true: '#FFD700'}}
              thumbColor={overlayActive ? '#FFF' : '#888'} />
          </View>

          {/* Auto-detect toggle */}
          <View style={[styles.row, {marginTop: 14}]}>
            <View style={{flex: 1, paddingRight: 8}}>
              <Text style={styles.cardTitle}>Auto-Detect (CV)</Text>
              <Text style={styles.cardSub}>
                {autoDetect
                  ? 'Reading screen — coins detected automatically'
                  : 'Computer vision detects striker, coins, pockets'}
              </Text>
            </View>
            <Switch value={autoDetect} onValueChange={toggleAutoDetect}
              trackColor={{false: '#333', true: '#00E5FF'}}
              thumbColor={autoDetect ? '#FFF' : '#888'} />
          </View>

          {/* AutoPlay toggle */}
          <View style={[styles.row, {marginTop: 14}]}>
            <View style={{flex: 1, paddingRight: 8}}>
              <Text style={[styles.cardTitle, {color: autoPlay ? '#6B99FF' : '#FFF'}]}>
                AutoPlay {autoPlay ? '(ON)' : ''}
              </Text>
              <Text style={styles.cardSub}>
                {!accessibilityReady
                  ? 'Needs Accessibility permission — tap toggle to set up'
                  : autoPlay
                  ? 'Auto-shooting — waits for stable board then swipes'
                  : 'Auto-shoots striker when board is stable (no root!)'}
              </Text>
            </View>
            <Switch value={autoPlay} onValueChange={toggleAutoPlay}
              trackColor={{false: '#333', true: '#6B99FF'}}
              thumbColor={autoPlay ? '#FFF' : '#888'} />
          </View>

          {/* Accessibility status indicator */}
          {overlayActive && (
            <TouchableOpacity
              style={[styles.accessibilityBadge,
                {backgroundColor: accessibilityReady ? '#0A2A0A' : '#2A1A00',
                 borderColor: accessibilityReady ? '#22C55E' : '#FF8A00'}]}
              onPress={() => {
                if (!accessibilityReady) {
                  try { OverlayModule.requestAccessibilityPermission(); } catch {}
                  setTimeout(refreshStatus, 3000);
                }
              }}>
              <Text style={{color: accessibilityReady ? '#22C55E' : '#FF8A00',
                fontSize: 12, fontWeight: '700'}}>
                {accessibilityReady
                  ? '✓ Accessibility Ready — AutoPlay can inject gestures'
                  : '⚠ Tap here → enable AIMxASSIST in Accessibility Settings'}
              </Text>
            </TouchableOpacity>
          )}
        </View>

        {/* Shot mode */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Prediction Lines</Text>
          <Text style={styles.cardSub}>
            "All Lines" shows direct + wall-bounce shots in different colors
          </Text>
          <View style={styles.shotGrid}>
            {SHOT_MODES.map(({mode, label, desc}) => (
              <TouchableOpacity key={mode}
                style={[styles.shotBtn,
                  selectedMode === mode && styles.shotBtnActive]}
                onPress={() => handleModeSelect(mode)}>
                <Text style={[styles.shotLabel,
                  selectedMode === mode && styles.shotLabelActive]}>
                  {label}
                </Text>
                <Text style={styles.shotDesc}>{desc}</Text>
              </TouchableOpacity>
            ))}
          </View>

          <View style={styles.legend}>
            <LegendDot color="#FFD700" label="Direct" />
            <LegendDot color="#AADDFF" label="1-cushion" />
            <LegendDot color="#D946EF" label="2-cushion" />
            <LegendDot color="#22C55E" label="Pocket line" />
          </View>
        </View>

        {/* Detection threshold */}
        <View style={styles.card}>
          <View style={styles.rowSpread}>
            <Text style={styles.cardTitle}>Detection Sensitivity</Text>
            <Text style={[styles.valueLabel, {color: '#00E5FF'}]}>
              {detectThreshold}
            </Text>
          </View>
          <Text style={styles.cardSub}>
            Lower = more circles detected (more false positives). Raise to 35–45
            if pink circles appear everywhere. Higher = only clearest circles.
          </Text>
          <Slider style={styles.slider}
            minimumValue={12} maximumValue={50} step={1}
            value={detectThreshold} onValueChange={handleThresholdChange}
            minimumTrackTintColor="#00E5FF" maximumTrackTintColor="#333"
            thumbTintColor="#00E5FF" />
        </View>

        {/* Margin calibration */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Margin Calibration</Text>
          <Text style={styles.cardSub}>
            Nudge if aim lines are slightly offset from actual board position
          </Text>
          <View style={styles.tabRow}>
            {(['D2', 'E2', 'INSIDE'] as const).map(tab => (
              <TouchableOpacity key={tab}
                style={[styles.tab, activeMarginTab === tab && styles.tabActive]}
                onPress={() => setActiveMarginTab(tab)}>
                <Text style={[styles.tabText,
                  activeMarginTab === tab && styles.tabTextActive]}>
                  {tab}
                </Text>
              </TouchableOpacity>
            ))}
          </View>
          <View style={styles.marginRow}>
            <Text style={styles.marginLabel}>
              X Offset: <Text style={styles.marginValue}>
                {getActiveMargin().x.toFixed(1)}</Text>
            </Text>
            <Slider style={styles.slider}
              minimumValue={-30} maximumValue={30} step={0.5}
              value={getActiveMargin().x}
              onValueChange={v => handleMarginChange('X', v)}
              minimumTrackTintColor="#00E5FF" maximumTrackTintColor="#333"
              thumbTintColor="#00E5FF" />
          </View>
          <View style={styles.marginRow}>
            <Text style={styles.marginLabel}>
              Y Offset: <Text style={styles.marginValue}>
                {getActiveMargin().y.toFixed(1)}</Text>
            </Text>
            <Slider style={styles.slider}
              minimumValue={-30} maximumValue={30} step={0.5}
              value={getActiveMargin().y}
              onValueChange={v => handleMarginChange('Y', v)}
              minimumTrackTintColor="#00E5FF" maximumTrackTintColor="#333"
              thumbTintColor="#00E5FF" />
          </View>
          <TouchableOpacity style={styles.resetBtn}
            onPress={() => {
              const r: MarginSettings = {d2X:0,d2Y:0,e2X:0,e2Y:0,insideX:0,insideY:0};
              setMargin(r);
              try { OverlayModule.setMarginOffset(0, 0); } catch {}
            }}>
            <Text style={styles.resetBtnText}>Reset Margins</Text>
          </TouchableOpacity>
        </View>

        {/* How to use */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>How to Use</Text>
          <Text style={styles.howToStep}>1. Grant "Draw over apps" permission</Text>
          <Text style={styles.howToStep}>2. Turn on Aim Overlay</Text>
          <Text style={styles.howToStep}>3. Turn on Auto-Detect (grant screen capture once per session)</Text>
          <Text style={styles.howToStep}>4. Open Carrom Disc Pool</Text>
          <Text style={styles.howToStep}>5. Tap floating icon → Turn ON lines</Text>
          <Text style={styles.howToStep}>6. Lines appear automatically — up to 7 shots shown</Text>
          <View style={styles.autoPlayGuide}>
            <Text style={styles.autoPlayGuideTitle}>AutoPlay Setup (one-time):</Text>
            <Text style={styles.howToStep}>A. Tap the AutoPlay toggle above</Text>
            <Text style={styles.howToStep}>B. Tap "Open Settings" → find AIMxASSIST</Text>
            <Text style={styles.howToStep}>C. Enable "AIMxASSIST Autoplay"</Text>
            <Text style={styles.howToStep}>D. Return here, enable AutoPlay toggle</Text>
            <Text style={styles.howToStep}>E. App waits for board to stabilise then shoots!</Text>
          </View>
          <Text style={styles.howToTip}>
            Colors: Gold = direct shot, Blue = 1-cushion bounce, Magenta = 2-cushion.
            AutoPlay uses 72% power — adjust by changing Detection Sensitivity if shots are too weak/strong.
          </Text>
        </View>

        <View style={styles.footer}>
          <Text style={styles.footerText}>AIMxASSIST v5.0 • created by abraham / Xhay</Text>
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
  root: {flex: 1, backgroundColor: '#0D0D1A'},
  header: {
    paddingTop: Platform.OS === 'android' ? StatusBar.currentHeight ?? 24 : 44,
    paddingBottom: 16, paddingHorizontal: 20,
    backgroundColor: '#13132A', borderBottomWidth: 1, borderBottomColor: '#222244',
  },
  logo: {color: '#FFD700', fontSize: 26, fontWeight: '900', letterSpacing: 1},
  subtitle: {color: '#8888BB', fontSize: 12, marginTop: 2},
  scroll: {flex: 1},
  scrollContent: {padding: 16, paddingBottom: 40},
  permBanner: {
    backgroundColor: '#2A1A00', borderWidth: 1, borderColor: '#FFD700',
    borderRadius: 10, padding: 14, marginBottom: 12,
  },
  permBannerText: {color: '#FFC', fontSize: 13},
  permBannerCta: {color: '#FFD700', fontSize: 13, fontWeight: '700', marginTop: 4},
  card: {
    backgroundColor: '#16162E', borderRadius: 14, padding: 16,
    marginBottom: 14, borderWidth: 1, borderColor: '#222244',
  },
  cardTitle: {color: '#FFFFFF', fontSize: 16, fontWeight: '700', marginBottom: 4},
  cardSub: {color: '#8888BB', fontSize: 12, marginBottom: 8},
  row: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between'},
  rowSpread: {flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center'},
  shotGrid: {flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8},
  shotBtn: {
    width: '47%', backgroundColor: '#1E1E3A', borderRadius: 10, padding: 12,
    borderWidth: 1.5, borderColor: '#333355', alignItems: 'flex-start',
  },
  shotBtnActive: {borderColor: '#FFD700', backgroundColor: '#26260A'},
  shotLabel: {color: '#AAA', fontSize: 14, fontWeight: '700'},
  shotLabelActive: {color: '#FFD700'},
  shotDesc: {color: '#666688', fontSize: 10, marginTop: 3},
  legend: {flexDirection: 'row', flexWrap: 'wrap', gap: 10, marginTop: 12},
  legendItem: {flexDirection: 'row', alignItems: 'center'},
  legendSwatch: {width: 12, height: 4, borderRadius: 2, marginRight: 6},
  legendLabel: {color: '#AAA', fontSize: 11},
  slider: {width: '100%', height: 36},
  sliderEndLabel: {color: '#666688', fontSize: 11},
  valueLabel: {color: '#FFD700', fontSize: 16, fontWeight: '700'},
  tabRow: {flexDirection: 'row', gap: 8, marginVertical: 10},
  tab: {
    flex: 1, paddingVertical: 8, borderRadius: 8,
    backgroundColor: '#1E1E3A', alignItems: 'center',
    borderWidth: 1, borderColor: '#333355',
  },
  tabActive: {backgroundColor: '#00293A', borderColor: '#00E5FF'},
  tabText: {color: '#8888BB', fontSize: 13, fontWeight: '600'},
  tabTextActive: {color: '#00E5FF'},
  marginRow: {marginBottom: 8},
  marginLabel: {color: '#AAA', fontSize: 13, marginBottom: 2},
  marginValue: {color: '#00E5FF', fontWeight: '700'},
  resetBtn: {
    marginTop: 6, paddingVertical: 8, borderRadius: 8,
    backgroundColor: '#1E1E3A', alignItems: 'center',
    borderWidth: 1, borderColor: '#444466',
  },
  resetBtnText: {color: '#FF7777', fontSize: 13, fontWeight: '600'},
  howToStep: {color: '#CCCCEE', fontSize: 13, marginBottom: 5, paddingLeft: 4},
  howToTip: {
    color: '#FFD700', fontSize: 12, marginTop: 8,
    backgroundColor: '#22220A', padding: 10, borderRadius: 8,
  },
  accessibilityBadge: {
    marginTop: 12, padding: 10, borderRadius: 8, borderWidth: 1,
  },
  autoPlayGuide: {
    marginTop: 10, padding: 10,
    backgroundColor: '#0D1A2A', borderRadius: 8,
    borderWidth: 1, borderColor: '#6B99FF',
  },
  autoPlayGuideTitle: {
    color: '#6B99FF', fontSize: 13, fontWeight: '700', marginBottom: 6,
  },
  footer: {alignItems: 'center', marginTop: 10},
  footerText: {color: '#444466', fontSize: 11},
});
