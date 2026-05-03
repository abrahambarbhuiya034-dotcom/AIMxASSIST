/**
 * AppNative.tsx — React Native shell for AIMxASSIST v8.4
 * Bridges OverlayModule (Java) to the React Native UI.
 * On web (Vite preview) we export the web App component instead.
 */
import React, { useState, useEffect, useCallback } from 'react';
import {
  View, Text, StyleSheet, Switch, TouchableOpacity,
  ScrollView, Platform, Linking, NativeModules, Alert,
  StatusBar, Dimensions,
} from 'react-native';

const { OverlayModule } = NativeModules as {
  OverlayModule: {
    canDrawOverlays: () => Promise<boolean>;
    requestOverlayPermission: () => void;
    startOverlay: () => Promise<boolean>;
    stopOverlay: () => Promise<boolean>;
    requestScreenCapture: () => Promise<boolean>;
    stopScreenCapture: () => Promise<boolean>;
    isAutoDetectActive: () => Promise<boolean>;
    setSensitivity: (v: number) => void;
    setDetectionThreshold: (v: number) => void;
    setAutoPlay: (enabled: boolean) => Promise<boolean>;
    isAutoPlayEnabled: () => Promise<boolean>;
    setAutoPlayDelay: (ms: number) => void;
    shootNow: () => Promise<boolean>;
    isAccessibilityReady: () => Promise<boolean>;
    requestAccessibilityPermission: () => void;
    setShotMode: (mode: string) => void;
  };
};

const isAndroid = Platform.OS === 'android';
const GOLD = '#FFD700';
const CYAN = '#00E5FF';
const GREEN = '#22C55E';
const BG = '#0D0D1A';
const CARD = '#16162E';
const BORDER = '#222244';

