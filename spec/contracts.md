# CipherVisual 双端契约（schema_version 1）

> 双端（Android / iOS）各自手写实现，**不互相对拍**，而是各自对拍 `contracts/golden/`——golden 由 `tools/reference/cv-ref.mjs`（JS 参考实现）生成。本文是参考实现的文字版；**文字与参考实现冲突时以参考实现 + golden 为准**，并回修本文。
>
> 设计来源（vault `20-知识/项目记录/CipherLex/`）：库边界与能力范畴 v0.6 · 时间轴 IR SSOT v0.3 · effect 原语集 v0.1 · 句柄状态机 v0.1。本文 §3–§6 是 IR SSOT 未写到算法级的部分，在实现时补定（见 §9「实现补定项」）。

## 1. 分层

```
facade（CipherVisual）→ VisualEngine（时间轴推进 / 锚点 / 预算）→ VisualHandle（FSM）
                              │
         纯函数层：IR 校验 · phaseAt · keyframe · 粒子场 · 轨迹 · 预算裁决   ← golden 对拍
                              │
         平台层：FrameClock（Choreographer / CADisplayLink）· 渲染（Canvas / CoreGraphics）· 内容解析
```

纯函数层零平台依赖（Android `:core` 为纯 JVM 模块；iOS `CipherVisualCore` 不 import UIKit）。**纯函数层之后不允许再有决策**——渲染器只照 `RenderInstruction` 画。

## 2. IR

字段见 `spec/dissolve.ir.json`（= `dissolveTemplate({ holdMs: 5000 })`）。JSON 键名同 vault IR SSOT §八 的 yaml。

- `holdMs` 是 **hold 相位时长**：`hold = [600, 600 + holdMs]`，模板额外产出锚点 `onHoldExpired @ 600 + holdMs`。
- 校验错误码（集合即契约，golden `ir.json` 覆盖每一条）：

| 码 | 条件 |
|---|---|
| `SCHEMA_VERSION` | `schema_version != 1` |
| `EFFECT_UNKNOWN` / `EFFECT_UNSUPPORTED` | 不在 12 原语内 / 在但未实现（MVP 仅 `dissolve`） |
| `MODEL_TYPE` | `model.type != particleField` |
| `SAMPLER_GRID` / `SAMPLER_JITTER` | `grid_px < 1` / `jitter_px < 0 或 ≥ grid_px` |
| `SEED` | 不在 uint32 范围 |
| `PHASES_EMPTY` / `PHASE_ID_DUP` / `PHASE_RANGE` / `PHASE_START` / `PHASE_GAP` | 空 / id 重复 / `!(start<end)` / 首相位不从 0 起 / 相邻不首尾相接 |
| `PHASE_MODE_SCOPE` / `PHASE_MODE` | 非 hold 相位带 `mode` / hold 的 `mode ∉ {static, jitter}` |
| `PHASE_SET` | dissolve 的相位 id 序列 ≠ `[burst, dissolve, resolve, hold]` |
| `PHYSICS` | `v0 < 0` 或 `drag ≤ 0` 或 `gravity` 非有限 |
| `DRIVER_REF` | `trajectory.driver` 不在 `primitives` |
| `PRIM_TYPE` / `PRIM_SHAPE` / `PRIM_TIMES` / `PRIM_EASE` | 非 keyframe / 长度 <2 或 times≠values / times 非严格递增 / 未知缓动 |
| `ANCHOR_ID_DUP` / `ANCHOR_RANGE` | 锚点 id 重复 / `at ∉ [0, 最后相位 end]` |

## 3. PRNG：mulberry32

```
state = (state + 0x6D2B79F5) mod 2³²
t = imul(state ^ (state >>> 15), state | 1)
t = (t + imul(t ^ (t >>> 7), t | 61)) ^ t
out = (t ^ (t >>> 14)) >>> 0          // uint32
nextDouble = out / 2³²                // [0, 1)
```

整个粒子场只用**一条** PRNG 流，消费顺序即契约（§4）。任何地方改用平台随机数 = bug。

## 4. 粒子场 f(seed, grid, jitter, source, target)

**采样**（source 先、target 后，共用一条流）：行主序遍历 `ceil(w/grid) × ceil(h/grid)` 个格子，每格：

