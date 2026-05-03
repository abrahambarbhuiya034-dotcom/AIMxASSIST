import { useState, useEffect, useRef, useCallback } from "react";

const GOLD = "#FFD700";
const CYAN = "#00E5FF";
const GREEN = "#22C55E";
const BG = "#0D0D1A";
const CARD = "#16162E";
const BORDER = "#222244";

export default function App() {
  const [overlayOpen, setOverlayOpen] = useState(false);
  const [power, setPower] = useState(1.5);
  const [lineColor, setLineColor] = useState<"gold" | "cyan" | "white">("gold");
  const [showAngle, setShowAngle] = useState(true);
  const [showReflect, setShowReflect] = useState(true);

  return (
    <div style={{ minHeight: "100vh", background: BG, fontFamily: "system-ui,sans-serif", color: "#fff" }}>
      {/* Header */}
      <div style={{ background: "#13132A", borderBottom: `1px solid ${BORDER}`, padding: "18px 20px 14px", display: "flex", alignItems: "center", justifyContent: "space-between" }}>
        <div>
          <div style={{ fontSize: 24, fontWeight: 900, color: GOLD, letterSpacing: 1 }}>AIMxASSIST</div>
          <div style={{ fontSize: 11, color: "#8888BB", marginTop: 2 }}>Carrom Aim Line Tool • v9.0</div>
        </div>
        <div style={{
          background: overlayOpen ? "#0A2A10" : "#1A1A0A",
          border: `1.5px solid ${overlayOpen ? GREEN : GOLD}`,
          borderRadius: 20, padding: "6px 16px", cursor: "pointer",
          color: overlayOpen ? GREEN : GOLD, fontWeight: 700, fontSize: 13,
        }} onClick={() => setOverlayOpen(v => !v)}>
          {overlayOpen ? "● LIVE" : "START"}
        </div>
      </div>

      <div style={{ padding: "14px 14px 60px", maxWidth: 500, margin: "0 auto" }}>

        {/* Main Aim Canvas */}
        <div style={{ background: CARD, borderRadius: 14, padding: 14, marginBottom: 14, border: `1px solid ${BORDER}` }}>
          <div style={{ fontSize: 15, fontWeight: 700, marginBottom: 10, color: GOLD }}>
            ⊕ Aim Line Overlay
          </div>
          <AimCanvas
            active={overlayOpen}
            power={power}
            lineColor={lineColor}
            showAngle={showAngle}
            showReflect={showReflect}
          />
          <div style={{ fontSize: 11, color: "#555577", marginTop: 8, textAlign: "center" }}>
            {overlayOpen
              ? "Drag the striker ● to aim — lines update in real-time"
              : "Tap START to activate aim lines"}
          </div>
        </div>

        {/* Controls */}
        <div style={{ background: CARD, borderRadius: 14, padding: 14, marginBottom: 14, border: `1px solid ${BORDER}` }}>
          <SliderRow
            label="Shot Power"
            value={power} min={0.5} max={3.0} step={0.1}
            display={power.toFixed(1) + "×"}
            color={GOLD}
            leftLabel="Soft" rightLabel="Hard"
            onChange={setPower}
          />
        </div>

        {/* Line options */}
        <div style={{ background: CARD, borderRadius: 14, padding: 14, marginBottom: 14, border: `1px solid ${BORDER}` }}>
          <div style={{ fontSize: 14, fontWeight: 700, marginBottom: 12 }}>Line Options</div>

          <div style={{ display: "flex", gap: 8, marginBottom: 12 }}>
            {(["gold", "cyan", "white"] as const).map(c => (
              <div key={c} onClick={() => setLineColor(c)} style={{
                flex: 1, textAlign: "center", padding: "8px 0", borderRadius: 10, cursor: "pointer",
                border: `1.5px solid ${lineColor === c ? colorVal(c) : BORDER}`,
                background: lineColor === c ? `${colorVal(c)}22` : "transparent",
                color: lineColor === c ? colorVal(c) : "#888",
                fontWeight: 700, fontSize: 13, textTransform: "capitalize",
              }}>{c}</div>
            ))}
          </div>

          <ToggleRow label="Show Aim Angle" sub="Degrees from vertical" checked={showAngle} color={CYAN} onChange={() => setShowAngle(v => !v)} />
          <div style={{ marginTop: 10 }}>
            <ToggleRow label="Show Bank Shot Line" sub="Reflect line off board wall" checked={showReflect} color={CYAN} onChange={() => setShowReflect(v => !v)} />
          </div>
        </div>

        {/* Floating button preview */}
        <div style={{ background: CARD, borderRadius: 14, padding: 14, marginBottom: 14, border: `1px solid ${BORDER}` }}>
          <div style={{ fontSize: 14, fontWeight: 700, marginBottom: 4 }}>Floating Button</div>
          <div style={{ fontSize: 12, color: "#8888BB", marginBottom: 12 }}>
            Drag the floating button anywhere on screen while the overlay is active.
          </div>
          <FloatButtonPreview active={overlayOpen} color={lineColor} />
        </div>

        {/* How to use */}
        <div style={{ background: CARD, borderRadius: 14, padding: 14, border: `1px solid ${BORDER}` }}>
          <div style={{ fontSize: 14, fontWeight: 700, marginBottom: 10 }}>How to Use</div>
          {[
            "1. Tap START to activate the aim overlay",
            "2. Drag the white striker dot to match your carrom board",
            "3. The gold line shows your shot direction",
            "4. Blue dashed line = bank shot (wall reflection)",
            "5. Angle shown in degrees — match it to your screen",
            "6. Adjust Shot Power for striker force",
            "7. Floating button stays on top while you play",
          ].map((s, i) => (
            <div key={i} style={{ fontSize: 13, color: "#CCCCEE", marginBottom: 6, paddingLeft: 2 }}>{s}</div>
          ))}
        </div>

      </div>

      {/* Floating overlay button */}
      {overlayOpen && <FloatingButton color={lineColor} onClose={() => setOverlayOpen(false)} />}
    </div>
  );
}