export default function AppNative() {
  const [overlayActive, setOverlayActive] = useState(false);
  const [autoDetect, setAutoDetect] = useState(false);
  const [autoPlay, setAutoPlay] = useState(false);
  const [accessibilityReady, setAccessibilityReady] = useState(false);
  const [sensitivity, setSensitivity] = useState(1.0);
  const [detectThreshold, setDetectThreshold] = useState(36);
  const [autoPlayDelay, setAutoPlayDelay] = useState(1800);
  const [canOverlay, setCanOverlay] = useState(false);

  const poll = useCallback(async () => {
    if (!isAndroid || !OverlayModule) return;
    try {
      const [overlay, acc, ap] = await Promise.all([
        OverlayModule.canDrawOverlays(),
        OverlayModule.isAccessibilityReady(),
        OverlayModule.isAutoPlayEnabled(),
      ]);
      setCanOverlay(overlay);
      setAccessibilityReady(acc);
      setAutoPlay(ap);
    } catch (_) {}
  }, []);

  useEffect(() => {
    poll();
    const t = setInterval(poll, 3000);
    return () => clearInterval(t);
  }, [poll]);

  async function toggleOverlay() {
    if (!isAndroid || !OverlayModule) return;
    if (!canOverlay) { OverlayModule.requestOverlayPermission(); return; }
    if (overlayActive) {
      await OverlayModule.stopOverlay();
      await OverlayModule.stopScreenCapture();
      setOverlayActive(false); setAutoDetect(false); setAutoPlay(false);
    } else {
      await OverlayModule.startOverlay();
      setOverlayActive(true);
    }
  }

  async function toggleAutoDetect() {
    if (!overlayActive) return;
    if (autoDetect) {
      await OverlayModule.stopScreenCapture();
      setAutoDetect(false); setAutoPlay(false);
    } else {
      await OverlayModule.requestScreenCapture();
      setAutoDetect(true);
    }
  }

  async function toggleAutoPlay() {
    if (!overlayActive || !autoDetect) return;
    if (!accessibilityReady) {
      Alert.alert('Accessibility Required',
        'Enable "AIMxASSIST" in Settings → Accessibility → Installed Services, then return here.',
        [{ text: 'Open Settings', onPress: () => OverlayModule.requestAccessibilityPermission() },
         { text: 'Cancel' }]);
      return;
    }
    try {
      const next = !autoPlay;
      await OverlayModule.setAutoPlay(next);
      setAutoPlay(next);
    } catch (e: any) { Alert.alert('AutoPlay Error', e.message); }
  }

  async function shootNow() {
    if (!overlayActive || !autoDetect) return;
    try { await OverlayModule.shootNow(); }
    catch (e: any) { Alert.alert('Shoot Error', e.message); }
  }

  function onSensitivity(v: number) {
    setSensitivity(v);
    if (isAndroid && OverlayModule) OverlayModule.setSensitivity(v);
  }
  function onThreshold(v: number) {
    setDetectThreshold(v);
    if (isAndroid && OverlayModule) OverlayModule.setDetectionThreshold(v);
  }
  function onDelay(v: number) {
    setAutoPlayDelay(v);
    if (isAndroid && OverlayModule) OverlayModule.setAutoPlayDelay(v);
  }

  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor={BG} />
      <View style={styles.header}>
        <Text style={styles.title}>AIMxASSIST</Text>
        <Text style={styles.subtitle}>Auto-Detect Carrom Aim Assist • v8.4</Text>
      </View>

      <ScrollView contentContainerStyle={styles.scroll}>
        <Card>
          <Row label="Aim Overlay"
            sub={overlayActive ? 'Running — aim lines visible over game' : 'Start to draw aim lines over Carrom Pool'}
            value={overlayActive} tint={GOLD} onToggle={toggleOverlay} />
          <Spacer />
          <Row label="Auto-Detect (CV)"
            sub={autoDetect ? 'Reading screen — coins & pocket detected' : 'Pure-Java detector (one-time screen permission)'}
            value={autoDetect} tint={CYAN} onToggle={toggleAutoDetect} disabled={!overlayActive} />
        </Card>

        <Card>
          <Text style={styles.cardTitle}>AutoPlay</Text>
          <Text style={styles.cardSub}>Swipes striker automatically. Fires when board is stable.</Text>

          {!accessibilityReady && (
            <TouchableOpacity style={styles.banner}
              onPress={() => isAndroid && OverlayModule?.requestAccessibilityPermission()}>
              <Text style={styles.bannerText}>⚠ "AIMxASSIST" not enabled in Accessibility</Text>
              <Text style={[styles.bannerCta, { color: GOLD }]}>Tap to open Accessibility Settings →</Text>
            </TouchableOpacity>
          )}

          <Row label="Auto Shoot"
            sub={autoPlay ? 'ACTIVE — switch to your Carrom game now' : 'Off — tap to start auto-shooting'}
            value={autoPlay} tint={GREEN} onToggle={toggleAutoPlay}
            disabled={!overlayActive || !autoDetect || !accessibilityReady} />

          <SliderRow label="Min Delay" value={autoPlayDelay}
            min={500} max={5000} step={100} color={GREEN}
            display={(autoPlayDelay/1000).toFixed(1) + ' s'}
            leftLabel="Fast 0.5 s" rightLabel="Slow 5 s"
            onChange={onDelay} />

          <TouchableOpacity
            style={[styles.shootBtn, (!overlayActive||!autoDetect||!accessibilityReady)&&styles.disabled]}
            onPress={shootNow}
            disabled={!overlayActive||!autoDetect||!accessibilityReady}>
            <Text style={styles.shootBtnText}>▶  Shoot Best Shot Now</Text>
          </TouchableOpacity>
        </Card>

        <Card>
          <SliderRow label="Shot Power" value={sensitivity}
            min={0.3} max={3.0} step={0.1} color={GOLD}
            display={sensitivity.toFixed(1) + 'x'}
            leftLabel="Soft" rightLabel="Hard"
            onChange={onSensitivity} />
        </Card>

        <Card>
          <SliderRow label="Detection Sensitivity" value={detectThreshold}
            min={12} max={50} step={1} color={CYAN}
            display={String(detectThreshold)}
            leftLabel="More" rightLabel="Fewer"
            onChange={onThreshold}
            sub="Lower = more circles detected (may add false positives). Raise to 35–45 to filter ghost circles." />
        </Card>

        <Card>
          <Text style={styles.cardTitle}>How to Use</Text>
          {[
            '1. Grant "Draw over apps" permission',
            '2. Turn on the Aim Overlay',
            '3. Enable Auto-Detect (grants screen capture)',
            '4. Open Carrom Pool — lines appear over coins',
            '5. Gold line = best shot. Cyan = 2nd best, etc.',
            '6. For AutoPlay: enable "AIMxASSIST" in Accessibility',
            '7. Flip Auto Shoot ON, then switch to Carrom Pool',
          ].map((s, i) => <Text key={i} style={styles.step}>{s}</Text>)}
          <View style={styles.tip}>
            <Text style={{ color: GOLD, fontSize: 12 }}>
              AutoPlay waits for a stable board before firing. It will never activate while you are viewing this app.
            </Text>
          </View>
        </Card>

        <Text style={styles.footer}>
          AIMxASSIST v8.4 • Ghost-Ball AI • Stable AutoPlay • Android 6+{'\n'}
          Pure-Java detection — no OpenCV required
        </Text>
      </ScrollView>
    </View>
  );
}