1. `jitter > 0` 时**无条件**抽 2 次：`jx = floor(r·(2j+1)) − j`，`jy` 同；`jitter = 0` 不抽；
2. `x = clamp(gx·grid + floor(grid/2) + jx, 0, w−1)`，`y` 同；
3. 读 `argb(x, y)`（**非预乘** ARGB）；alpha = 0 跳过，否则产出样本 `(x, y, c)`。

> 「透明格也抽」保证 source 采样只依赖 source 尺寸，与 target 无关 → `prepare(source)` 可预采样并记下 PRNG 续跑点。

**配对**：`ns = |S|, nt = |T|, n = max(ns, nt)`。对 `perm = [0..nt)` 做 Fisher–Yates（`i` 从 `nt−1` 降到 1，`j = floor(r·(i+1))`）。第 `i` 个粒子：

- `src = S[i mod ns]`（ns=0 时无），`tgt = T[perm[i mod nt]]`（nt=0 时无）
- 起点 = `src ?? tgt` 的位置；起色 = `i < ns ? src.c : (同位置色 & 0x00FFFFFF)`（多出的粒子透明起步 → 淡入）
- 终点 = `tgt ?? src` 的位置；终色 = `i < nt ? tgt.c : 透明`（多出的粒子淡出）
- 依次抽 3 次：`θ = r·2π`，`k = 0.5 + r`，`phase = r·2π`；`dx = cos θ·k`，`dy = sin θ·k`

## 5. 轨迹

```
d(t) = driver.next(t).value                  // prim_burst: keyframe [0,150,600]→[0,1,0], cubicOut（逐段缓动）
m(t) = t ≤ burst.end ? 0 : t ≥ hold.start ? 1 : cubicInOut((t − burst.end)/(hold.start − burst.end))
x = sx + (tx − sx)·m + d·dx·(v0/drag)
y = sy + (ty − sy)·m + d·(dy·(v0/drag) + gravity/drag²)
c = 逐通道 floor(a + (b − a)·m + 0.5)          // ARGB 四通道
jitter hold（t > hold.start 且 mode=jitter）：x += 1.5·sin(w + phase)，y += 1.5·cos(w + phase)，w = 2π(t − hold.start)/1200
```

`v0/drag` 是阻尼运动的终端位移（300/4 = 75px），`gravity/drag²` 是重力下垂量（12.5px）。

## 6. 引擎语义

| 项 | 契约 |
|---|---|
| 时间原点 | 句柄被接纳后的**第一帧** vsync 时间为 t=0 |
| 锚点 | 每帧分发所有满足 `prev < at ≤ t` 的锚点（按 at 升序，同 at 按 IR 顺序）；掉帧时一帧内补发，每个锚点只发一次；回调里 cancel 后停止分发本帧剩余锚点 |
| hold 到期 | 只发 `onHoldExpired`，**不自动收回**；static hold 且锚点发完后引擎不再要帧 |
| teardown | 同步生效：→ completed，释放粒子场与帧缓冲，渲染面立即清空 |
| reverse | 反向起点 = `min(t, hold.start)`；下一帧起 `t = 起点 − 经过时间`；`t ≤ 0` 时 → completed（`endReason = REVERSED`）；reverse 中可 teardown 打断 |
| 渲染指令 | 减少动效 → `Crossfade(m(t))`；active 且 t ≥ hold.start 且 static → `StaticTarget`；否则 `Particles` |
| 预算口径 | 句柄每帧成本 = 粒子数（变换期 / jitter hold / reverse 中）；static hold 与减少动效 = 0 |
| 打满裁决 | 见 golden `admission.json`；grid 阶梯 = grid, 2·grid, 4·grid |
| 单请求超总预算 | `queue` / `dropNewest` / `dropOldest` 下，请求在最低可用档即超总预算 → 直接拒绝 `overTotalBudget`（排队会永久饿死队列）；`dropNewest` 当前占满 → `budgetFull`。拒绝原因随 `PlayResult.Rejected` / `rejectReason` 交给接入方 |
| 取消交接 | **最新来的主动取消前一次，前一次终态回调发出后才启动最新的**。两条路径：① 同渲染位（`slot`，平台层 = View）上新请求取消该位所有未终态前任；② `dropOldest` 挤占。取消方式取新请求的 `preemptMode`（默认 teardown，可选 reverse）。未启动的前任直接撤回（先于在跑的，避免级联放行造成闪现）；teardown 同步终态后立即裁决新请求；reverse 则新请求以 `Queued` 停在 idle，等前任倒放完、终态回调后再裁决 |
| 交接后裁决 | 交接完成后重新裁决；被拒则新句柄 idle → completed（`endReason = REJECTED`，`rejectReason` 给原因）。挤占引发级联放行后必须重新裁决，不按旧计划启动 |
| 静态停留释放 | active 且 t ≥ hold.start 且 static（`StaticTarget`）时粒子场**主动释放**，只保留渲染 target 所需；jitter 停留与减少动效不涉及 |
| reverse 重建 | 从已释放状态发起 reverse（App cancel 或交接 preempt）须重建粒子场并过裁决：按 grid 阶梯取第一档 `占用 + 成本 ≤ 预算` 的（与打满策略无关）；三档都放不下 → 降为 teardown，`endReason = TEARDOWN`（交接路径保持 PREEMPTED / EVICTED）。重建由种子协议保证与原粒子场逐位一致 |
| 预算不变式 | `queue` / `dropNewest` / `dropOldest` 下**任意时刻**占用 ≤ 预算（fuzz 每步断言）；`degrade` 最粗档兜底与 `dropFrame` 按定义可超 |
| 失败 | 平台渲染异常 → `reportError` → failed（可见失败，不静默降级） |

