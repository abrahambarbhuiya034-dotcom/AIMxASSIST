import { useState, useEffect } from "react";

export default function App() {
  const [overlayActive, setOverlayActive] = useState(false);
  const [autoDetect, setAutoDetect] = useState(false);
  const [autoPlay, setAutoPlay] = useState(false);
  const [accessibilityReady, setAccessibilityReady] = useState(false);
  const [sensitivity, setSensitivity] = useState(1.0);
  const [detectThreshold, setDetectThreshold] = useState(36);
  const [autoPlayDelay, setAutoPlayDelay] = useState(2.0);
  const [shootFlash, setShootFlash] = useState(false);

  function toggleOverlay() { setOverlayActive(v => !v); if (overlayActive) { setAutoDetect(false); setAutoPlay(false); } }
  function toggleAutoDetect() { if (!overlayActive) return; setAutoDetect(v => !v); }
  function toggleAutoPlay() { if (!overlayActive || !autoDetect || !accessibilityReady) return; setAutoPlay(v => !v); }
  function shootNow() {
    if (!overlayActive || !autoDetect || !accessibilityReady) return;
    setShootFlash(true);
    setTimeout(() => setShootFlash(false), 400);
  }

  return (
    <div style={{ minHeight: "100vh", background: "#0D0D1A", fontFamily: "system-ui, sans-serif", color: "#fff" }}>
      {/* Header */}
      <div style={{ background: "#13132A", borderBottom: "1px solid #222244", padding: "20px 20px 16px" }}>
        <div style={{ fontSize: 26, fontWeight: 900, color: "#FFD700", letterSpacing: 1 }}>AIMxASSIST</div>
        <div style={{ fontSize: 12, color: "#8888BB", marginTop: 2 }}>Auto-Detect Carrom Aim Assist • v8.4</div>
      </div>

      {/* Scrollable content */}
      <div style={{ padding: "16px", maxWidth: 480, margin: "0 auto", paddingBottom: 40 }}>

        {/* Overlay + Auto-Detect card */}
        <Card>
          <Row label="Aim Overlay"
            sub={overlayActive ? "Running — aim lines visible on top of Carrom Pool" : "Start to draw aim lines over any carrom game"}
            checked={overlayActive} color="#FFD700" onChange={toggleOverlay} />
          <div style={{ marginTop: 14 }}>
            <Row label="Auto-Detect (CV)"
              sub={autoDetect ? "Reading screen — striker, coins & pockets detected automatically" : "Pure-Java detector: reads screen pixels in real-time (one-time permission)"}
              checked={autoDetect} color="#00E5FF" onChange={toggleAutoDetect} disabled={!overlayActive} />
          </div>
        </Card>

        {/* AutoPlay card */}
        <Card>
          <div style={{ fontSize: 16, fontWeight: 700, marginBottom: 4 }}>AutoPlay</div>
          <div style={{ fontSize: 12, color: "#8888BB", marginBottom: 12 }}>
            Automatically swipes the striker. Fires ONLY when the carrom board is stable.
            Requires Overlay + Auto-Detect + Accessibility.
          </div>

          {!accessibilityReady && (
            <Banner color="#FFD700" bg="#2A1A00"
              text='"AIMxASSIST" not enabled in Accessibility Settings'
              cta="Tap to open Settings →"
              onClick={() => setAccessibilityReady(true)} />
          )}

          <div style={{ marginTop: 10 }}>
            <Row label="Auto Shoot"
              sub={autoPlay ? "ACTIVE — switch to carrom game, fires on stable board" : "Off — tap to start automatic shooting"}
              checked={autoPlay} color="#22C55E" onChange={toggleAutoPlay}
              disabled={!overlayActive || !autoDetect || !accessibilityReady} />
          </div>

          <SliderRow
            label="Min Delay Between Shots"
            value={autoPlayDelay} min={0.5} max={5.0} step={0.1}
            display={autoPlayDelay.toFixed(1) + " s"}
            color="#22C55E"
            leftLabel="Fast (0.5 s)" rightLabel="Slow (5 s)"
            onChange={setAutoPlayDelay} />

          <button
            onClick={shootNow}
            disabled={!overlayActive || !autoDetect || !accessibilityReady}
            style={{
              marginTop: 12, width: "100%", padding: "12px 0",
              borderRadius: 10, background: shootFlash ? "#0F3D1A" : "#0A2A10",
              border: "1.5px solid #22C55E", color: "#22C55E",
              fontSize: 15, fontWeight: 700, cursor: "pointer",
              opacity: (!overlayActive || !autoDetect || !accessibilityReady) ? 0.35 : 1,
              transition: "background 0.15s",
            }}>
            ▶  Shoot Best Shot Now
          </button>
        </Card>

        {/* Shot Power */}
        <Card>
          <SliderRow label="Shot Power"
            value={sensitivity} min={0.3} max={3.0} step={0.1}
            display={sensitivity.toFixed(1) + "x"}
            color="#FFD700"
            leftLabel="Soft" rightLabel="Hard"
            onChange={setSensitivity} />
        </Card>

        {/* Detection Sensitivity */}
        <Card>
          <SliderRow
            label="Detection Sensitivity"
            value={detectThreshold} min={12} max={50} step={1}
            display={String(detectThreshold)}
            color="#00E5FF"
            leftLabel="More circles" rightLabel="Fewer circles"
            onChange={setDetectThreshold}
            sub="Lower = detects more circles (more false positives). Raise to 35–45 to reduce ghost circles." />
        </Card>

        {/* How To Use */}
        <Card>
          <div style={{ fontSize: 16, fontWeight: 700, marginBottom: 12 }}>How to Use</div>
          {[
            '1. Grant "Draw over apps" permission',
            '2. Turn on the Aim Overlay',
            '3. Enable Auto-Detect (one-time screen capture permission)',
            '4. Open Carrom Pool — lines appear automatically',
            '5. Gold line = best shot. Cyan = 2nd best, etc.',
            '6. For AutoPlay: enable "AIMxASSIST" in Android Accessibility Settings',
            '7. Flip Auto Shoot ON, then switch to your carrom game',
          ].map((step, i) => (
            <div key={i} style={{ fontSize: 13, color: "#CCCCEE", marginBottom: 6, paddingLeft: 4 }}>{step}</div>
          ))}
          <div style={{ color: "#FFD700", fontSize: 12, marginTop: 8, background: "#22220A", padding: 10, borderRadius: 8 }}>
            AutoPlay waits for a stable board before firing — it will never activate while you are on this app screen.
          </div>
        </Card>

        {/* Board Visualization */}
        <Card>
          <div style={{ fontSize: 16, fontWeight: 700, marginBottom: 12 }}>Live Aim Lines Preview</div>
          <BoardCanvas active={overlayActive && autoDetect} />
        </Card>

        <div style={{ textAlign: "center", marginTop: 10, color: "#444466", fontSize: 11 }}>
          AIMxASSIST v8.4 • Ghost-Ball AI • Stable AutoPlay • Android 6+
        </div>
      </div>
    </div>
  );
}

