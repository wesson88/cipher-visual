import XCTest
@testable import CipherVisualCore

/// 引擎不变式 fuzz，镜像 Android `EngineFuzzTest`（断言清单见那边的类注释）。随机源用 Mulberry32，失败信息带 seed 与操作轨迹。
/// 可回收像素源通过 `isAvailable` 声明不可读（与 Android 同口径），引擎据此拒绝；监听器抛异常一项 iOS 不适用（协议方法不 throws）。
final class EngineFuzzTests: XCTestCase {
    private final class Tracked {
        let handle: VisualHandle
        let slot: String?
        let seq: Int
        var lastState: HandleState
        var notifiedAfterTerminal = false
        var releaseCount = 0
        var violations: [String] = []

        init(_ handle: VisualHandle, _ slot: String?, _ seq: Int) {
            self.handle = handle
            self.slot = slot
            self.seq = seq
            lastState = handle.state
        }
    }

    /// 可回收像素源：回收后读到 0（iOS 口径）
    private final class RecyclablePixels: PixelSource {
        let width: Int
        let height = 12
        var recycled = false
        var isAvailable: Bool { !recycled }
        init(_ w: Int) { width = w }
        func argb(x: Int, y: Int) -> UInt32 { recycled ? 0 : 0xFF22_3344 }
    }

    private final class Recorder: VisualListener {
        var byId: [Int64: Tracked] = [:]
        var cancelOnActive: Set<Int64> = []
        var anchorsFired = 0

        func onAnchor(_ handle: VisualHandle, anchorId: String) { anchorsFired += 1 }
        var trace: [String] = []
        var onActivated: ((Tracked) -> Void)?

        func log(_ s: String) {
            trace.append(s)
            if trace.count > 40 { trace.removeFirst() }
        }

        func onStateChanged(_ handle: VisualHandle, state: HandleState) {
            log("  cb h\(handle.id) → \(state.rawValue) \(handle.endReason.map { "\($0)" } ?? "")")
            if let t = byId[handle.id] {
                if t.lastState.isTerminal { t.violations.append("终态 \(t.lastState) 之后又回调 \(state)") }
                if !HandleEvent.allCases.contains(where: { HandleFSM.transition(t.lastState, $0) == state }) {
                    t.violations.append("非法跳转 \(t.lastState) → \(state)")
                }
                if state == .active { onActivated?(t) }
                t.lastState = state
            }
            if state == .active && cancelOnActive.contains(handle.id) {
                log("  (回调内立即取消 h\(handle.id))")
                handle.cancel(.teardown)
            }
        }
    }

    private struct Payload {
        let id: Int64
        let src: RecyclablePixels
        let tgt: RecyclablePixels
    }

    func testInvariantsHoldUnderRandomOperations() throws {
        for seed in UInt32(0)..<500 { try runOnce(seed) }
    }

    private func runOnce(_ seed: UInt32) throws {
        var rng = Mulberry32(seed: seed)
        func r(_ n: Int) -> Int { Int(rng.nextDouble() * Double(n)) }
        let strategy = OverflowStrategy.allCases[r(OverflowStrategy.allCases.count)]
        let budget = 20 + r(100)
        let bounded = [OverflowStrategy.queue, .dropNewest, .dropOldest].contains(strategy)
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock, config: EngineConfig(overflowStrategy: strategy, particleBudget: budget))
        let slotNames = ["slotA", "slotB", "slotC"]
        let rec = Recorder()
        var tracked: [Tracked] = []
        var now = 0.0
        var seq = 0
        let ctx = "seed=\(seed) strategy=\(strategy) budget=\(budget)"

        var renderSlots: [String: RenderSlot<Payload>] = [:]
        for name in slotNames {
            renderSlots[name] = RenderSlot<Payload>(
                onRelease: { p in
                    p.src.recycled = true
                    p.tgt.recycled = true
                    rec.byId[p.id]?.releaseCount += 1
                },
                onLayoutChanged: {}
            )
        }

