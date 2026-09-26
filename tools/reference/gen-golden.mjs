// 从参考实现生成 contracts/golden/*.json。
//   node tools/reference/gen-golden.mjs            写入 contracts/golden/
//   node tools/reference/gen-golden.mjs --out DIR  写到别处（contract-check 用来做逐字节 diff）
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  Mulberry32, EASINGS, keyframeNext, phaseAt, dissolveTemplate, validateIr, particleField,
  trajectoryFrame, STATES, EVENTS, transition, admit, DEFAULT_SEED,
} from "./cv-ref.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const outIdx = process.argv.indexOf("--out");
const outDir = outIdx > 0 ? resolve(process.argv[outIdx + 1]) : join(root, "contracts/golden");
mkdirSync(outDir, { recursive: true });

const write = (name, body) =>
  writeFileSync(join(outDir, name), JSON.stringify({ _generated: "tools/reference/gen-golden.mjs — 禁止手改", ...body }, null, 2) + "\n");

// ---------------------------------------------------------------- prng
{
  const cases = [0, 1, DEFAULT_SEED, 0xffffffff, 123456789].map((seed) => {
    const r = new Mulberry32(seed);
    const uint32 = Array.from({ length: 8 }, () => r.nextUint32());
    const r2 = new Mulberry32(seed);
    const double = Array.from({ length: 4 }, () => r2.nextDouble());
    return { seed, uint32, double };
  });
  write("prng.json", { algorithm: "mulberry32", cases });
}

// ---------------------------------------------------------------- easing + keyframe
{
  const ps = [0, 0.1, 0.25, 0.5, 0.75, 0.9, 1];
  const easing = Object.keys(EASINGS).map((name) => ({ name, p: ps, value: ps.map((p) => EASINGS[name](p)) }));
  const prims = [
    { type: "keyframe", times: [0, 150, 600], values: [0, 1, 0], ease: "cubicOut" },
    { type: "keyframe", times: [100, 200, 300, 500], values: [2, -1, 0.5, 0.5], ease: "cubicInOut" },
    { type: "keyframe", times: [0, 1000], values: [10, 20], ease: "linear" },
  ];
  const ts = [-50, 0, 50, 100, 149.5, 150, 200, 299, 300, 450, 599.9, 600, 1000, 5000];
  const keyframe = prims.map((prim) => ({ prim, t: ts, samples: ts.map((t) => keyframeNext(prim, t)) }));
  write("primitive.json", { easing, keyframe });
}

// ---------------------------------------------------------------- timeline
{
  const ir = dissolveTemplate({ holdMs: 5000 });
  const ts = [-1, 0, 149.99, 150, 349, 350, 599, 600, 3000, 5600, 5600.01];
  write("timeline.json", {
    phases: ir.phases,
    t: ts,
    phase: ts.map((t) => phaseAt(ir.phases, t)),
  });
}

// ---------------------------------------------------------------- IR 模板 + 校验
{
  const templates = [
    { args: { seed: DEFAULT_SEED, holdMs: 5000, holdMode: "static", gridPx: 4 } },
    { args: { seed: 7, holdMs: 30000, holdMode: "jitter", gridPx: 8 } },
  ].map((c) => ({ ...c, ir: dissolveTemplate(c.args) }));

  const base = () => JSON.parse(JSON.stringify(dissolveTemplate({})));
  const mutate = (name, fn) => { const ir = base(); fn(ir); return { name, ir }; };
  const cases = [
    { name: "valid-default", ir: base() },
    mutate("schema-version", (ir) => { ir.schema_version = 2; }),
    mutate("effect-unknown", (ir) => { ir.effect = "gift"; }),
    mutate("effect-unsupported", (ir) => { ir.effect = "fade"; }),
    mutate("model-type", (ir) => { ir.model.type = "transform"; }),
    mutate("grid-zero", (ir) => { ir.model.sampler.grid_px = 0; }),
    mutate("jitter-ge-grid", (ir) => { ir.model.sampler.jitter_px = 4; }),
    mutate("seed-negative", (ir) => { ir.model.seed = -1; }),
    mutate("phases-gap", (ir) => { ir.phases[1].range = [160, 350]; }),
    mutate("phase-start", (ir) => { ir.phases[0].range = [10, 150]; }),
    mutate("phase-range", (ir) => { ir.phases[3].range = [600, 600]; }),
    mutate("phase-dup", (ir) => { ir.phases[1].id = "burst"; }),
    mutate("phase-mode-scope", (ir) => { ir.phases[0].mode = "static"; }),
    mutate("phase-mode", (ir) => { ir.phases[3].mode = "wobble"; }),
    mutate("physics", (ir) => { ir.trajectory.physics.drag = 0; }),
    mutate("driver-ref", (ir) => { ir.trajectory.driver = "prim_missing"; }),
    mutate("prim-times", (ir) => { ir.primitives.prim_burst.times = [0, 600, 150]; }),
    mutate("prim-shape", (ir) => { ir.primitives.prim_burst.values = [0, 1]; }),
    mutate("prim-ease", (ir) => { ir.primitives.prim_burst.ease = "bounce"; }),
    mutate("anchor-range", (ir) => { ir.anchors.push({ id: "late", at: 99999 }); }),
    mutate("anchor-dup", (ir) => { ir.anchors.push({ id: "onBurst", at: 10 }); }),
    mutate("phase-range-short", (ir) => { ir.phases[1].range = [150]; }),
    mutate("phase-range-empty", (ir) => { ir.phases[0].range = []; }),
    mutate("phase-range-long", (ir) => { ir.phases[3].range = [600, 5600, 7000]; }),
    mutate("phase-range-short-last", (ir) => { ir.phases[3].range = [600]; }),
  ].map((c) => ({ ...c, errors: validateIr(c.ir) }));
  write("ir.json", { templates, validation: cases });
}

