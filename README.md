# CipherVisual

视觉内容的**时间轴变换引擎**（Android + iOS）。输入 `source / target / timeline`，输出「粒子渲染 + 锚点帧回调 + 句柄」。

它不知道内容是什么意思、为什么变、变完怎样，也从不调用任何其他库。触觉、音效、埋点都由宿主 App 在锚点回调里自己编排。CipherHaptic 是它的「触觉孪生」，两者唯一共享的是 `FrameClock` 契约。

## 现状（v0.1 · MVP）

| 能力 | 状态 |
|---|---|
| effect `dissolve`（particleField + keyframe） | ✅ 双端 |
| 其余 11 个原语（burst / fall / … / orbit） | 词典已登记，IR 校验报 `EFFECT_UNSUPPORTED`（V2） |
| Timeline IR（JSON）解析 + 校验 + 模板 | ✅ |
| mulberry32 种子协议 · 粒子场 · 轨迹 · 相位 | ✅ golden 对拍 |
| 句柄 FSM（5 态 5 事件）· teardown / reverse | ✅ |
| 粒子预算 + 5 种打满策略 · 减少动效降级 · `prepare` 预热 | ✅ |
| Android：Choreographer + Canvas + demo App | ✅ 本地构建 |
| iOS：CADisplayLink + CoreGraphics | ✅ 已写完，由 CI（macOS）编译和测试 |
| 真机 30 帧标定 · 预算标定 · 双端帧对齐实测 | ⏳ P0 验证 |

## 仓库结构

```
spec/                 契约：contracts.md（算法口径）· effects.json · transitions.json · dissolve.ir.json
contracts/golden/     golden 向量（生成产物，禁止手改）
tools/reference/      JS 参考实现 cv-ref.mjs + golden 生成器
tools/contract-check.mjs   CI 契约检查（golden 漂移 / spec 一致 / 三方 token / 措辞红线）
android/              Gradle：:core（纯 JVM）· :cipher-visual（Android 库）· :demo
ios/ + Package.swift  SwiftPM：CipherVisualCore（纯 Swift）· CipherVisual（UIKit）
```

## 双端一致性怎么保证

两端都对拍同一份参考实现生成的 golden，**两端之间不直接对拍**：

```
tools/reference/cv-ref.mjs ──gen──▶ contracts/golden/*.json ◀── Android GoldenTest
                                                             ◀── iOS GoldenTests
```

纯函数层（PRNG / 缓动 / 原语 / 相位 / 粒子场 / 轨迹 / FSM / 预算裁决 / IR 校验）必须和 golden 一致，容差 1e-9。渲染层允许两端有差异。改算法时按这个顺序走：先改 `cv-ref.mjs`，再重新生成 golden，然后两端跟进，最后回修 `spec/contracts.md`。

## 用法

### Android

```kotlin
val visual = CipherVisual(context, EngineConfig(hardwareTier = HardwareTier.LINEAR_X_FULL))
val result = visual.play(
    view = cipherVisualView,
    effect = Effect.DISSOLVE,
    source = VisualContent.Text("Hello", TextStyle(textPx, Color.GRAY)),
    target = VisualContent.Text("你好", TextStyle(textPx, Color.BLUE)),
    holdMs = 5000,
    listener = object : VisualListener {
        override fun onAnchor(handle: VisualHandle, anchorId: String) {
            when (anchorId) {
                Anchors.ON_BURST -> haptics.playOnNextFrame(...)          // App 编排
                Anchors.ON_HOLD_EXPIRED -> handle.cancel(CancelMode.REVERSE) // 库不自动收回
            }
        }
    },
)
// 切后台 / 锁屏等安全事件：App 自行判断后调 handle.cancel(CancelMode.TEARDOWN)
```

### iOS

```swift
let visual = CipherVisual(config: EngineConfig(hardwareTier: .linearXFull))
let result = try visual.play(in: view, effect: .dissolve,
                             source: .text("Hello", TextStyle(font: font, color: .gray)),
                             target: .text("你好", TextStyle(font: font, color: .blue)),
                             holdMs: 5000, listener: self)   // listener 为弱引用
```

## 构建与测试

```bash
node tools/reference/gen-golden.mjs      # 改了参考实现后重新生成 golden
node tools/contract-check.mjs            # 契约检查
cd android && ./gradlew :core:test :cipher-visual:assembleRelease :demo:assembleDebug
swift test                               # macOS
```

## 边界（节选）

判断「这个该不该进库」用五条尺子：应用无关 / 内容无关 / 能脱离业务单独验证 / 谁是决策者 / 原语还是编排。

- **库给能力，App 定策略**。比如 `hold.mode = jitter` 这个开关在库里，但为什么要开由 App 决定；打满时有哪些策略可选由库提供，选哪个归 App，具体执行又回到库。
- **库不监听**切后台、锁屏、滚动，也不替 App 计时，更不会自动收回。
- **库不解析业务富文本**。App 自己渲染成位图后再交给库。

完整设计见 vault `20-知识/项目记录/CipherLex/动效引擎CipherVisual-*` 系列（系列索引为入口）。
