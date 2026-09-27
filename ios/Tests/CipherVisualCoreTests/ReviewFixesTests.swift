import XCTest
@testable import CipherVisualCore

/// 全仓 code-review 发现项的回归用例，镜像 Android `ReviewFixesTest`（#1 读像素抛错为 Android 特有，iOS 像素源不抛错）。
final class ReviewFixesTests: XCTestCase {
    private final class Log: VisualListener {
        var events: [String] = []
        func onStateChanged(_ handle: VisualHandle, state: HandleState) {
            events.append("h\(handle.id):\(state.rawValue)")
        }
    }

    private func block(_ w: Int) -> PixelSource {
        ArrayPixels(width: w, height: 12, pixels: Array(repeating: 0xFF11_2233, count: w * 12))
    }

    private func req(_ w: Int = 40, listener: VisualListener? = nil, slot: AnyHashable? = nil, mode: CancelMode = .teardown) -> PlayRequest {
        PlayRequest(ir: IRTemplates.dissolve(holdMs: 1000), source: block(w), target: block(w), listener: listener,
                    slot: slot, preemptMode: mode)
    }

    // #2
    func testQueueIsStrictFifo() throws {
        let log = Log()
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock, config: EngineConfig(overflowStrategy: .queue, particleBudget: 40))
        _ = try engine.play(req(listener: log))
        guard case let .queued(big) = try engine.play(req(listener: log)) else { return XCTFail("expected queued") }
        guard case let .queued(small) = try engine.play(req(8, listener: log)) else { return XCTFail("严格 FIFO：放得下也不插队") }
        clock.run(0, 800)
        XCTAssertEqual(big.state, .active)
        XCTAssertEqual(small.state, .active)
        XCTAssertEqual(log.events.filter { $0.hasSuffix(":active") }, ["h1:active", "h2:active", "h3:active"])
    }

    func testHandoffWaiterUnderQueueGoesToTheBack() throws {
        let log = Log()
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock, config: EngineConfig(overflowStrategy: .queue, particleBudget: 40))
        let a = try engine.play(req(listener: log, slot: "v")).handle!
        clock.run(0, 300)
        let q = try engine.play(req(listener: log)).handle!
        let w = try engine.play(req(listener: log, slot: "v", mode: .reverse)).handle!
        clock.run(320, 1000)
        XCTAssertEqual(a.state, .completed)
        XCTAssertEqual(q.state, .active)
        XCTAssertTrue(w.isQueued, "交接完成后前面有人排队 → 排到队尾")
        clock.run(1016, 2200)
        XCTAssertEqual(log.events.filter { $0.hasSuffix(":active") }, ["h1:active", "h2:active", "h3:active"])
    }

    // #3
    func testMalformedPhaseRangeIsValidationError() {
        let base = IRTemplates.dissolve(holdMs: 1000)
        for bad in [[150.0], [], [150.0, 350.0, 400.0]] {
            var ir = base
            ir.phases[1].range = bad
            XCTAssertEqual(IRValidator.validate(ir), [.PHASE_RANGE], "range=\(bad)")
            XCTAssertThrowsError(try VisualEngine(clock: ManualClock()).play(PlayRequest(ir: ir, source: block(40), target: block(40)))) {
                XCTAssertTrue($0 is IRInvalid)
            }
        }
    }

    // #5
    func testCancelInsideActiveCallbackReleasesRenderSlotPayloadImmediately() throws {
        final class Canceller: VisualListener {
            func onStateChanged(_ handle: VisualHandle, state: HandleState) {
                if state == .active { handle.cancel(.teardown) }
            }
        }
        let canceller = Canceller()
        var released: [String] = []
        let slot = RenderSlot<String>(onRelease: { released.append($0) }, onLayoutChanged: {})
        let engine = VisualEngine(clock: ManualClock())
        guard case let .started(h) = try engine.play(req(listener: canceller, slot: "v")) else { return XCTFail("expected started") }
        XCTAssertTrue(h.state.isTerminal)
        slot.attach(h, "payload")
        XCTAssertEqual(released, ["payload"], "错过了终态通知也要在挂载时立即释放")
        XCTAssertNil(slot.currentHandle)
    }
}

final class RenderSlotTests: XCTestCase {
    private func block() -> PixelSource { ArrayPixels(width: 40, height: 12, pixels: Array(repeating: 0xFF11_2233, count: 480)) }
    private func req(_ mode: CancelMode = .teardown) -> PlayRequest {
        PlayRequest(ir: IRTemplates.dissolve(holdMs: 1000), source: block(), target: block(), slot: "v", preemptMode: mode)
    }

    func testReverseHandoffKeepsRenderingPreviousUntilItEnds() throws {
        var released: [String] = []
        let slot = RenderSlot<String>(onRelease: { released.append($0) }, onLayoutChanged: {})
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock)
        let a = try engine.play(req()).handle!
        slot.attach(a, "A")
        clock.run(0, 300)
        let b = try engine.play(req(.reverse)).handle!
        slot.attach(b, "B")
        XCTAssertTrue(slot.currentHandle === a)
        XCTAssertTrue(slot.latestHandle === b)
        clock.run(320, 1000)
        XCTAssertEqual(released, ["A"])
        XCTAssertTrue(slot.currentHandle === b)
    }

    func testReplacedPendingIsReleasedExactlyOnce() throws {
        var released: [String] = []
        let slot = RenderSlot<String>(onRelease: { released.append($0) }, onLayoutChanged: {})
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock)
        slot.attach(try engine.play(req()).handle!, "A")
        clock.run(0, 300)
        slot.attach(try engine.play(req(.reverse)).handle!, "B")
        slot.attach(try engine.play(req(.reverse)).handle!, "C")
        XCTAssertEqual(released, ["B"])
        clock.run(320, 1000)
        XCTAssertEqual(released, ["B", "A"])
        slot.detach()
        XCTAssertEqual(released, ["B", "A", "C"])
    }
}
