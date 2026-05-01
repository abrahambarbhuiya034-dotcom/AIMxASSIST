/**
 * AIMxASSIST - Carrom Pool Aim Assistant
 * Main React Native UI — v3.0
 *
 * - Fully automatic aim lines, no touch needed
 * - Board auto-detected via wood-colour CV (no manual margin needed)
 * - Max 5 prediction lines, EMA-smoothed for stability
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

// ─── Types ───────────────────────────────────────────────────────────────────
type ShotMode = 'ALL' | 'DIRECT' | 'AI' | 'GOLDEN' | 'LUCKY';

interface MarginSettings {
  d2X: number; d2Y: number;
  e2X: number; e2Y: number;
  insideX: number; insideY: number;
}

const SHOT_MODES: {mode: ShotMode; label: string; desc: string}[] = [
  {mode: 'ALL',    label: 'All Lines', desc: 'Show every prediction simultaneously'},
  {mode: 'DIRECT', label: 'Direct',    desc: 'Striker straight line only'},
  {mode: 'AI',     label: 'AI Aim',    desc: 'Striker + coin chain reactions'},
  {mode: 'GOLDEN', label: 'Golden',    desc: 'Up to one cushion bounce'},
  {mode: 'LUCKY',  label: 'Lucky',     desc: 'Up to two cushion bounces'},
];

export default function App() {
  const [hasOverlay, setHasOverlay]   = useState(false);
  const [overlayActive, setOverlayActive] = useState(false);
  const [autoDetect, setAutoDetect]   = useState(false);
  const [selectedMode, setSelectedMode] = useState<ShotMode>('ALL');
  const [sensitivity, setSensitivity] = useState(1.0);
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
        // System dialog appears; status updates on next poll
        setTimeout(refreshStatus, 2500);
      }
    } catch (e: any) {
      Alert.alert('Error', e.message || 'Could not toggle screen capture');
    }
  }, [overlayActive, autoDetect, refreshStatus]);

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
          Auto-Detect Carrom Aim Assist • v3.0
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

        {/* Overlay + Auto-Detect controls */}
        <View style={styles.card}>
          <View style={styles.row}>
            <View style={{flex: 1, paddingRight: 8}}>
              <Text style={styles.cardTitle}>Aim Overlay</Text>
              <Text style={styles.cardSub}>
                {overlayActive
                  ? 'Running — tap floating icon in the game to show lines'
                  : 'Start to draw aim lines on top of Carrom Pool'}
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
                  : 'Use computer vision to auto-place striker/coins (per-session permission)'}
              </Text>
            </View>
            <Switch value={autoDetect} onValueChange={toggleAutoDetect}
              trackColor={{false: '#333', true: '#00E5FF'}}
              thumbColor={autoDetect ? '#FFF' : '#888'} />
          </View>
        </View>

        {/* Shot mode */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Prediction Lines</Text>
          <Text style={styles.cardSub}>
            "All Lines" shows every shot type at once in different colors
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
            <LegendDot color="#FFD700" label="Aim" />
            <LegendDot color="#FF8A00" label="Coin" />
            <LegendDot color="#FF3D71" label="Queen" />
            <LegendDot color="#00E5FF" label="1-bounce" />
            <LegendDot color="#D946EF" label="2-bounce" />
            <LegendDot color="#22C55E" label="Pocket" />
          </View>
        </View>

        {/* Sensitivity */}
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

        {/* Detection threshold */}
        <View style={styles.card}>
          <View style={styles.rowSpread}>
            <Text style={styles.cardTitle}>Detection Sensitivity</Text>
            <Text style={[styles.valueLabel, {color: '#00E5FF'}]}>
              {detectThreshold}
            </Text>
          </View>
          <Text style={styles.cardSub}>
            Lower = detects more circles (more false positives).
            Raise to 35–45 if you see too many ghost circles.
            Higher = fewer, only the clearest circles.
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
            Nudge the touch point if your screen reports a small offset
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
          <Text style={styles.howToStep}>2. Turn on the Aim Overlay</Text>
          <Text style={styles.howToStep}>3. Turn on Auto-Detect (grant screen capture — asked once per session)</Text>
          <Text style={styles.howToStep}>4. Open Carrom Pool</Text>
          <Text style={styles.howToStep}>5. Tap the floating icon → tap "Turn ON"</Text>
          <Text style={styles.howToStep}>6. Aim lines appear automatically — no touch needed</Text>
          <Text style={styles.howToStep}>7. Up to 5 best shots are shown, updating every ~30 FPS</Text>
          <Text style={styles.howToTip}>
            Tip: the board is auto-detected by wood colour — if lines appear
            outside the board, lower "Detection Sensitivity" slightly.
            Watermark "created by abraham / Xhay" shows at board centre.
          </Text>
        </View>

        <View style={styles.footer}>
          <Text style={styles.footerText}>AIMxASSIST v3.0 • Auto-Detect • Android 6+</Text>
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
  footer: {alignItems: 'center', marginTop: 10},
  footerText: {color: '#444466', fontSize: 11},
});