function Card({ children }: { children: React.ReactNode }) {
  return (
    <div style={{
      background: "#16162E", borderRadius: 14, padding: 16,
      marginBottom: 14, border: "1px solid #222244"
    }}>{children}</div>
  );
}

function Row({ label, sub, checked, color, onChange, disabled = false }: {
  label: string; sub: string; checked: boolean; color: string;
  onChange: () => void; disabled?: boolean;
}) {
  return (
    <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
      <div style={{ flex: 1, paddingRight: 8 }}>
        <div style={{ fontSize: 16, fontWeight: 700, color: "#fff", marginBottom: 4 }}>{label}</div>
        <div style={{ fontSize: 12, color: "#8888BB" }}>{sub}</div>
      </div>
      <div
        onClick={() => !disabled && onChange()}
        style={{
          width: 50, height: 28, borderRadius: 14, position: "relative", cursor: disabled ? "not-allowed" : "pointer",
          background: checked ? color : "#333", opacity: disabled ? 0.4 : 1, transition: "background 0.2s",
          flexShrink: 0,
        }}>
        <div style={{
          position: "absolute", top: 3, left: checked ? 24 : 3,
          width: 22, height: 22, borderRadius: 11,
          background: checked ? "#fff" : "#888", transition: "left 0.2s",
        }} />
      </div>
    </div>
  );
}

function SliderRow({ label, value, min, max, step, display, color, leftLabel, rightLabel, onChange, sub }: {
  label: string; value: number; min: number; max: number; step: number;
  display: string; color: string; leftLabel?: string; rightLabel?: string;
  onChange: (v: number) => void; sub?: string;
}) {
  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 4 }}>
        <div style={{ fontSize: 16, fontWeight: 700 }}>{label}</div>
        <div style={{ fontSize: 16, fontWeight: 700, color }}>{display}</div>
      </div>
      {sub && <div style={{ fontSize: 12, color: "#8888BB", marginBottom: 6 }}>{sub}</div>}
      <input type="range" min={min} max={max} step={step} value={value}
        onChange={e => onChange(Number(e.target.value))}
        style={{ width: "100%", accentColor: color }} />
      {(leftLabel || rightLabel) && (
        <div style={{ display: "flex", justifyContent: "space-between", marginTop: 2 }}>
          <span style={{ fontSize: 11, color: "#666688" }}>{leftLabel}</span>
          <span style={{ fontSize: 11, color: "#666688" }}>{rightLabel}</span>
        </div>
      )}
    </div>
  );
}

function Banner({ color, bg, text, cta, onClick }: { color: string; bg: string; text: string; cta: string; onClick?: () => void }) {
  return (
    <div onClick={onClick} style={{
      background: bg, border: `1px solid ${color}`, borderRadius: 10, padding: 14, marginBottom: 12, cursor: "pointer"
    }}>
      <div style={{ color: "#FFC", fontSize: 13 }}>{text}</div>
      <div style={{ color, fontSize: 13, fontWeight: 700, marginTop: 4 }}>{cta}</div>
    </div>
  );
}