function colorVal(c: "gold" | "cyan" | "white") {
  return c === "gold" ? GOLD : c === "cyan" ? CYAN : "#FFFFFF";
}

// ─── Aim Canvas ──────────────────────────────────────────────────────────────
function AimCanvas({ active, power, lineColor, showAngle, showReflect }: {
  active: boolean; power: number;
  lineColor: "gold" | "cyan" | "white";
  showAngle: boolean; showReflect: boolean;
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const dragging = useRef(false);
  const [striker, setStriker] = useState({ x: 0.5, y: 0.78 });
  const [aimAngle, setAimAngle] = useState(-Math.PI / 2);

  const draw = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    const W = canvas.width, H = canvas.height;
    ctx.clearRect(0, 0, W, H);

    const boardPad = 16;
    const bx = boardPad, by = boardPad, bw = W - boardPad * 2, bh = H - boardPad * 2;

    // Board background
    ctx.fillStyle = "#1A0E00";
    ctx.beginPath();
    ctx.roundRect(bx, by, bw, bh, 8);
    ctx.fill();

    // Board border
    ctx.strokeStyle = active ? "rgba(255,215,0,0.5)" : "rgba(255,215,0,0.15)";
    ctx.lineWidth = 2;
    ctx.setLineDash([6, 4]);
    ctx.beginPath(); ctx.roundRect(bx, by, bw, bh, 8); ctx.stroke();
    ctx.setLineDash([]);

    // Pockets
    const pocketR = 10;
    const pockets = [
      { x: bx + pocketR, y: by + pocketR },
      { x: bx + bw - pocketR, y: by + pocketR },
      { x: bx + pocketR, y: by + bh - pocketR },
      { x: bx + bw - pocketR, y: by + bh - pocketR },
    ];
    ctx.fillStyle = "#000";
    for (const p of pockets) {
      ctx.beginPath(); ctx.arc(p.x, p.y, pocketR, 0, Math.PI * 2); ctx.fill();
      ctx.beginPath(); ctx.arc(p.x, p.y, pocketR, 0, Math.PI * 2);
      ctx.strokeStyle = "rgba(255,215,0,0.3)"; ctx.lineWidth = 1.5; ctx.stroke();
    }

    // Center circle
    const cx = bx + bw / 2, cy = by + bh / 2;
    ctx.beginPath(); ctx.arc(cx, cy, bw * 0.08, 0, Math.PI * 2);
    ctx.strokeStyle = "rgba(255,215,0,0.2)"; ctx.lineWidth = 1.5; ctx.stroke();

    // Sample coins
    const coinR = 7;
    const coins = [
      { x: cx, y: cy, col: "#E53935" },
      { x: cx - 22, y: cy - 18, col: "#EEEEEE" },
      { x: cx + 22, y: cy - 18, col: "#EEEEEE" },
      { x: cx - 38, y: cy, col: "#EEEEEE" },
      { x: cx + 38, y: cy, col: "#EEEEEE" },
      { x: cx, y: cy - 32, col: "#EEEEEE" },
      { x: cx - 22, y: cy + 18, col: "#111" },
      { x: cx + 22, y: cy + 18, col: "#111" },
      { x: cx - 38, y: cy - 28, col: "#111" },
      { x: cx + 38, y: cy - 28, col: "#111" },
    ];
    for (const c of coins) {
      ctx.beginPath(); ctx.arc(c.x, c.y, coinR, 0, Math.PI * 2);
      ctx.fillStyle = c.col; ctx.fill();
      ctx.strokeStyle = "rgba(255,255,255,0.25)"; ctx.lineWidth = 1; ctx.stroke();
    }

    // Striker position in canvas coords
    const sx = bx + striker.x * bw;
    const sy = by + striker.y * bh;
    const sr = 11;

    if (active) {
      const lineCol = colorVal(lineColor);
      const dx = Math.cos(aimAngle), dy = Math.sin(aimAngle);

      // How far until hitting board wall
      const maxLen = bw * power * 1.2;

      // ─── Glow behind line ───
      ctx.save();
      ctx.beginPath(); ctx.moveTo(sx, sy); ctx.lineTo(sx + dx * maxLen, sy + dy * maxLen);
      ctx.strokeStyle = lineCol + "33"; ctx.lineWidth = 18; ctx.lineCap = "round"; ctx.stroke();
      ctx.restore();

      // ─── Main aim line ───
      ctx.beginPath(); ctx.moveTo(sx, sy); ctx.lineTo(sx + dx * maxLen, sy + dy * maxLen);
      ctx.strokeStyle = lineCol; ctx.lineWidth = 2.5; ctx.setLineDash([]);
      ctx.lineCap = "round"; ctx.stroke();

      // ─── Bank shot reflection ───
      if (showReflect) {
        // Find wall intersection
        let t = Infinity;
        if (dx > 0) t = Math.min(t, (bx + bw - sx) / dx);
        if (dx < 0) t = Math.min(t, (bx - sx) / dx);
        if (dy > 0) t = Math.min(t, (by + bh - sy) / dy);
        if (dy < 0) t = Math.min(t, (by - sy) / dy);

        if (t < maxLen * 0.9) {
          const wx = sx + dx * t, wy = sy + dy * t;
          // Reflect
          let rdx = dx, rdy = dy;
          if (Math.abs(wx - bx) < 2 || Math.abs(wx - (bx + bw)) < 2) rdx = -dx;
          else rdy = -dy;

          const bankLen = (maxLen - t) * 0.7;
          ctx.beginPath(); ctx.moveTo(wx, wy); ctx.lineTo(wx + rdx * bankLen, wy + rdy * bankLen);
          ctx.strokeStyle = CYAN; ctx.lineWidth = 1.5;
          ctx.setLineDash([6, 5]); ctx.stroke();
          ctx.setLineDash([]);

          // Wall hit dot
          ctx.beginPath(); ctx.arc(wx, wy, 4, 0, Math.PI * 2);
          ctx.fillStyle = CYAN; ctx.fill();
        }
      }

      // ─── Angle readout ───
      if (showAngle) {
        const deg = Math.round(((aimAngle * 180 / Math.PI) % 360 + 360) % 360);
        ctx.font = "bold 12px system-ui";
        ctx.fillStyle = lineCol;
        ctx.textAlign = "left";
        ctx.fillText(deg + "°", sx + 14, sy - 10);
      }
    }

    // ─── Striker ───
    ctx.beginPath(); ctx.arc(sx, sy, sr, 0, Math.PI * 2);
    ctx.fillStyle = active ? "rgba(255,255,255,0.9)" : "rgba(255,255,255,0.4)";
    ctx.fill();
    ctx.beginPath(); ctx.arc(sx, sy, sr, 0, Math.PI * 2);
    ctx.strokeStyle = active ? GOLD : "#555";
    ctx.lineWidth = 2; ctx.stroke();

    // Inner dot
    ctx.beginPath(); ctx.arc(sx, sy, 3, 0, Math.PI * 2);
    ctx.fillStyle = active ? GOLD : "#555"; ctx.fill();

  }, [active, striker, aimAngle, power, lineColor, showAngle, showReflect]);

  useEffect(() => { draw(); }, [draw]);

  // Touch/mouse handling for dragging striker & aiming
  const getPos = (e: React.MouseEvent | React.TouchEvent, canvas: HTMLCanvasElement) => {
    const rect = canvas.getBoundingClientRect();
    const scaleX = canvas.width / rect.width;
    const scaleY = canvas.height / rect.height;
    const boardPad = 16;
    if ("touches" in e) {
      return {
        x: ((e.touches[0].clientX - rect.left) * scaleX - boardPad) / (canvas.width - boardPad * 2),
        y: ((e.touches[0].clientY - rect.top) * scaleY - boardPad) / (canvas.height - boardPad * 2),
        cx: (e.touches[0].clientX - rect.left) * scaleX,
        cy: (e.touches[0].clientY - rect.top) * scaleY,
      };
    }
    return {
      x: ((e.clientX - rect.left) * scaleX - boardPad) / (canvas.width - boardPad * 2),
      y: ((e.clientY - rect.top) * scaleY - boardPad) / (canvas.height - boardPad * 2),
      cx: (e.clientX - rect.left) * scaleX,
      cy: (e.clientY - rect.top) * scaleY,
    };
  };

  const onDown = (e: React.MouseEvent | React.TouchEvent) => {
    if (!active) return;
    const canvas = canvasRef.current; if (!canvas) return;
    const { x, y } = getPos(e, canvas);
    const boardPad = 16;
    const bw = canvas.width - boardPad * 2, bh = canvas.height - boardPad * 2;
    const dist = Math.hypot((x - striker.x) * bw, (y - striker.y) * bh);
    if (dist < 22) { dragging.current = true; }
  };

  const onMove = (e: React.MouseEvent | React.TouchEvent) => {
    if (!active) return;
    const canvas = canvasRef.current; if (!canvas) return;
    e.preventDefault();
    const { x, y, cx, cy } = getPos(e, canvas);
    if (dragging.current) {
      setStriker({ x: Math.max(0.05, Math.min(0.95, x)), y: Math.max(0.05, Math.min(0.95, y)) });
    } else {
      // Update aim angle based on touch position relative to striker
      const boardPad = 16;
      const bw = canvas.width - boardPad * 2, bh = canvas.height - boardPad * 2;
      const sx = boardPad + striker.x * bw, sy = boardPad + striker.y * bh;
      const angle = Math.atan2(cy - sy, cx - sx);
      setAimAngle(angle);
    }
  };

  const onUp = () => { dragging.current = false; };

  return (
    <canvas
      ref={canvasRef}
      width={320} height={320}
      onMouseDown={onDown} onMouseMove={onMove} onMouseUp={onUp}
      onTouchStart={onDown} onTouchMove={onMove} onTouchEnd={onUp}
      style={{ width: "100%", borderRadius: 10, cursor: active ? "crosshair" : "default", touchAction: "none", display: "block" }}
    />
  );
}

