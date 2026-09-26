// CipherVisual 纯函数层参考实现（JS）。
//
// 这是 golden 向量的唯一产出方：Kotlin / Swift 两端不互相对拍，而是各自对拍本文件
// 生成的 contracts/golden/*.json。算法口径以 spec/contracts.md 为准，本文件是它的可执行版。
// 只放纯函数（PRNG / 缓动 / 原语 / 相位 / 粒子场 / 轨迹 / FSM / 预算裁决），不放渲染。

// ---------------------------------------------------------------- PRNG · mulberry32

export class Mulberry32 {
  constructor(seed) {
    this.state = seed >>> 0;
  }
  nextUint32() {
    this.state = (this.state + 0x6d2b79f5) >>> 0;
    let t = this.state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t = (t + Math.imul(t ^ (t >>> 7), t | 61)) ^ t;
    return (t ^ (t >>> 14)) >>> 0;
  }
  nextDouble() {
    return this.nextUint32() / 4294967296;
  }
}

// ---------------------------------------------------------------- 缓动

export const EASINGS = {
  linear: (p) => p,
  cubicIn: (p) => p * p * p,
  cubicOut: (p) => {
    const q = 1 - p;
    return 1 - q * q * q;
  },
  cubicInOut: (p) => {
    if (p < 0.5) return 4 * p * p * p;
    const q = -2 * p + 2;
    return 1 - (q * q * q) / 2;
  },
};

export function ease(name, p) {
  const f = EASINGS[name];
  if (!f) throw new Error(`unknown ease: ${name}`);
  return f(p);
}

// ---------------------------------------------------------------- 原语 · keyframe

export function keyframeNext(prim, t) {
  const { times, values } = prim;
  const last = times.length - 1;
  if (t <= times[0]) return { value: values[0], done: false };
  if (t >= times[last]) return { value: values[last], done: true };
  let i = 0;
  while (t >= times[i + 1]) i++;
  const p = (t - times[i]) / (times[i + 1] - times[i]);
  const e = ease(prim.ease, p);
  return { value: values[i] + (values[i + 1] - values[i]) * e, done: false };
}

// ---------------------------------------------------------------- 相位

// 区间左闭右开；最后一个相位右端闭合。区间外返回 null。
export function phaseAt(phases, t) {
  for (let i = 0; i < phases.length; i++) {
    const [s, e] = phases[i].range;
    const isLast = i === phases.length - 1;
    if (t >= s && (t < e || (isLast && t === e))) return phases[i].id;
  }
  return null;
}

// ---------------------------------------------------------------- IR 模板 · dissolve

export const DEFAULT_SEED = 0x3f2a1c7b;

export function dissolveTemplate({ seed = DEFAULT_SEED, holdMs = 5000, holdMode = "static", gridPx = 4, jitterPx = 1 } = {}) {
  const holdEnd = 600 + holdMs;
  return {
    schema_version: 1,
    effect: "dissolve",
    model: { type: "particleField", sampler: { grid_px: gridPx, jitter_px: jitterPx }, seed: seed >>> 0 },
    phases: [
      { id: "burst", range: [0, 150] },
      { id: "dissolve", range: [150, 350] },
      { id: "resolve", range: [350, 600] },
      { id: "hold", range: [600, holdEnd], mode: holdMode },
    ],
    trajectory: { physics: { v0: 300, drag: 4, gravity: 200 }, driver: "prim_burst" },
    primitives: {
      prim_burst: { type: "keyframe", times: [0, 150, 600], values: [0, 1, 0], ease: "cubicOut" },
    },
    anchors: [
      { id: "onBurst", at: 0 },
      { id: "onResolve", at: 600 },
      { id: "onHoldExpired", at: holdEnd },
    ],
  };
}

// ---------------------------------------------------------------- IR 校验

export const EFFECTS_IMPLEMENTED = ["dissolve"];
export const EFFECTS_ALL = ["dissolve", "burst", "fall", "rise", "impact", "fade", "slide", "shake", "pulse", "scale", "spiral", "orbit"];
const DISSOLVE_PHASES = ["burst", "dissolve", "resolve", "hold"];