function Card({ children }: { children: React.ReactNode }) {
  return <View style={styles.card}>{children}</View>;
}

function Spacer() { return <View style={{ height: 14 }} />; }

function Row({ label, sub, value, tint, onToggle, disabled = false }: {
  label: string; sub: string; value: boolean; tint: string;
  onToggle: () => void; disabled?: boolean;
}) {
  return (
    <View style={styles.row}>
      <View style={styles.rowText}>
        <Text style={styles.rowLabel}>{label}</Text>
        <Text style={styles.rowSub}>{sub}</Text>
      </View>
      <Switch value={value} onValueChange={disabled ? undefined : onToggle}
        thumbColor={value ? '#fff' : '#888'}
        trackColor={{ false: '#333', true: tint }}
        disabled={disabled} style={{ opacity: disabled ? 0.4 : 1 }} />
    </View>
  );
}

function SliderRow({ label, value, min, max, step, display, color, leftLabel, rightLabel, onChange, sub }: {
  label: string; value: number; min: number; max: number; step: number;
  display: string; color: string; leftLabel?: string; rightLabel?: string;
  onChange: (v: number) => void; sub?: string;
}) {
  return (
    <View style={{ marginTop: 6 }}>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginBottom: 4 }}>
        <Text style={styles.rowLabel}>{label}</Text>
        <Text style={[styles.rowLabel, { color }]}>{display}</Text>
      </View>
      {sub && <Text style={[styles.rowSub, { marginBottom: 6 }]}>{sub}</Text>}
      {/* Native Slider would be added via @react-native-community/slider in real build */}
      <View style={{ height: 6, backgroundColor: '#2a2a4a', borderRadius: 3, marginBottom: 4 }}>
        <View style={{ height: 6, width: `${Math.round((value-min)/(max-min)*100)}%`, backgroundColor: color, borderRadius: 3 }} />
      </View>
      {(leftLabel || rightLabel) && (
        <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
          <Text style={{ fontSize: 11, color: '#666688' }}>{leftLabel}</Text>
          <Text style={{ fontSize: 11, color: '#666688' }}>{rightLabel}</Text>
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: BG },
  header: { backgroundColor: '#13132A', borderBottomWidth: 1, borderBottomColor: BORDER, padding: 20, paddingTop: 48 },
  title: { fontSize: 26, fontWeight: '900', color: GOLD, letterSpacing: 1 },
  subtitle: { fontSize: 12, color: '#8888BB', marginTop: 2 },
  scroll: { padding: 16, paddingBottom: 40 },
  card: { backgroundColor: CARD, borderRadius: 14, padding: 16, marginBottom: 14, borderWidth: 1, borderColor: BORDER },
  cardTitle: { fontSize: 16, fontWeight: '700', color: '#fff', marginBottom: 4 },
  cardSub: { fontSize: 12, color: '#8888BB', marginBottom: 12 },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  rowText: { flex: 1, paddingRight: 8 },
  rowLabel: { fontSize: 16, fontWeight: '700', color: '#fff', marginBottom: 2 },
  rowSub: { fontSize: 12, color: '#8888BB' },
  banner: { backgroundColor: '#2A1A00', borderWidth: 1, borderColor: GOLD, borderRadius: 10, padding: 14, marginBottom: 12 },
  bannerText: { color: '#FFCC88', fontSize: 13 },
  bannerCta: { fontSize: 13, fontWeight: '700', marginTop: 4 },
  shootBtn: { marginTop: 12, padding: 12, borderRadius: 10, backgroundColor: '#0A2A10', borderWidth: 1.5, borderColor: GREEN, alignItems: 'center' },
  shootBtnText: { color: GREEN, fontSize: 15, fontWeight: '700' },
  disabled: { opacity: 0.35 },
  step: { fontSize: 13, color: '#CCCCEE', marginBottom: 6, paddingLeft: 4 },
  tip: { backgroundColor: '#22220A', padding: 10, borderRadius: 8, marginTop: 8 },
  footer: { textAlign: 'center', marginTop: 10, color: '#444466', fontSize: 11, lineHeight: 18 },
});