## 7. 句柄状态机

`spec/transitions.json`（5 态 / 5 事件，cancel 按 mode 拆两个 wire 名）。相位与 hold 到期不进状态机。

`withdraw`：`queue` 策略下句柄排队时（idle）调 `cancel(任意 mode)` → 撤出队列，idle → completed，`endReason = WITHDRAWN`，照常回调 `onStateChanged(completed)`。

## 8. 平台层约定

| | Android | iOS |
|---|---|---|
| minSdk / 部署目标 | 24 | iOS 13 |
| FrameClock | Choreographer | CADisplayLink（弱代理） |
| 像素读取 | `Bitmap.getPixel`（非预乘；HARDWARE 位图先拷软件位图） | 绘入 BGRA 预乘缓冲后**反预乘** |
| 粒子绘制 | 同色合批 `drawPoints`（方形笔触 = grid） | 同色合批 `fill(rects)` |
| 减少动效 | `ANIMATOR_DURATION_SCALE == 0` | `UIAccessibility.isReduceMotionEnabled` |

渲染层允许双端差异（「音色不同，曲子一致」）；纯函数层必须 golden 一致。

## 9. 实现补定项与裁决（2026-09-26 用户拍板）

设计文档未定 / 矛盾之处，实现时补定，已拍板：

| # | 项 | 裁决 | vault 回写 |
|---|---|---|---|
| 1 | `holdMs` 语义（IR SSOT §八 示例 `holdMs: 5000` ↔ `hold: [600, 5000]` 矛盾） | **hold 持续时长**：hold = [600, 600 + holdMs] | IR SSOT §八 示例改 [600, 5600] |
| 2 | `onHoldExpired` 锚点 | 模板产出 `onHoldExpired @ hold.end`，是 IR 契约字段 | 写回 IR SSOT §七/§八 |
| 3 | 轨迹公式（§5）、配对规则（§4）、采样抖动离散化 | **权威在本仓库**（本文 + `tools/reference` + golden），vault 只放摘要 + 指针，避免双 SSOT | 不写回 |
| 4 | `queue` 策略下排队中撤回 | 新增事件 `withdraw`：idle → completed，`endReason = WITHDRAWN`（contract-additive，5 态 5 事件） | 写回句柄状态机 |
| 5 | `impact` 模型 | **particleField**；画面震动部分由调用方叠加 `shake` 编排（尺子 5） | 写回原语集 |
| 6 | 预算默认值 `LINEAR_X_FULL = 8000 / ERM_Z = 3000` | 仍是**拍值，待真机标定**（边界 §6.4） | — |
| 7 | 新请求与前一次的关系 | 最新的取消前一次，前一次终态回调后再启动；同渲染位与 `dropOldest` 都走交接；默认 teardown、可配 reverse（2026-09-26） | 写回边界 §五/§六 |
| 8 | 单请求超总预算 | 直接拒绝并给出原因（`overTotalBudget`），不排队（2026-09-26） | 写回边界 §六 |
| 9 | 停在最终画面的粒子资源 / reverse 超预算 | 静态停留即释放粒子场；reverse 重建过裁决，按阶梯降档，放不下降为 teardown（2026-09-26，选项 A） | 写回边界 §6.1 |
