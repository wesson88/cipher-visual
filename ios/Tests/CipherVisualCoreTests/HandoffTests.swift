import XCTest
@testable import CipherVisualCore

/// 取消交接，镜像 Android `HandoffTest`：「最新来的主动取消前一次，前一次取消拿到回调后再发起最新的」。
final class HandoffTests: XCTestCase {
    private let slot: AnyHashable = "view-1"

    private final class Log: VisualListener {
        var events: [String] = []
        func onStateChanged(_ handle: VisualHandle, state: HandleState) {
            events.append("h\(handle.id):\(state.rawValue)" + (handle.endReason.map { "/\($0)" } ?? ""))
        }
    }

    private func block(_ w: Int) -> PixelSource {
        ArrayPixels(width: w, height: 12, pixels: Array(repeating: 0xFF11_2233, count: w * 12))
    }

    private func req(_ log: Log, _ mode: CancelMode = .teardown, slot: AnyHashable?? = .none, w: Int = 40) -> PlayRequest {
        PlayRequest(ir: IRTemplates.dissolve(holdMs: 1000), source: block(w), target: block(w), listener: log,
                    slot: slot ?? self.slot, preemptMode: mode)
    }

    func testTeardownPreemptStartsNewAfterPreviousCallback() throws {
        let log = Log()
        let engine = VisualEngine(clock: ManualClock())
        let a = try engine.play(req(log)).handle!
        guard case let .started(b) = try engine.play(req(log)) else { return XCTFail("expected started") }
        XCTAssertEqual(a.endReason, .preempted)
        XCTAssertEqual(log.events, ["h1:active", "h1:completed/preempted", "h2:active"])
        XCTAssertEqual(b.state, .active)
    }

    func testReversePreemptWaitsUntilPreviousReverseCompletes() throws {
        let log = Log()
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock)
        let a = try engine.play(req(log)).handle!
        clock.run(0, 800)
        guard case let .queued(b) = try engine.play(req(log, .reverse)) else { return XCTFail("expected queued") }
        XCTAssertEqual(a.state, .cancelling)
        XCTAssertTrue(b.isAwaitingHandoff)
        clock.run(820, 1600)
        XCTAssertEqual(a.endReason, .preempted)
        XCTAssertEqual(b.state, .active)
        let i = log.events.firstIndex(of: "h1:completed/preempted")
        XCTAssertNotNil(i)
        XCTAssertEqual(log.events.firstIndex(of: "h2:active"), i.map { $0 + 1 })
    }

    func testNewerRequestReplacesPendingOneAndStillWaits() throws {
        let log = Log()
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock)
        let a = try engine.play(req(log)).handle!
        clock.run(0, 300)
        let b = try engine.play(req(log, .reverse)).handle!
        guard case let .queued(c) = try engine.play(req(log, .reverse)) else { return XCTFail("expected queued") }
        XCTAssertEqual(b.endReason, .preempted)
        XCTAssertEqual(a.state, .cancelling)
        clock.run(320, 1000)
        XCTAssertEqual(c.state, .active)
        XCTAssertFalse(log.events.contains("h2:active"), "被取代的等待者不应闪现启动")
    }

    func testWithdrawingPendingOneDoesNotForgetReversingOne() throws {
        let log = Log()
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock)
        let a = try engine.play(req(log)).handle!
        clock.run(0, 300)
        let b = try engine.play(req(log, .reverse)).handle!
        b.cancel(.teardown)
        XCTAssertEqual(b.endReason, .withdrawn)
        guard case let .queued(c) = try engine.play(req(log, .reverse)) else { return XCTFail("expected queued") }
        XCTAssertEqual(a.state, .cancelling)
        XCTAssertTrue(c.isAwaitingHandoff)
        clock.run(320, 1000)
        XCTAssertEqual(c.state, .active)
    }

    func testDropOldestWithReverseWaitsForEvicted() throws {
        let log = Log()
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock, config: EngineConfig(overflowStrategy: .dropOldest, particleBudget: 40))
        let a = try engine.play(req(log, slot: .some(nil))).handle!
        clock.run(0, 300)
        guard case let .queued(b) = try engine.play(req(log, .reverse, slot: .some(nil))) else { return XCTFail("expected queued") }
        XCTAssertEqual(a.state, .cancelling)
        XCTAssertLessThanOrEqual(engine.particleUsage, 40)
        clock.run(320, 1000)
        XCTAssertEqual(a.endReason, .evicted)
        XCTAssertEqual(b.state, .active)
    }

    func testOverTotalBudgetRejectedWithReason() throws {
        let engine = VisualEngine(clock: ManualClock(), config: EngineConfig(overflowStrategy: .queue, particleBudget: 20))
        guard case let .rejected(reason) = try engine.play(req(Log(), slot: .some(nil))) else { return XCTFail("expected rejected") }
        XCTAssertEqual(reason, .overTotalBudget)
    }

    func testRejectionAfterHandoffEndsWaitingHandle() throws {
        let log = Log()
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock, config: EngineConfig(overflowStrategy: .dropNewest, particleBudget: 20))
        let a = try engine.play(req(log, w: 8)).handle!
        clock.run(0, 300)
        let b = try engine.play(req(log, .reverse, w: 40)).handle!
        clock.run(320, 1000)
        XCTAssertEqual(a.state, .completed)
        XCTAssertEqual(b.endReason, .rejected)
        XCTAssertEqual(b.rejectReason, .overTotalBudget)
    }
}
