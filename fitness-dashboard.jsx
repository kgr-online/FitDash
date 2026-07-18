import { useState, useEffect } from "react";

// ── Mock Health Connect data ──────────────────────────────────────────────────
// Replace these with real Health Connect API calls (via Android WebView bridge
// or a local server that reads from the HC provider).
const TODAY = {
  steps: 8342,
  stepsGoal: 10000,
  activeCalories: 387,
  totalCalories: 1820,
  caloriesGoal: 2200,
  heartRateResting: 54,
  heartRateCurrent: 72,
  heartRateMax: 148,
  sleepHours: 6.8,
  sleepGoal: 8,
  sleepDeep: 1.4,
  sleepRem: 1.9,
  sleepLight: 3.5,
  exerciseMinutes: 38,
  exerciseGoal: 30,
  distance: 6.1, // km
  vo2max: 44.2,
  spo2: 98,
};

const WEEK = [
  { day: "MON", steps: 11200, calories: 420, sleep: 7.2, hr: 61 },
  { day: "TUE", steps: 6800,  calories: 290, sleep: 6.1, hr: 68 },
  { day: "WED", steps: 9400,  calories: 380, sleep: 7.8, hr: 59 },
  { day: "THU", steps: 12100, calories: 510, sleep: 8.1, hr: 57 },
  { day: "FRI", steps: 7300,  calories: 310, sleep: 5.9, hr: 71 },
  { day: "SAT", steps: 15400, calories: 640, sleep: 9.0, hr: 55 },
  { day: "SUN", steps: 8342,  calories: 387, sleep: 6.8, hr: 54 },
];

// ── Helpers ───────────────────────────────────────────────────────────────────
const pct = (val, goal) => Math.min(100, Math.round((val / goal) * 100));
const fmt = (n) => n.toLocaleString();
const pad2 = (n) => String(Math.floor(n)).padStart(2, "0");
const fmtSleep = (h) => `${pad2(h)}h ${pad2((h % 1) * 60)}m`;

// ── Ring component ────────────────────────────────────────────────────────────
function Ring({ value, goal, color, size = 80, stroke = 6, label, sub }) {
  const r = (size - stroke * 2) / 2;
  const circ = 2 * Math.PI * r;
  const p = pct(value, goal);
  const dash = (p / 100) * circ;

  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 6 }}>
      <svg width={size} height={size} style={{ transform: "rotate(-90deg)" }}>
        <circle cx={size/2} cy={size/2} r={r} fill="none" stroke="#1e1e1e" strokeWidth={stroke} />
        <circle
          cx={size/2} cy={size/2} r={r} fill="none"
          stroke={color} strokeWidth={stroke}
          strokeDasharray={`${dash} ${circ}`}
          strokeLinecap="round"
          style={{ transition: "stroke-dasharray 1s cubic-bezier(.4,0,.2,1)" }}
        />
      </svg>
      <div style={{ textAlign: "center", marginTop: -4 }}>
        <div style={{ fontFamily: "'IBM Plex Mono', monospace", fontSize: 11, color: "#888", letterSpacing: 1 }}>{label}</div>
        <div style={{ fontFamily: "'IBM Plex Mono', monospace", fontSize: 13, color: "#ddd" }}>{sub}</div>
      </div>
    </div>
  );
}

// ── Spark bar ────────────────────────────────────────────────────────────────
function SparkBars({ data, accessor, color, height = 32 }) {
  const vals = data.map(accessor);
  const max = Math.max(...vals);
  return (
    <div style={{ display: "flex", alignItems: "flex-end", gap: 3, height }}>
      {data.map((d, i) => {
        const h = Math.round((vals[i] / max) * height);
        const isToday = i === data.length - 1;
        return (
          <div key={i} style={{ flex: 1, display: "flex", flexDirection: "column", alignItems: "center", gap: 2 }}>
            <div style={{
              width: "100%", height: h,
              background: isToday ? color : "#2a2a2a",
              borderRadius: 2,
              transition: `height 0.8s ${i * 0.05}s cubic-bezier(.4,0,.2,1)`
            }} />
          </div>
        );
      })}
    </div>
  );
}