// ─── Floating Button (stays on screen, draggable) ────────────────────────────
function FloatingButton({ color, onClose }: { color: "gold" | "cyan" | "white"; onClose: () => void }) {
  const [pos, setPos] = useState({ x: window.innerWidth - 70, y: window.innerHeight * 0.45 });
  const [expanded, setExpanded] = useState(false);
  const dragging = useRef(false);
  const startPos = useRef({ mx: 0, my: 0, bx: 0, by: 0 });
  const moved = useRef(false);

  const onDown = (e: React.PointerEvent) => {
    dragging.current = true; moved.current = false;
    startPos.current = { mx: e.clientX, my: e.clientY, bx: pos.x, by: pos.y };
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
  };
  const onMove = (e: React.PointerEvent) => {
    if (!dragging.current) return;
    const dx = e.clientX - startPos.current.mx, dy = e.clientY - startPos.current.my;
    if (Math.abs(dx) > 4 || Math.abs(dy) > 4) moved.current = true;
    setPos({ x: startPos.current.bx + dx, y: startPos.current.by + dy });
  };
  const onUp = () => {
    dragging.current = false;
    if (!moved.current) setExpanded(v => !v);
  };

  const c = colorVal(color);

  return (
    <div style={{ position: "fixed", left: pos.x, top: pos.y, zIndex: 9999, userSelect: "none" }}>
      {/* Expanded menu */}
      {expanded && (
        <div style={{
          position: "absolute", bottom: 64, right: 0,
          background: "#16162E", border: `1px solid ${c}`, borderRadius: 12,
          padding: 10, minWidth: 130, boxShadow: `0 4px 24px ${c}44`
        }}>
          {[
            { icon: "✕", label: "Close Overlay", action: () => { setExpanded(false); onClose(); }, col: "#FF5555" },
            { icon: "⊕", label: "Aim ON", action: () => setExpanded(false), col: GREEN },
            { icon: "◎", label: "Reset Aim", action: () => setExpanded(false), col: CYAN },
          ].map((item, i) => (
            <div key={i} onClick={item.action} style={{
              display: "flex", alignItems: "center", gap: 8, padding: "8px 6px",
              cursor: "pointer", borderRadius: 8, color: item.col, fontWeight: 700, fontSize: 13,
            }}>
              <span style={{ fontSize: 16 }}>{item.icon}</span> {item.label}
            </div>
          ))}
        </div>
      )}

      {/* Main floating button */}
      <div
        onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp}
        style={{
          width: 52, height: 52, borderRadius: 26,
          background: expanded ? `${c}22` : "#0D0D1A",
          border: `2.5px solid ${c}`,
          display: "flex", alignItems: "center", justifyContent: "center",
          cursor: "grab", boxShadow: `0 2px 20px ${c}66`,
          transition: "background 0.15s",
        }}>
        <span style={{ fontSize: 22, color: c, lineHeight: 1 }}>⊕</span>
      </div>

      {/* Pulsing ring when active */}
      <div style={{
        position: "absolute", inset: -6, borderRadius: 32,
        border: `1.5px solid ${c}`, opacity: 0.4,
        animation: "pulse 2s infinite",
        pointerEvents: "none",
      }} />

      <style>{`@keyframes pulse{0%,100%{transform:scale(1);opacity:0.4}50%{transform:scale(1.18);opacity:0.1}}`}</style>
    </div>
  );
}

