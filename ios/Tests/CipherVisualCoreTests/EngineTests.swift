import XCTest
@testable import CipherVisualCore

/// 手动帧时钟：一次性回调；支持延时回调（到期后的第一帧才投递）。
final class ManualClock: FrameClock {
    private var callbacks: [FrameCallback] = []
    private var delayed: [(cb: FrameCallback, due: UInt64)] = []
    private(set) var nowNanos: UInt64 = 0
    /// 实际投递过的帧回调次数（用于断言静态停留不逐帧空转）
    private(set) var delivered = 0
    var subscribed: Bool { !callbacks.isEmpty || !delayed.isEmpty }
    var immediatePending: Bool { !callbacks.isEmpty }
    var delayedPending: Bool { !delayed.isEmpty }

    func postFrameCallback(_ callback: FrameCallback) { callbacks.append(callback) }

    func postFrameCallback(_ callback: FrameCallback, delayMs: Double) {
        delayed.removeAll { $0.cb === callback }
        delayed.append((callback, nowNanos + UInt64(max(delayMs, 0) * 1_000_000)))
    }

    func removeFrameCallback(_ callback: FrameCallback) {
        callbacks.removeAll { $0 === callback }
        delayed.removeAll { $0.cb === callback }
    }

    func frame(_ ms: Double) {
        nowNanos = UInt64(ms * 1_000_000)
        let due = delayed.filter { $0.due <= nowNanos }.map { $0.cb }
        delayed.removeAll { $0.due <= nowNanos }
        let cbs = callbacks + due
        callbacks.removeAll()
        delivered += cbs.count
        cbs.forEach { $0.doFrame(nowNanos) }
    }

    func run(_ from: Double, _ to: Double) {
        var t = from
        while t <= to {
            frame(t)
            t += 1000.0 / 60
        }
    }
}

final class Recorder: VisualListener {
    var events: [String] = []
    var hook: ((VisualHandle, String) -> Void)?

    func onAnchor(_ handle: VisualHandle, anchorId: String) {
        events.append("anchor:\(anchorId)")
        hook?(handle, anchorId)
    }

    func onStateChanged(_ handle: VisualHandle, state: HandleState) {
        events.append("state:\(state.rawValue)")
    }
}

/// 镜像 Android EngineTest 的关键用例（双端行为等价）。
final class EngineTests: XCTestCase {
    private func block(_ color: UInt32 = 0xFF11_2233) -> PixelSource {
        ArrayPixels(width: 40, height: 12, pixels: Array(repeating: color, count: 480))
    }

    private func request(holdMode: HoldMode = .static, listener: VisualListener? = nil, reduced: Bool = false) -> PlayRequest {
        PlayRequest(ir: IRTemplates.dissolve(holdMs: 1000, holdMode: holdMode), source: block(), target: block(0xFF44_5566),
                    listener: listener, reducedMotion: reduced)
    }