// 返回错误码数组（空 = 合法）。错误码是契约的一部分，两端必须产出同一集合。
export function validateIr(ir) {
  const errs = [];
  const add = (c) => errs.includes(c) || errs.push(c);
  if (ir.schema_version !== 1) add("SCHEMA_VERSION");
  if (!EFFECTS_ALL.includes(ir.effect)) add("EFFECT_UNKNOWN");
  else if (!EFFECTS_IMPLEMENTED.includes(ir.effect)) add("EFFECT_UNSUPPORTED");

  const m = ir.model || {};
  if (m.type !== "particleField") add("MODEL_TYPE");
  const g = m.sampler?.grid_px, j = m.sampler?.jitter_px;
  if (!Number.isInteger(g) || g < 1) add("SAMPLER_GRID");
  if (!Number.isInteger(j) || j < 0 || (Number.isInteger(g) && j >= g)) add("SAMPLER_JITTER");
  if (!Number.isInteger(m.seed) || m.seed < 0 || m.seed > 0xffffffff) add("SEED");

  const phases = ir.phases || [];
  if (phases.length === 0) add("PHASES_EMPTY");
  const ids = phases.map((p) => p.id);
  if (new Set(ids).size !== ids.length) add("PHASE_ID_DUP");
  phases.forEach((p, i) => {
    const [s, e] = p.range;
    if (!(s < e)) add("PHASE_RANGE");
    if (i === 0 && s !== 0) add("PHASE_START");
    if (i > 0 && phases[i - 1].range[1] !== s) add("PHASE_GAP");
    if (p.mode !== undefined && p.mode !== null) {
      if (p.id !== "hold") add("PHASE_MODE_SCOPE");
      else if (p.mode !== "static" && p.mode !== "jitter") add("PHASE_MODE");
    }
  });
  if (ir.effect === "dissolve" && JSON.stringify(ids) !== JSON.stringify(DISSOLVE_PHASES)) add("PHASE_SET");

  const phy = ir.trajectory?.physics || {};
  if (!(phy.v0 >= 0) || !(phy.drag > 0) || !Number.isFinite(phy.gravity)) add("PHYSICS");
  const prims = ir.primitives || {};
  if (!(ir.trajectory?.driver in prims)) add("DRIVER_REF");
  for (const k of Object.keys(prims)) {
    const p = prims[k];
    if (p.type !== "keyframe") { add("PRIM_TYPE"); continue; }
    if (!Array.isArray(p.times) || p.times.length < 2 || p.times.length !== p.values?.length) add("PRIM_SHAPE");
    else if (p.times.some((t, i) => i > 0 && !(t > p.times[i - 1]))) add("PRIM_TIMES");
    if (!(p.ease in EASINGS)) add("PRIM_EASE");
  }

  const end = phases.length ? phases[phases.length - 1].range[1] : 0;
  const aids = (ir.anchors || []).map((a) => a.id);
  if (new Set(aids).size !== aids.length) add("ANCHOR_ID_DUP");
  for (const a of ir.anchors || []) if (!(a.at >= 0 && a.at <= end)) add("ANCHOR_RANGE");
  return errs;
}

// ---------------------------------------------------------------- 粒子场 f(seed, grid, bitmap)

// bitmap = { width, height, pixels: [ARGB uint32，行主序] }
function sample(bitmap, grid, jitter, rng) {
  const out = [];
  const cols = Math.ceil(bitmap.width / grid);
  const rows = Math.ceil(bitmap.height / grid);
  const half = Math.floor(grid / 2);
  for (let gy = 0; gy < rows; gy++) {
    for (let gx = 0; gx < cols; gx++) {
      let jx = 0, jy = 0;
      if (jitter > 0) {
        jx = Math.floor(rng.nextDouble() * (2 * jitter + 1)) - jitter;
        jy = Math.floor(rng.nextDouble() * (2 * jitter + 1)) - jitter;
      }
      const x = Math.min(Math.max(gx * grid + half + jx, 0), bitmap.width - 1);
      const y = Math.min(Math.max(gy * grid + half + jy, 0), bitmap.height - 1);
      const c = bitmap.pixels[y * bitmap.width + x] >>> 0;
      if (c >>> 24 === 0) continue;
      out.push({ x, y, c });
    }
  }
  return out;
}

const transparent = (c) => (c & 0x00ffffff) >>> 0;

export function particleField(seed, grid, jitter, source, target) {
  const rng = new Mulberry32(seed);
  const S = sample(source, grid, jitter, rng);
  const T = sample(target, grid, jitter, rng);
  const ns = S.length, nt = T.length, n = Math.max(ns, nt);
  const perm = Array.from({ length: nt }, (_, i) => i);
  for (let i = nt - 1; i >= 1; i--) {
    const j = Math.floor(rng.nextDouble() * (i + 1));
    const tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp;
  }
  const particles = [];
  for (let i = 0; i < n; i++) {
    const tgt = nt > 0 ? T[perm[i % nt]] : null;
    const src = ns > 0 ? S[i % ns] : null;
    // 多出来的一侧：位置借用对侧/循环样本，颜色透明 → 淡入/淡出
    const sx = src ? src.x : tgt.x, sy = src ? src.y : tgt.y;
    const sc = src && i < ns ? src.c : transparent(src ? src.c : tgt.c);
    const tx = tgt ? tgt.x : src.x, ty = tgt ? tgt.y : src.y;
    const tc = tgt && i < nt ? tgt.c : transparent(tgt ? tgt.c : src.c);
    const theta = rng.nextDouble() * 2 * Math.PI;
    const k = 0.5 + rng.nextDouble();
    const phase = rng.nextDouble() * 2 * Math.PI;
    particles.push({ sx, sy, sc, tx, ty, tc, dx: Math.cos(theta) * k, dy: Math.sin(theta) * k, phase });
  }
  return { sourceCount: ns, targetCount: nt, particles };
}