// ── Metric tile ──────────────────────────────────────────────────────────────
function Tile({ label, value, unit, sub, accent = "#c8b89a", children }) {
  return (
    <div style={{
      background: "#111",
      border: "1px solid #1e1e1e",
      borderRadius: 2,
      padding: "18px 20px",
      display: "flex",
      flexDirection: "column",
      gap: 8,
    }}>
      <div style={{ fontFamily: "'IBM Plex Mono', monospace", fontSize: 9, letterSpacing: 2, color: "#555", textTransform: "uppercase" }}>
        {label}
      </div>
      <div style={{ display: "flex", alignItems: "baseline", gap: 4 }}>
        <span style={{ fontFamily: "'IBM Plex Mono', monospace", fontSize: 28, fontWeight: 400, color: "#e8e0d5", lineHeight: 1 }}>
          {value}
        </span>
        {unit && <span style={{ fontFamily: "'IBM Plex Mono', monospace", fontSize: 11, color: "#555" }}>{unit}</span>}
      </div>
      {sub && <div style={{ fontFamily: "'IBM Plex Mono', monospace", fontSize: 10, color: "#444" }}>{sub}</div>}
      {children}
    </div>
  );
}

// ── Progress bar ─────────────────────────────────────────────────────────────
function Bar({ value, goal, color }) {
  return (
    <div style={{ height: 2, background: "#1e1e1e", borderRadius: 1, marginTop: 4 }}>
      <div style={{
        height: "100%", borderRadius: 1,
        background: color,
        width: `${pct(value, goal)}%`,
        transition: "width 1s cubic-bezier(.4,0,.2,1)"
      }} />
    </div>
  );
}