// ---------------------------------------------------------------- 粒子场 + 轨迹

// 合成位图：行主序 ARGB。用字符画描述，'.' = 透明
function bitmap(rows, palette) {
  const height = rows.length, width = rows[0].length;
  const pixels = [];
  for (const row of rows) for (const ch of row) pixels.push(ch === "." ? 0 : palette[ch] >>> 0);
  return { width, height, pixels };
}
const PAL = { a: 0xff202020, b: 0xffe04040, c: 0x80ffffff, d: 0xff3080ff };
const glyphA = bitmap([
  "....aaaa....",
  "...aa..aa...",
  "..aa....aa..",
  "..aaaaaaaa..",
  "..aa....aa..",
  "..aa....aa..",
  "..aa....aa..",
  "............",
], PAL);
const glyphB = bitmap([
  "bbbbbbbbb...",
  "bb.....bbb..",
  "bb.....bbb..",
  "bbbbbbbbb...",
  "bb.....cbb..",
  "bb.....bbb..",
  "bbbbbbbbb...",
  "............",
], PAL);
const dot = bitmap(["....", ".dd.", ".dd.", "...."], PAL);
const empty = bitmap(["....", "...."], PAL);

{
  const fieldCases = [
    { name: "A->B grid2 jitter1", seed: DEFAULT_SEED, grid: 2, jitter: 1, source: glyphA, target: glyphB },
    { name: "A->B grid4 jitter0", seed: 42, grid: 4, jitter: 0, source: glyphA, target: glyphB },
    { name: "dot->A grid2 jitter0 (nt>ns)", seed: 9, grid: 2, jitter: 0, source: dot, target: glyphA },
    { name: "B->dot grid3 jitter2 (ns>nt)", seed: 0xffffffff, grid: 3, jitter: 2, source: glyphB, target: dot },
    { name: "empty->dot grid1 jitter0", seed: 1, grid: 1, jitter: 0, source: empty, target: dot },
    { name: "empty->empty", seed: 1, grid: 2, jitter: 1, source: empty, target: empty },
  ].map((c) => ({ ...c, field: particleField(c.seed, c.grid, c.jitter, c.source, c.target) }));
  write("particle-field.json", { cases: fieldCases });

  const ts = [0, 50, 150, 250, 350, 475, 600, 1400, 5600, 7000];
  const trajCases = ["static", "jitter"].map((holdMode) => {
    const ir = dissolveTemplate({ seed: 42, holdMs: 5000, holdMode, gridPx: 2, jitterPx: 1 });
    const { grid_px, jitter_px } = ir.model.sampler;
    const field = particleField(ir.model.seed, grid_px, jitter_px, glyphA, glyphB);
    return { holdMode, ir, source: glyphA, target: glyphB, t: ts, frames: ts.map((t) => trajectoryFrame(ir, field, t)) };
  });
  write("trajectory.json", { cases: trajCases });
}

// ---------------------------------------------------------------- FSM
{
  const table = STATES.map((s) => ({ state: s, next: Object.fromEntries(EVENTS.map((e) => [e, transition(s, e)])) }));
  write("fsm.json", { states: STATES, events: EVENTS, table });
}

// ---------------------------------------------------------------- 预算裁决
{
  const running = [{ id: 1, cost: 3000 }, { id: 2, cost: 0 }, { id: 3, cost: 2500 }, { id: 4, cost: 1000 }];
  const cases = [];
  for (const strategy of ["degrade", "dropFrame", "queue", "dropNewest", "dropOldest"]) {
    for (const [budget, ladder] of [[8000, [1200, 300, 75]], [8000, [4000, 1000, 250]], [8000, [9000, 2250, 600]], [3000, [20000, 5000, 1250]], [100000, [4000, 1000, 250]]]) {
      cases.push({ strategy, budget, running, ladder, decision: admit(strategy, budget, running, ladder) });
    }
  }
  cases.push({ strategy: "degrade", budget: 5000, running: [], ladder: [4000, 1000, 250], decision: admit("degrade", 5000, [], [4000, 1000, 250]) });
  // queue 严格 FIFO：前面有人排队，放得下也排队尾；超总预算仍先拒绝
  for (const queued of [1, 3]) {
    cases.push({ strategy: "queue", budget: 8000, running, ladder: [100, 25, 6], queued, decision: admit("queue", 8000, running, [100, 25, 6], queued) });
    cases.push({ strategy: "queue", budget: 8000, running, ladder: [9000, 2250, 600], queued, decision: admit("queue", 8000, running, [9000, 2250, 600], queued) });
    cases.push({ strategy: "dropNewest", budget: 8000, running, ladder: [100, 25, 6], queued, decision: admit("dropNewest", 8000, running, [100, 25, 6], queued) });
  }
  write("admission.json", { cases });
}

console.log(`golden written to ${outDir}`);