        rec.onActivated = { t in
            if bounded && engine.particleUsage > budget {
                t.violations.append("放行时占用 \(engine.particleUsage) > 预算 \(budget)")
            }
            if let slot = t.slot {
                for o in tracked where o.slot == slot && o.seq < t.seq && !o.handle.state.isTerminal {
                    t.violations.append("同位更早的句柄 \(o.handle.id) 尚未终态（\(o.handle.state)）就放行了")
                }
            }
            if strategy == .queue {
                let mine = t.handle.queueSeq
                for o in tracked where o !== t && o.handle.isQueued && (mine < 0 || o.handle.queueSeq < mine) {
                    t.violations.append("插队：h\(o.handle.id)（入队 #\(o.handle.queueSeq)）仍在排队，h\(t.handle.id)（入队 #\(mine)）却先放行")
                }
            }
        }

        func check(_ step: String) -> Bool {
            let active = engine.activeHandles
            var ok = true
            func expect(_ cond: Bool, _ msg: String) {
                if !cond {
                    ok = false
                    XCTFail("\(ctx) step=\(step) \(msg)\n  轨迹：\n    " + rec.trace.joined(separator: "\n    "))
                }
            }
            for t in tracked {
                let h = t.handle
                let at = "handle=\(h.id)"
                expect(t.violations.isEmpty, "\(at) \(t.violations)")
                expect(h.state == t.lastState, "\(at) 回调状态与句柄状态不一致")
                if h.state.isTerminal {
                    expect(!active.contains { $0 === h }, "\(at) 终态仍在运行列表")
                    expect(!h.isQueued, "\(at) 终态仍在队列")
                    expect(!h.isAwaitingHandoff, "\(at) 终态仍在等交接")
                    expect(!h.holdsParticleData, "\(at) 终态仍持粒子场")
                    expect(h.currentRender() == RenderInstruction.none, "\(at) 终态渲染指令非 none")
                    if t.slot != nil {
                        expect(t.releaseCount == 1, "\(at) 渲染位载荷释放次数 \(t.releaseCount)（应恰好 1 次）")
                    } else {
                        expect(t.notifiedAfterTerminal, "\(at) 终态未通知渲染面")
                    }
                }
                if h.state == .active && h.currentRender() == .staticTarget {
                    expect(!h.holdsParticleData, "\(at) 停在最终画面仍持粒子场")
                }
                if h.state == .idle {
                    expect(!h.holdsParticleData, "\(at) 未启动却持有粒子场")
                    expect(h.isQueued || h.isAwaitingHandoff, "\(at) idle 但既不排队也不等交接（僵尸）")
                    expect(!active.contains { $0 === h }, "\(at) 未启动句柄出现在运行列表")
                }
            }
            for h in active { expect(h.state == .active || h.state == .cancelling, "运行列表含 \(h.state)") }
            if bounded { expect(engine.particleUsage <= budget, "占用 \(engine.particleUsage) > 预算 \(budget)") }
            for slot in slotNames {
                let n = tracked.filter { $0.slot == slot && $0.handle.state == .active }.count
                expect(n <= 1, "\(slot) 上同时有 \(n) 个 active")
            }
            return ok
        }