// ── Sleep breakdown ──────────────────────────────────────────────────────────
function SleepBar({ deep, rem, light, total }) {
  const segments = [
    { label: "DEEP", val: deep, color: "#3d5a80" },
    { label: "REM",  val: rem,  color: "#7b9e87" },
    { label: "LGT",  val: light, color: "#2a2a2a" },
  ];
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
      <div style={{ display: "flex", gap: 2, height: 6, borderRadius: 1, overflow: "hidden" }}>
        {segments.map(s => (
          <div key={s.label} style={{ flex: s.val, background: s.color }} />
        ))}
      </div>
      <div style={{ display: "flex", gap: 12 }}>
        {segments.map(s => (
          <div key={s.label} style={{ display: "flex", alignItems: "center", gap: 4 }}>
            <div style={{ width: 6, height: 6, borderRadius: "50%", background: s.color }} />
            <span style={{ fontFamily: "'IBM Plex Mono', monospace", fontSize: 9, color: "#444", letterSpacing: 1 }}>
              {s.label} {fmtSleep(s.val)}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ── HR zone strip ────────────────────────────────────────────────────────────
function HRStrip({ current, resting, max }) {
  const zones = [
    { label: "REST", range: [40, 60], color: "#2a3a2a" },
    { label: "FAT",  range: [60, 100], color: "#2a3a30" },
    { label: "CARD", range: [100, 140], color: "#3a3a20" },
    { label: "PEAK", range: [140, 200], color: "#3a2020" },
  ];
  const pos = Math.min(100, Math.max(0, ((current - 40) / 160) * 100));
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
      <div style={{ position: "relative", height: 8, display: "flex", gap: 2 }}>
        {zones.map(z => <div key={z.label} style={{ flex: z.range[1] - z.range[0], background: z.color, borderRadius: 1 }} />)}
        <div style={{
          position: "absolute", top: -2, left: `${pos}%`,
          width: 2, height: 12, background: "#c8b89a", borderRadius: 1,
          transform: "translateX(-50%)",
          transition: "left 1s cubic-bezier(.4,0,.2,1)"
        }} />
      </div>
      <div style={{ display: "flex", justifyContent: "space-between" }}>
        {zones.map(z => (
          <span key={z.label} style={{ fontFamily: "'IBM Plex Mono', monospace", fontSize: 8, color: "#333", letterSpacing: 1 }}>{z.label}</span>
        ))}
      </div>
    </div>
  );
}

// ── Main app ──────────────────────────────────────────────────────────────────
export default function FitnessDashboard() {
  const [tab, setTab] = useState("today");
  const [time, setTime] = useState(new Date());

  useEffect(() => {
    const t = setInterval(() => setTime(new Date()), 1000);
    return () => clearInterval(t);
  }, []);

  const accent = "#c8b89a";
  const green = "#7b9e87";
  const blue = "#3d5a80";

  const dayLabel = time.toLocaleDateString("en-US", { weekday: "long", month: "long", day: "numeric" });
  const timeStr = time.toLocaleTimeString("en-US", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });

  return (
    <div style={{
      minHeight: "100vh",
      background: "#0a0a0a",
      color: "#e8e0d5",
      fontFamily: "'IBM Plex Mono', monospace",
    }}>
      <link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@300;400;500&display=swap" rel="stylesheet" />

      {/* Header */}
      <div style={{
        borderBottom: "1px solid #1a1a1a",
        padding: "16px 24px",
        display: "flex",
        justifyContent: "space-between",
        alignItems: "center",
      }}>
        <div>
          <div style={{ fontSize: 9, letterSpacing: 3, color: "#444", marginBottom: 2 }}>HEALTH CONNECT</div>
          <div style={{ fontSize: 12, color: "#666" }}>{dayLabel}</div>
        </div>
        <div style={{ textAlign: "right" }}>
          <div style={{ fontSize: 20, color: "#333", letterSpacing: 2 }}>{timeStr}</div>
          <div style={{ fontSize: 9, letterSpacing: 2, color: "#2a2a2a", marginTop: 2 }}>LOCAL</div>
        </div>
      </div>

      {/* Tabs */}
      <div style={{ display: "flex", borderBottom: "1px solid #1a1a1a", padding: "0 24px" }}>
        {["today", "week"].map(t => (
          <button key={t} onClick={() => setTab(t)} style={{
            background: "none", border: "none", cursor: "pointer",
            padding: "12px 0", marginRight: 24,
            fontFamily: "'IBM Plex Mono', monospace",
            fontSize: 9, letterSpacing: 2,
            color: tab === t ? accent : "#333",
            borderBottom: tab === t ? `1px solid ${accent}` : "1px solid transparent",
            textTransform: "uppercase",
          }}>{t}</button>
        ))}
      </div>

      {/* Content */}
      <div style={{ padding: 24 }}>

        {tab === "today" && (
          <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>

            {/* Rings row */}
            <div style={{
              background: "#111",
              border: "1px solid #1e1e1e",
              borderRadius: 2,
              padding: "20px 24px",
              display: "flex",
              justifyContent: "space-around",
              alignItems: "flex-start",
            }}>
              <Ring value={TODAY.steps} goal={TODAY.stepsGoal} color={accent} label="STEPS" sub={`${pct(TODAY.steps, TODAY.stepsGoal)}%`} />
              <Ring value={TODAY.activeCalories} goal={TODAY.caloriesGoal} color={green} label="KCAL" sub={`${pct(TODAY.activeCalories, TODAY.caloriesGoal)}%`} />
              <Ring value={TODAY.exerciseMinutes} goal={TODAY.exerciseGoal} color={blue} label="MOVE" sub={`${pct(TODAY.exerciseMinutes, TODAY.exerciseGoal)}%`} />
              <Ring value={TODAY.sleepHours} goal={TODAY.sleepGoal} color="#6a5acd" label="SLEEP" sub={`${pct(TODAY.sleepHours, TODAY.sleepGoal)}%`} />
            </div>

            {/* Steps + Distance */}
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
              <Tile label="Steps" value={fmt(TODAY.steps)} sub={`Goal: ${fmt(TODAY.stepsGoal)} · ${pct(TODAY.steps, TODAY.stepsGoal)}%`} accent={accent}>
                <Bar value={TODAY.steps} goal={TODAY.stepsGoal} color={accent} />
              </Tile>
              <Tile label="Distance" value={TODAY.distance.toFixed(1)} unit="km" sub={`≈ ${(TODAY.distance * 0.621371).toFixed(1)} mi`} />
            </div>

            {/* Heart rate */}
            <Tile label="Heart Rate" value={TODAY.heartRateCurrent} unit="bpm" sub={`Resting ${TODAY.heartRateResting} · Peak ${TODAY.heartRateMax}`}>
              <HRStrip current={TODAY.heartRateCurrent} resting={TODAY.heartRateResting} max={TODAY.heartRateMax} />
            </Tile>

            {/* Sleep */}
            <Tile label="Sleep" value={fmtSleep(TODAY.sleepHours)} sub={`Goal: ${fmtSleep(TODAY.sleepGoal)}`}>
              <SleepBar deep={TODAY.sleepDeep} rem={TODAY.sleepRem} light={TODAY.sleepLight} total={TODAY.sleepHours} />
            </Tile>

            {/* Calories + VO2 + SpO2 */}
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 12 }}>
              <Tile label="Active Cal" value={fmt(TODAY.activeCalories)} unit="kcal" sub={`/ ${fmt(TODAY.caloriesGoal)}`}>
                <Bar value={TODAY.activeCalories} goal={TODAY.caloriesGoal} color={green} />
              </Tile>
              <Tile label="VO₂ Max" value={TODAY.vo2max.toFixed(1)} unit="ml/kg" sub="Fitness score" />
              <Tile label="SpO₂" value={`${TODAY.spo2}%`} sub="Blood oxygen" />
            </div>

          </div>
        )}

        {tab === "week" && (
          <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>

            {/* Steps week */}
            <Tile label="Steps — 7-day" value={fmt(Math.round(WEEK.reduce((a, d) => a + d.steps, 0) / 7))} unit="avg/day" sub="Today highlighted">
              <div style={{ marginTop: 8 }}>
                <SparkBars data={WEEK} accessor={d => d.steps} color={accent} height={40} />
                <div style={{ display: "flex", justifyContent: "space-between", marginTop: 4 }}>
                  {WEEK.map(d => (
                    <span key={d.day} style={{ fontSize: 8, color: "#333", flex: 1, textAlign: "center" }}>{d.day}</span>
                  ))}
                </div>
              </div>
            </Tile>

            {/* Calories week */}
            <Tile label="Active Calories — 7-day" value={fmt(Math.round(WEEK.reduce((a, d) => a + d.calories, 0) / 7))} unit="kcal avg" sub="Today highlighted">
              <div style={{ marginTop: 8 }}>
                <SparkBars data={WEEK} accessor={d => d.calories} color={green} height={40} />
                <div style={{ display: "flex", justifyContent: "space-between", marginTop: 4 }}>
                  {WEEK.map(d => (
                    <span key={d.day} style={{ fontSize: 8, color: "#333", flex: 1, textAlign: "center" }}>{d.day}</span>
                  ))}
                </div>
              </div>
            </Tile>

            {/* Sleep week */}
            <Tile label="Sleep — 7-day" value={fmtSleep(WEEK.reduce((a, d) => a + d.sleep, 0) / 7)} sub="Weekly average · Today highlighted">
              <div style={{ marginTop: 8 }}>
                <SparkBars data={WEEK} accessor={d => d.sleep} color="#6a5acd" height={40} />
                <div style={{ display: "flex", justifyContent: "space-between", marginTop: 4 }}>
                  {WEEK.map(d => (
                    <span key={d.day} style={{ fontSize: 8, color: "#333", flex: 1, textAlign: "center" }}>{d.day}</span>
                  ))}
                </div>
              </div>
            </Tile>

            {/* Resting HR week */}
            <Tile label="Resting HR — 7-day" value={Math.round(WEEK.reduce((a, d) => a + d.hr, 0) / 7)} unit="bpm avg" sub="Today highlighted">
              <div style={{ marginTop: 8 }}>
                <SparkBars data={WEEK} accessor={d => d.hr} color={blue} height={40} />
                <div style={{ display: "flex", justifyContent: "space-between", marginTop: 4 }}>
                  {WEEK.map(d => (
                    <span key={d.day} style={{ fontSize: 8, color: "#333", flex: 1, textAlign: "center" }}>{d.day}</span>
                  ))}
                </div>
              </div>
            </Tile>

            {/* Summary row */}
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
              <Tile label="Total Steps" value={fmt(WEEK.reduce((a, d) => a + d.steps, 0))} sub="This week" />
              <Tile label="Best Day" value={WEEK.reduce((a, d) => d.steps > a.steps ? d : a).day} sub={`${fmt(WEEK.reduce((a, d) => d.steps > a.steps ? d : a).steps)} steps`} />
            </div>
          </div>
        )}

      </div>

      {/* Footer */}
      <div style={{ padding: "12px 24px", borderTop: "1px solid #111", marginTop: 12 }}>
        <div style={{ fontSize: 8, color: "#222", letterSpacing: 2 }}>DATA SOURCE: HEALTH CONNECT · MOCK DATA</div>
      </div>
    </div>
  );
}