// ---------------------------------------------------------------- 轨迹

export const JITTER_AMPLITUDE_PX = 1.5;
export const JITTER_PERIOD_MS = 1200;

export function lerpArgb(a, b, m) {
  let out = 0;
  for (let shift = 24; shift >= 0; shift -= 8) {
    const ca = (a >>> shift) & 0xff, cb = (b >>> shift) & 0xff;
    out = out * 256 + Math.floor(ca + (cb - ca) * m + 0.5);
  }
  return out >>> 0;
}

// 时间轴派生量：morph 窗口 = [burst.end, hold.start]
export function timelineKnobs(ir) {
  const ph = Object.fromEntries(ir.phases.map((p) => [p.id, p]));
  return { morphStart: ph.burst.range[1], morphEnd: ph.hold.range[0], holdMode: ph.hold.mode || "static" };
}

export function morphAt(knobs, t) {
  if (t <= knobs.morphStart) return 0;
  if (t >= knobs.morphEnd) return 1;
  return EASINGS.cubicInOut((t - knobs.morphStart) / (knobs.morphEnd - knobs.morphStart));
}

// 返回 { x: [], y: [], c: [] }
export function trajectoryFrame(ir, field, t) {
  const knobs = timelineKnobs(ir);
  const phy = ir.trajectory.physics;
  const d = keyframeNext(ir.primitives[ir.trajectory.driver], t).value;
  const m = morphAt(knobs, t);
  const scatter = phy.v0 / phy.drag;
  const sag = phy.gravity / (phy.drag * phy.drag);
  const jitterOn = knobs.holdMode === "jitter" && t > knobs.morphEnd;
  const w = ((t - knobs.morphEnd) / JITTER_PERIOD_MS) * 2 * Math.PI;
  const xs = [], ys = [], cs = [];
  for (const p of field.particles) {
    let x = p.sx + (p.tx - p.sx) * m + d * p.dx * scatter;
    let y = p.sy + (p.ty - p.sy) * m + d * (p.dy * scatter + sag);
    if (jitterOn) {
      x += JITTER_AMPLITUDE_PX * Math.sin(w + p.phase);
      y += JITTER_AMPLITUDE_PX * Math.cos(w + p.phase);
    }
    xs.push(x); ys.push(y); cs.push(lerpArgb(p.sc, p.tc, m));
  }
  return { x: xs, y: ys, c: cs };
}

// ---------------------------------------------------------------- 句柄 FSM（5 态 4 事件）

export const STATES = ["idle", "active", "cancelling", "completed", "failed"];
export const EVENTS = ["play", "cancelTeardown", "cancelReverse", "reverseDone", "error"];
const TABLE = {
  idle: { play: "active" },
  active: { cancelTeardown: "completed", cancelReverse: "cancelling", error: "failed" },
  cancelling: { reverseDone: "completed", cancelTeardown: "completed", error: "failed" },
  completed: {},
  failed: {},
};
export function transition(state, event) {
  return TABLE[state][event] ?? null;
}

// ---------------------------------------------------------------- 预算裁决

// ladderCosts[i] = 该 handle 在粒子网格第 i 档（grid, grid*2, grid*4）下的粒子数
// running = [{ id, cost }] 按启动先后排列（最早在前）
export function admit(strategy, budget, running, ladderCosts) {
  const usage = running.reduce((s, r) => s + r.cost, 0);
  const fits = (c, u = usage) => u + c <= budget;
  switch (strategy) {
    case "degrade": {
      for (let i = 0; i < ladderCosts.length; i++) if (fits(ladderCosts[i])) return { kind: "admit", grid: i, evict: [] };
      return { kind: "admit", grid: ladderCosts.length - 1, evict: [] };
    }
    case "dropFrame":
      return { kind: "admit", grid: 0, evict: [] };
    case "queue":
      return fits(ladderCosts[0]) ? { kind: "admit", grid: 0, evict: [] } : { kind: "queue" };
    case "dropNewest":
      return fits(ladderCosts[0]) ? { kind: "admit", grid: 0, evict: [] } : { kind: "reject" };
    case "dropOldest": {
      const evict = [];
      let u = usage;
      for (const r of running) {
        if (fits(ladderCosts[0], u)) break;
        if (r.cost === 0) continue;
        evict.push(r.id);
        u -= r.cost;
      }
      return { kind: "admit", grid: 0, evict };
    }
    default:
      throw new Error(`unknown strategy: ${strategy}`);
  }
}