    func testAnchorsFireInOrderAndHoldDoesNotAutoRecall() throws {
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock)
        let l = Recorder()
        let h = try engine.play(request(listener: l)).handle!
        clock.run(0, 2000)
        XCTAssertEqual(l.events.filter { $0.hasPrefix("anchor") }, ["anchor:onBurst", "anchor:onResolve", "anchor:onHoldExpired"])
        XCTAssertEqual(h.state, .active)
        XCTAssertFalse(clock.subscribed)
        XCTAssertEqual(h.currentRender(), .staticTarget)
    }

    func testReverseFromHoldPlaysBackThenCompletes() throws {
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock)
        let h = try engine.play(request()).handle!
        clock.run(0, 1200)
        XCTAssertTrue(h.cancel(.reverse))
        clock.frame(1300)
        XCTAssertEqual(h.timeMs, 600)
        clock.frame(1600)
        XCTAssertEqual(h.timeMs, 300)
        clock.frame(1900)
        XCTAssertEqual(h.state, .completed)
        XCTAssertEqual(h.endReason, .reversed)
    }

    func testTeardownIsImmediate() throws {
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock)
        let h = try engine.play(request()).handle!
        clock.run(0, 200)
        XCTAssertTrue(h.cancel(.teardown))
        XCTAssertEqual(h.state, .completed)
        XCTAssertEqual(h.currentRender(), RenderInstruction.none)
        XCTAssertEqual(engine.particleUsage, 0)
        XCTAssertFalse(h.cancel(.teardown))
    }

    func testAppCanRecallInsideHoldExpiredAnchor() throws {
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock)
        let l = Recorder()
        l.hook = { h, id in if id == Anchors.onHoldExpired { h.cancel(.reverse) } }
        let h = try engine.play(request(listener: l)).handle!
        clock.run(0, 3000)
        XCTAssertEqual(h.endReason, .reversed)
    }

    func testDegradeRaisesGrid() throws {
        let engine = VisualEngine(clock: ManualClock(), config: EngineConfig(overflowStrategy: .degrade, particleBudget: 40))
        let a = try engine.play(request()).handle!
        let b = try engine.play(request()).handle!
        XCTAssertEqual(a.gridPx, 4)
        XCTAssertEqual(b.gridPx, 8)
        XCTAssertEqual(b.particleCount, 10)
    }

    func testQueueStartsWhenBudgetFrees() throws {
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock, config: EngineConfig(overflowStrategy: .queue, particleBudget: 40))
        _ = try engine.play(request())
        guard case let .queued(b) = try engine.play(request()) else { return XCTFail("expected queued") }
        XCTAssertEqual(b.state, .idle)
        clock.run(0, 700)
        XCTAssertEqual(b.state, .active)
    }

    func testQueuedHandleWithdrawCompletes() throws {
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock, config: EngineConfig(overflowStrategy: .queue, particleBudget: 40))
        _ = try engine.play(request())
        guard case let .queued(b) = try engine.play(request()) else { return XCTFail("expected queued") }
        XCTAssertTrue(b.cancel(.teardown))
        XCTAssertEqual(b.state, .completed)
        XCTAssertEqual(b.endReason, .withdrawn)
        clock.run(0, 700)
        XCTAssertEqual(b.state, .completed)
        XCTAssertFalse(b.cancel(.teardown))
    }

    func testWithdrawReleasesParticleDataAndNotifiesRenderer() throws {
        let engine = VisualEngine(clock: ManualClock(), config: EngineConfig(overflowStrategy: .queue, particleBudget: 40))
        _ = try engine.play(request())
        guard case let .queued(b) = try engine.play(request()) else { return XCTFail("expected queued") }
        XCTAssertFalse(b.holdsParticleData, "排队期间不持粒子场（只为判超总预算才建，建完即丢）")
        var notified = false
        b.frameObserver = { notified = b.state.isTerminal }
        b.cancel(.reverse)
        XCTAssertFalse(b.holdsParticleData, "撤回后必须释放粒子场")
        XCTAssertEqual(b.currentRender(), RenderInstruction.none)
        XCTAssertTrue(notified, "撤回后渲染面必须收到终态通知")
    }

    func testReleaseWithdrawsQueuedHandles() throws {
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock, config: EngineConfig(overflowStrategy: .queue, particleBudget: 40))
        let a = try engine.play(request()).handle!
        let l = Recorder()
        guard case let .queued(b) = try engine.play(request(listener: l)) else { return XCTFail("expected queued") }
        engine.release()
        XCTAssertEqual(a.state, .completed)
        XCTAssertEqual(b.state, .completed)
        XCTAssertEqual(b.endReason, .withdrawn)
        XCTAssertFalse(b.isQueued)
        XCTAssertFalse(b.holdsParticleData)
        XCTAssertEqual(l.events, ["state:completed"])
        XCTAssertFalse(clock.subscribed)
    }

    func testDropNewestAndDropOldest() throws {
        let e1 = VisualEngine(clock: ManualClock(), config: EngineConfig(overflowStrategy: .dropNewest, particleBudget: 40))
        _ = try e1.play(request())
        guard case .rejected = try e1.play(request()) else { return XCTFail("expected rejected") }

        let e2 = VisualEngine(clock: ManualClock(), config: EngineConfig(overflowStrategy: .dropOldest, particleBudget: 40))
        let a = try e2.play(request()).handle!
        let b = try e2.play(request()).handle!
        XCTAssertEqual(a.endReason, .evicted)
        XCTAssertEqual(b.state, .active)
    }

    func testReducedMotionCrossfades() throws {
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock)
        let l = Recorder()
        let h = try engine.play(request(listener: l, reduced: true)).handle!
        XCTAssertEqual(engine.particleUsage, 0)
        clock.frame(0)
        XCTAssertEqual(h.currentRender(), .crossfade(targetAlpha: 0))
        clock.run(16, 2000)
        XCTAssertEqual(h.currentRender(), .crossfade(targetAlpha: 1))
        XCTAssertEqual(l.events.filter { $0.hasPrefix("anchor") }.count, 3)
    }

    func testInvalidIrRejected() {
        var bad = IRTemplates.dissolve(holdMs: 1000)
        bad.effect = "fade"
        XCTAssertThrowsError(try VisualEngine(clock: ManualClock()).play(PlayRequest(ir: bad, source: block(), target: block()))) {
            XCTAssertTrue(($0 as! IRInvalid).errors.contains(.EFFECT_UNSUPPORTED))
        }
    }
}