// ─── Floating button demo inside the settings card ───────────────────────────
function FloatButtonPreview({ active, color }: { active: boolean; color: "gold" | "cyan" | "white" }) {
  const c = colorVal(color);
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
      <div style={{
        width: 48, height: 48, borderRadius: 24,
        background: active ? `${c}18` : "#111",
        border: `2px solid ${active ? c : BORDER}`,
        display: "flex", alignItems: "center", justifyContent: "center",
        boxShadow: active ? `0 0 16px ${c}55` : "none",
        transition: "all 0.3s",
      }}>
        <span style={{ fontSize: 22, color: active ? c : "#444" }}>⊕</span>
      </div>
      <div style={{ fontSize: 12, color: "#8888BB" }}>
        {active
          ? "Floating button visible — drag it anywhere"
          : "Tap START to show the floating button"}
      </div>
    </div>
  );
}

// ─── Toggle Row ──────────────────────────────────────────────────────────────
function ToggleRow({ label, sub, checked, color, onChange }: {
  label: string; sub: string; checked: boolean; color: string; onChange: () => void;
}) {
  return (
    <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
      <div style={{ flex: 1, paddingRight: 10 }}>
        <div style={{ fontSize: 14, fontWeight: 700 }}>{label}</div>
        <div style={{ fontSize: 12, color: "#8888BB", marginTop: 2 }}>{sub}</div>
      </div>
      <div onClick={onChange} style={{
        width: 46, height: 26, borderRadius: 13, cursor: "pointer",
        background: checked ? color : "#2a2a4a", position: "relative",
        flexShrink: 0, transition: "background 0.2s",
      }}>
        <div style={{
          position: "absolute", top: 3, left: checked ? 22 : 3,
          width: 20, height: 20, borderRadius: 10,
          background: "#fff", transition: "left 0.2s",
        }} />
      </div>
    </div>
  );
}

// ─── Slider Row ──────────────────────────────────────────────────────────────
function SliderRow({ label, value, min, max, step, display, color, leftLabel, rightLabel, onChange }: {
  label: string; value: number; min: number; max: number; step: number;
  display: string; color: string; leftLabel?: string; rightLabel?: string;
  onChange: (v: number) => void;
}) {
  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 4 }}>
        <div style={{ fontSize: 14, fontWeight: 700 }}>{label}</div>
        <div style={{ fontSize: 14, fontWeight: 700, color }}>{display}</div>
      </div>
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
