import XCTest
@testable import CipherVisualCore

/// 停在最终画面即释放粒子场；reverse 时按需重建并过预算裁决，放不下降为 teardown。镜像 Android `ReverseRebuildTest`。
final class ReverseRebuildTests: XCTestCase {
    private final class States: VisualListener {
        var events: [String] = []
        func onStateChanged(_ handle: VisualHandle, state: HandleState) { events.append(state.rawValue) }
    }

    private func block(_ w: Int, _ color: UInt32 = 0xFF11_2233) -> PixelSource {
        ArrayPixels(width: w, height: 12, pixels: Array(repeating: color, count: w * 12))
    }

    private func req(_ holdMode: HoldMode = .static, listener: VisualListener? = nil, w: Int = 40,
                     slot: AnyHashable? = nil, mode: CancelMode = .teardown) -> PlayRequest {
        PlayRequest(ir: IRTemplates.dissolve(holdMs: 5000, holdMode: holdMode), source: block(w), target: block(w, 0xFF44_5566),
                    listener: listener, slot: slot, preemptMode: mode)
    }

    func testStaticHoldReleasesParticlesButKeepsShowingTarget() throws {
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock)
        let h = try engine.play(req()).handle!
        clock.run(0, 400)
        XCTAssertTrue(h.holdsParticleData)
        clock.run(416, 800)
        XCTAssertEqual(h.state, .active)
        XCTAssertEqual(h.currentRender(), .staticTarget)
        XCTAssertFalse(h.holdsParticleData)
        XCTAssertEqual(h.particleCount, 0)
    }

    func testJitterHoldKeepsParticles() throws {
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock)
        let h = try engine.play(req(.jitter)).handle!
        clock.run(0, 800)
        XCTAssertTrue(h.holdsParticleData)
    }

    func testReverseFromStaticHoldRebuildsIdenticalField() throws {
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock)
        let a = try engine.play(req()).handle!
        let b = try engine.play(req()).handle!
        clock.frame(0)
        clock.frame(300)
        guard case let .particles(fb) = b.currentRender() else { return XCTFail("expected particles") }
        let forward = Array(fb.xs.prefix(fb.count))
        b.cancel(.teardown)
        clock.run(316, 900)
        XCTAssertFalse(a.holdsParticleData)
        XCTAssertTrue(a.cancel(.reverse))
        XCTAssertEqual(a.state, .cancelling)
        XCTAssertEqual(a.gridPx, 4)
        clock.frame(1000)
        clock.frame(1300)
        XCTAssertEqual(a.timeMs, 300)
        guard case let .particles(fa) = a.currentRender() else { return XCTFail("expected particles") }
        XCTAssertEqual(forward, Array(fa.xs.prefix(fa.count)), "重建后倒放画面与原正放一致")
    }

    func testReverseRebuildDegradesGridWhenBudgetIsTight() throws {
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock, config: EngineConfig(overflowStrategy: .queue, particleBudget: 40))
        let a = try engine.play(req()).handle!
        clock.run(0, 700)
        let b = try engine.play(req()).handle!
        clock.frame(720)
        XCTAssertEqual(engine.particleUsage, 30)
        XCTAssertTrue(a.cancel(.reverse))
        XCTAssertEqual(a.state, .cancelling)
        XCTAssertEqual(a.gridPx, 8)
        XCTAssertEqual(engine.particleUsage, 40)
        XCTAssertEqual(b.state, .active)
    }

    func testReverseFallsBackToTeardownWhenCoarsestGridDoesNotFit() throws {
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock, config: EngineConfig(overflowStrategy: .dropNewest, particleBudget: 30))
        let states = States()
        let a = try engine.play(req(listener: states)).handle!
        clock.run(0, 700)
        _ = try engine.play(req())
        clock.frame(720)
        XCTAssertTrue(a.cancel(.reverse))
        XCTAssertEqual(a.state, .completed)
        XCTAssertEqual(a.endReason, .teardown)
        XCTAssertEqual(states.events, ["active", "completed"])
        XCTAssertFalse(a.holdsParticleData)
        XCTAssertLessThanOrEqual(engine.particleUsage, 30)
    }

    func testPreemptWithReverseFromStaticHoldAlsoPassesBudget() throws {
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock, config: EngineConfig(overflowStrategy: .queue, particleBudget: 30))
        let a = try engine.play(req(slot: "v")).handle!
        clock.run(0, 700)
        _ = try engine.play(req())
        clock.frame(720)
        let c = try engine.play(req(w: 8, slot: "v", mode: .reverse))
        XCTAssertEqual(a.endReason, .preempted)
        XCTAssertEqual(a.state, .completed)
        XCTAssertLessThanOrEqual(engine.particleUsage, 30)
        guard case .queued = c else { return XCTFail("expected queued") }
    }
}