function BoardCanvas({ active }: { active: boolean }) {
  useEffect(() => {
    const canvas = document.getElementById("board-canvas") as HTMLCanvasElement;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    const W = canvas.width, H = canvas.height;
    ctx.clearRect(0, 0, W, H);

    const cx = W / 2, cy = H / 2;
    const side = Math.min(W, H) * 0.88;
    const hl = cx - side / 2, ht = cy - side / 2, hr = cx + side / 2, hb = cy + side / 2;

    // Board background
    ctx.fillStyle = "#1A0E00";
    ctx.fillRect(hl, ht, side, side);

    // Board border
    ctx.strokeStyle = active ? "rgba(255,215,0,0.6)" : "rgba(255,215,0,0.2)";
    ctx.lineWidth = 2;
    ctx.setLineDash([6, 6]);
    ctx.strokeRect(hl, ht, side, side);
    ctx.setLineDash([]);

    const inset = side * 0.06;
    const pockets = [
      [hl + inset, ht + inset], [hr - inset, ht + inset],
      [hl + inset, hb - inset], [hr - inset, hb - inset],
    ];

    // Pockets
    ctx.fillStyle = "rgba(46,204,113,0.6)";
    for (const [px, py] of pockets) {
      ctx.beginPath();
      ctx.arc(px, py, side * 0.032, 0, Math.PI * 2);
      ctx.fill();
    }

    const r = side * 0.022;
    const coinLayout = [
      [cx, cy, "red"],
      [cx - side*.08, cy - side*.10, "white"], [cx + side*.08, cy - side*.10, "white"],
      [cx - side*.16, cy - side*.05, "white"], [cx + side*.16, cy - side*.05, "white"],
      [cx, cy - side*.17, "white"],
      [cx + side*.16, cy + side*.05, "black"], [cx - side*.24, cy - side*.12, "black"],
      [cx + side*.24, cy - side*.12, "black"], [cx - side*.24, cy + side*.12, "black"],
      [cx + side*.24, cy + side*.12, "black"],
    ];

    for (const [x, y, col] of coinLayout) {
      ctx.beginPath();
      ctx.arc(x as number, y as number, r, 0, Math.PI * 2);
      ctx.fillStyle = col === "red" ? "rgba(255,51,68,0.85)" : col === "black" ? "rgba(16,16,16,0.85)" : "rgba(238,238,238,0.85)";
      ctx.fill();
      ctx.strokeStyle = "rgba(255,255,255,0.3)";
      ctx.lineWidth = 0.8;
      ctx.stroke();
    }

    // Striker
    const sx = cx, sy = ht + side * 0.80;
    const sr = r * 1.28;
    ctx.beginPath(); ctx.arc(sx, sy, sr, 0, Math.PI * 2);
    ctx.fillStyle = "rgba(238,238,238,0.85)"; ctx.fill();
    ctx.strokeStyle = "#FFD700"; ctx.lineWidth = 2; ctx.stroke();

    if (active) {
      // Draw aim lines toward nearest coin
      const targetCoin = coinLayout[1];
      const tx = targetCoin[0] as number, ty = targetCoin[1] as number;
      const dx = tx - sx, dy = ty - sy;
      const len = Math.sqrt(dx * dx + dy * dy);
      const nx = dx / len, ny = dy / len;
      const ghostX = tx + nx * (sr + r), ghostY = ty + ny * (sr + r);

      // Glow
      ctx.beginPath(); ctx.moveTo(sx, sy); ctx.lineTo(ghostX, ghostY);
      ctx.strokeStyle = "rgba(255,255,255,0.15)"; ctx.lineWidth = 16;
      ctx.lineCap = "round"; ctx.stroke();
      // Core
      ctx.beginPath(); ctx.moveTo(sx, sy); ctx.lineTo(ghostX, ghostY);
      ctx.strokeStyle = "rgba(255,255,255,1)"; ctx.lineWidth = 4; ctx.stroke();

      // Pocket line
      const pk = pockets[0];
      ctx.beginPath(); ctx.moveTo(tx, ty); ctx.lineTo(pk[0], pk[1]);
      ctx.strokeStyle = "rgba(26,26,46,0.9)"; ctx.lineWidth = 3; ctx.stroke();

      // Ghost dot
      ctx.beginPath(); ctx.arc(ghostX, ghostY, sr * 0.6, 0, Math.PI * 2);
      ctx.strokeStyle = "rgba(255,255,255,0.8)"; ctx.lineWidth = 1.5; ctx.stroke();
    }
  }, [active]);

  return (
    <div style={{ textAlign: "center" }}>
      <canvas id="board-canvas" width={280} height={280}
        style={{ borderRadius: 8, background: "#0D0D1A", maxWidth: "100%" }} />
      <div style={{ fontSize: 11, color: "#555577", marginTop: 8 }}>
        {active ? "Live detection active — aim lines rendered by Java CV engine on device" : "Enable Overlay + Auto-Detect to see live aim lines"}
      </div>
    </div>
  );
}
