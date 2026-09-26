// 契约检查（CI 必跑，任一不过即失败）：
//   1. 参考实现重生成 golden → 与 contracts/golden 逐字节 diff（防手改 / 防参考实现漂移未落盘）
//   2. spec/dissolve.ir.json == 参考模板（holdMs=5000），且通过参考校验
//   3. spec/transitions.json 与参考 FSM 表一致
//   4. effect token：spec/effects.json ↔ Kotlin Effect ↔ Swift Effect 三方集合与顺序一致
//   5. 措辞红线：库源码不得出现宿主业务领域词（边界尺子 1 / 系列索引治理规则 5）
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, readdirSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { dissolveTemplate, validateIr, transition, STATES, EVENTS } from "./reference/cv-ref.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const failures = [];
const check = (ok, msg) => { if (!ok) failures.push(msg); };
const read = (p) => readFileSync(join(root, p), "utf8");

// 1 golden
const tmp = mkdtempSync(join(tmpdir(), "cv-golden-"));
execFileSync(process.execPath, [join(root, "tools/reference/gen-golden.mjs"), "--out", tmp], { stdio: "ignore" });
for (const f of readdirSync(tmp)) {
  check(readFileSync(join(tmp, f), "utf8") === read(`contracts/golden/${f}`), `golden 漂移：contracts/golden/${f} 与参考实现重生成结果不一致（运行 node tools/reference/gen-golden.mjs）`);
}
for (const f of readdirSync(join(root, "contracts/golden"))) {
  if (f.endsWith(".json")) check(readdirSync(tmp).includes(f), `golden 多余文件：${f}`);
}

// 2 spec IR
const specIr = JSON.parse(read("spec/dissolve.ir.json"));
check(JSON.stringify(specIr) === JSON.stringify(dissolveTemplate({ holdMs: 5000 })), "spec/dissolve.ir.json 与参考模板不一致");
check(validateIr(specIr).length === 0, `spec/dissolve.ir.json 校验失败：${validateIr(specIr)}`);

// 3 FSM
const tr = JSON.parse(read("spec/transitions.json"));
check(JSON.stringify(tr.states) === JSON.stringify(STATES), "transitions.states 与参考不一致");
check(JSON.stringify(tr.events) === JSON.stringify(EVENTS), "transitions.events 与参考不一致");
for (const s of STATES) for (const e of EVENTS) {
  const row = tr.transitions.find((x) => x.from === s && x.event === e);
  check((row ? row.to : null) === transition(s, e), `FSM 不一致：${s} + ${e}`);
}

// 4 effect token 三方一致
const specTokens = JSON.parse(read("spec/effects.json")).effects.map((e) => e.token);
const kt = read("android/core/src/main/kotlin/ai/ciphervisual/core/Effect.kt");
const ktTokens = [...kt.matchAll(/^\s+[A-Z_]+\("([a-z]+)", ModelType\./gm)].map((m) => m[1]);
const sw = read("ios/Sources/CipherVisualCore/Primitives.swift");
const swLine = sw.match(/public enum Effect[^{]*\{\s*case ([^\n]+)/);
const swTokens = swLine ? swLine[1].split(",").map((s) => s.trim()) : [];
check(JSON.stringify(ktTokens) === JSON.stringify(specTokens), `Kotlin Effect ≠ spec：${ktTokens}`);
check(JSON.stringify(swTokens) === JSON.stringify(specTokens), `Swift Effect ≠ spec：${swTokens}`);

// 5 措辞红线（宿主业务词不得作为库概念出现在源码）
const FORBIDDEN = [/cipherlex/i, /decrypt/i, /disguise/i, /plaintext/i, /解密/, /伪装/, /明文/, /群组/, /暗号/];
const walk = (dir) => readdirSync(dir).flatMap((n) => {
  const p = join(dir, n);
  if (n === "build" || n.startsWith(".")) return [];
  return statSync(p).isDirectory() ? walk(p) : /\.(kt|kts|swift)$/.test(n) ? [p] : [];
});
for (const f of [...walk(join(root, "android")), ...walk(join(root, "ios"))]) {
  const text = readFileSync(f, "utf8");
  for (const re of FORBIDDEN) check(!re.test(text), `措辞红线：${f.slice(root.length + 1)} 命中 ${re}`);
}

if (failures.length) {
  console.error(`contract-check 失败（${failures.length}）：\n- ` + failures.join("\n- "));
  process.exit(1);
}
console.log("contract-check 通过");