        func play() throws {
            let slot: String? = r(4) == 0 ? nil : slotNames[r(slotNames.count)]
            var ir = IRTemplates.dissolve(holdMs: Double(100 + r(900)), holdMode: r(3) == 0 ? .jitter : .static)
            let malformed = r(20) == 0
            if malformed { ir.phases[1].range = [150] }
            let src = RecyclablePixels(8 * (1 + r(5)))
            let tgt = RecyclablePixels(8 * (1 + r(5)))
            let cancelFast = r(10) == 0
            let req = PlayRequest(ir: ir, source: src, target: tgt, listener: rec,
                                  reducedMotion: r(8) == 0, slot: slot.map { AnyHashable($0) },
                                  preemptMode: r(2) == 0 ? .teardown : .reverse)
            rec.log("play slot=\(slot ?? "nil") mode=\(req.preemptMode) malformed=\(malformed) cancelOnActive=\(cancelFast)")
            let res: PlayResult
            do {
                res = try engine.play(req)
            } catch let e as IRInvalid {
                XCTAssertTrue(malformed, "\(ctx) 合法 IR 被拒：\(e.errors)")
                return
            }
            XCTAssertFalse(malformed, "\(ctx) 畸形 IR 未被拒")
            guard let h = res.handle else { return }
            rec.log("  = h\(h.id) \(h.state.rawValue) queued=\(h.isQueued) awaiting=\(h.isAwaitingHandoff)")
            let t = Tracked(h, slot, seq)
            seq += 1
            tracked.append(t)
            rec.byId[h.id] = t
            if h.state == .active { rec.onActivated?(t) }
            if cancelFast {
                if h.state == .active {
                    rec.log("  (active 回调内取消 h\(h.id))")
                    h.cancel(.teardown)
                } else {
                    rec.cancelOnActive.insert(h.id)
                }
            }
            if let slot = slot {
                renderSlots[slot]!.attach(h, Payload(id: h.id, src: src, tgt: tgt))
            } else {
                h.frameObserver = { [unowned h, unowned t] in if h.state.isTerminal { t.notifiedAfterTerminal = true } }
                if h.state.isTerminal { t.notifiedAfterTerminal = true }
            }
        }

        func pick() -> Tracked? { tracked.isEmpty ? nil : tracked[r(tracked.count)] }

        for i in 0..<80 {
            switch r(12) {
            case 0, 1, 2, 3:
                try play()
            case 4, 5:
                if let t = pick() {
                    let wasTerminal = t.handle.state.isTerminal
                    let mode: CancelMode = r(2) == 0 ? .teardown : .reverse
                    rec.log("cancel h\(t.handle.id) \(mode) (was \(t.handle.state.rawValue))")
                    let ok = t.handle.cancel(mode)
                    if wasTerminal { XCTAssertFalse(ok, "\(ctx) 终态句柄 cancel 应返回 false") }
                }
            case 6:
                if let h = pick()?.handle {
                    rec.log("error h\(h.id) (was \(h.state.rawValue))")
                    h.reportError(NSError(domain: "fuzz", code: 0))
                }
            case 7:
                let slot = slotNames[r(slotNames.count)]
                rec.log("detach \(slot)")
                renderSlots[slot]!.detach()
            default:
                now += Double(r(250))
                rec.log("frame \(now)")
                clock.frame(now)
            }
            guard check("#\(i)") else { return }
        }

        var rounds = 0
        while tracked.contains(where: { $0.handle.state == .idle }) {
            rounds += 1
            guard rounds < 100 else { return XCTFail("\(ctx) 句柄卡在 idle 无法推进（活性破坏）") }
            engine.activeHandles.forEach { _ = $0.cancel(.teardown) }
            now += 16
            clock.frame(now)
            guard check("drain#\(rounds)") else { return }
        }

        // 静默期：先推一帧让订阅按准确时刻落定；若无句柄在动，接下来 3 秒的唤醒只应来自锚点到期
        now += 16
        clock.frame(now)
        if !engine.activeHandles.contains(where: { $0.isAnimating() }) {
            let deliveredBefore = clock.delivered
            let anchorsBefore = rec.anchorsFired
            var q = now
            while q <= now + 3000 {
                q += 1000.0 / 60
                clock.frame(q)
            }
            now = q
            let wakes = clock.delivered - deliveredBefore
            let fired = rec.anchorsFired - anchorsBefore
            XCTAssertLessThanOrEqual(wakes, fired + 1, "\(ctx) 静默期空转：唤醒 \(wakes) 次，只触发了 \(fired) 个锚点")
            guard check("quiet") else { return }
        }

        for _ in 0..<8 { try play() }
        engine.release()
        guard check("release") else { return }
        for t in tracked { XCTAssertTrue(t.handle.state.isTerminal, "\(ctx) release 后句柄 \(t.handle.id) 非终态：\(t.handle.state)") }
        XCTAssertFalse(clock.subscribed, "\(ctx) release 后仍订阅帧时钟")
    }
}
