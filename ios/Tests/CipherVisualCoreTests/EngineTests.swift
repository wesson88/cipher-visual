import XCTest
@testable import CipherVisualCore

final class ManualClock: FrameClock {
    private var callbacks: [FrameCallback] = []
    var subscribed: Bool { !callbacks.isEmpty }

    func postFrameCallback(_ callback: FrameCallback) { callbacks.append(callback) }
    func removeFrameCallback(_ callback: FrameCallback) { callbacks.removeAll { $0 === callback } }

    func frame(_ ms: Double) {
        let cbs = callbacks
        callbacks.removeAll()
        cbs.forEach { $0.doFrame(UInt64(ms * 1_000_000)) }
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
