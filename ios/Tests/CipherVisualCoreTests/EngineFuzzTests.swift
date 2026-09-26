import XCTest
@testable import CipherVisualCore

/// 引擎不变式 fuzz，镜像 Android `EngineFuzzTest`（断言清单见那边的类注释）。
/// 随机源用 Mulberry32，同 seed 两端走同一条操作序列的「形状」（具体数值因平台时序实现可能不同，不做逐步对拍）。
final class EngineFuzzTests: XCTestCase {
    private final class Tracked: VisualListener {
        let handle: VisualHandle
        var lastState: HandleState
        var notifiedAfterTerminal = false
        var violations: [String] = []

        init(_ handle: VisualHandle) {
            self.handle = handle
            lastState = handle.state
        }

        func onStateChanged(_ handle: VisualHandle, state: HandleState) {
            if lastState.isTerminal { violations.append("终态 \(lastState) 之后又回调 \(state)") }
            if !HandleEvent.allCases.contains(where: { HandleFSM.transition(lastState, $0) == state }) {
                violations.append("非法跳转 \(lastState) → \(state)")
            }
            lastState = state
        }
    }

    /// play 时句柄尚未创建，先挂代理，创建后再指向 Tracked。
    private final class Proxy: VisualListener {
        weak var target: Tracked?
        func onStateChanged(_ handle: VisualHandle, state: HandleState) { target?.onStateChanged(handle, state: state) }
    }

    private func block(_ w: Int) -> PixelSource {
        ArrayPixels(width: w, height: 12, pixels: Array(repeating: 0xFF22_3344, count: w * 12))
    }

    func testInvariantsHoldUnderRandomOperations() throws {
        for seed in UInt32(0)..<400 { try runOnce(seed) }
    }

    private func runOnce(_ seed: UInt32) throws {
        var rng = Mulberry32(seed: seed)
        func r(_ n: Int) -> Int { Int(rng.nextDouble() * Double(n)) }
        let strategy = OverflowStrategy.allCases[r(OverflowStrategy.allCases.count)]
        let budget = 40 + r(80)
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock, config: EngineConfig(overflowStrategy: strategy, particleBudget: budget))
        var tracked: [Tracked] = []
        var proxies: [Proxy] = [] // PlayRequest 弱引用 listener，由测试持有
        var now = 0.0
        let ctx = "seed=\(seed) strategy=\(strategy) budget=\(budget)"

        func check(_ step: String) {
            let active = engine.activeHandles
            for t in tracked {
                let h = t.handle
                let at = "\(ctx) step=\(step) handle=\(h.id)"
                XCTAssertTrue(t.violations.isEmpty, "\(at) \(t.violations)")
                XCTAssertEqual(h.state, t.lastState, "\(at) 回调状态与句柄状态不一致")
                if h.state.isTerminal {
                    XCTAssertFalse(active.contains { $0 === h }, "\(at) 终态仍在运行列表")
                    XCTAssertFalse(h.isQueued, "\(at) 终态仍在队列")
                    XCTAssertFalse(h.holdsParticleData, "\(at) 终态仍持粒子场")
                    XCTAssertEqual(h.currentRender(), RenderInstruction.none, "\(at) 终态渲染指令非 none")
                    XCTAssertTrue(t.notifiedAfterTerminal, "\(at) 终态未通知渲染面")
                }
                if h.state == .idle { XCTAssertTrue(h.isQueued, "\(at) idle 但不在队列（僵尸）") }
                if h.isQueued { XCTAssertFalse(active.contains { $0 === h }, "\(at) 排队句柄出现在运行列表") }
            }
            for h in active {
                XCTAssertTrue(h.state == .active || h.state == .cancelling, "\(ctx) step=\(step) 运行列表含 \(h.state)")
            }
        }

        func play() throws {
            let proxy = Proxy()
            proxies.append(proxy)
            let ir = IRTemplates.dissolve(holdMs: Double(100 + r(900)), holdMode: r(3) == 0 ? .jitter : .static)
            let req = PlayRequest(ir: ir, source: block(8 * (1 + r(5))), target: block(8 * (1 + r(5))),
                                  listener: proxy, reducedMotion: r(8) == 0)
            guard let h = try engine.play(req).handle else { return }
            let t = Tracked(h)
            proxy.target = t
            h.frameObserver = { [unowned h, unowned t] in if h.state.isTerminal { t.notifiedAfterTerminal = true } }
            tracked.append(t)
        }

        func pick() -> Tracked? { tracked.isEmpty ? nil : tracked[r(tracked.count)] }

        for i in 0..<80 {
            switch r(10) {
            case 0, 1, 2:
                try play()
            case 3, 4:
                if let t = pick() {
                    let wasTerminal = t.handle.state.isTerminal
                    let ok = t.handle.cancel(r(2) == 0 ? .teardown : .reverse)
                    if wasTerminal { XCTAssertFalse(ok, "\(ctx) 终态句柄 cancel 应返回 false") }
                }
            case 5:
                pick()?.handle.reportError(NSError(domain: "fuzz", code: 0))
            default:
                now += Double(r(250))
                clock.frame(now)
            }
            check("#\(i)")
        }

        var rounds = 0
        while tracked.contains(where: { $0.handle.isQueued }) {
            rounds += 1
            guard rounds < 100 else { return XCTFail("\(ctx) 队列无法排空（活性破坏）") }
            engine.activeHandles.forEach { _ = $0.cancel(.teardown) }
            now += 16
            clock.frame(now)
            check("drain#\(rounds)")
        }

        for _ in 0..<6 { try play() }
        engine.release()
        check("release")
        for t in tracked { XCTAssertTrue(t.handle.state.isTerminal, "\(ctx) release 后句柄 \(t.handle.id) 非终态：\(t.handle.state)") }
        XCTAssertFalse(clock.subscribed, "\(ctx) release 后仍订阅帧时钟")
        _ = proxies
    }
}
