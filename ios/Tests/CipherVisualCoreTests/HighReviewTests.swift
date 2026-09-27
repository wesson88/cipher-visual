import XCTest
@testable import CipherVisualCore

/// code-review high 发现项的回归用例（iOS core），镜像 Android `HighReviewTest`。
/// 监听器抛异常两项（幽灵句柄 / release 卡死）iOS 不适用：VisualListener 方法不 throws。
final class HighReviewTests: XCTestCase {
    private final class Anchors: VisualListener {
        var fired: [String] = []
        func onAnchor(_ handle: VisualHandle, anchorId: String) { fired.append("\(anchorId)@\(Int(handle.timeMs))") }
    }

    /// 可声明「已释放」的像素源（iOS 口径：释放后读到 0，靠 isAvailable 让引擎拒绝）
    private final class Releasable: PixelSource {
        let width = 40
        let height = 12
        var released = false
        var isAvailable: Bool { !released }
        func argb(x: Int, y: Int) -> UInt32 { released ? 0 : 0xFF11_2233 }
    }

    private func block() -> PixelSource { ArrayPixels(width: 40, height: 12, pixels: Array(repeating: 0xFF11_2233, count: 480)) }

    private func req(holdMs: Double = 5000, listener: VisualListener? = nil, slot: AnyHashable? = nil,
                     mode: CancelMode = .teardown, src: PixelSource? = nil, tgt: PixelSource? = nil) -> PlayRequest {
        PlayRequest(ir: IRTemplates.dissolve(holdMs: holdMs), source: src ?? block(), target: tgt ?? block(),
                    listener: listener, slot: slot, preemptMode: mode)
    }

    // #9：静态停留只用一次延时唤醒
    func testStaticHoldWaitsForNextAnchorWithSingleDelayedWake() throws {
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock)
        let anchors = Anchors()
        let h = try engine.play(req(listener: anchors)).handle!
        clock.run(0, 700)
        XCTAssertEqual(h.currentRender(), .staticTarget)
        XCTAssertFalse(clock.immediatePending)
        XCTAssertTrue(clock.delayedPending)
        let before = clock.delivered
        clock.run(716, 6000)
        XCTAssertEqual(clock.delivered - before, 1, "整个 hold 期间只被唤醒一次")
        XCTAssertEqual(anchors.fired.map { $0.components(separatedBy: "@")[0] }, ["onBurst", "onResolve", "onHoldExpired"])
        XCTAssertFalse(clock.subscribed)
    }

    func testClockWithoutDelayedSupportStillWorksViaDefault() throws {
        final class LegacyClock: FrameClock {
            var callbacks: [FrameCallback] = []
            func postFrameCallback(_ callback: FrameCallback) { callbacks.append(callback) }
            func removeFrameCallback(_ callback: FrameCallback) { callbacks.removeAll { $0 === callback } }
            func frame(_ ms: Double) {
                let cbs = callbacks
                callbacks.removeAll()
                cbs.forEach { $0.doFrame(UInt64(ms * 1_000_000)) }
            }
        }
        let clock = LegacyClock()
        let engine = VisualEngine(clock: clock)
        let anchors = Anchors()
        _ = try engine.play(req(holdMs: 1000, listener: anchors))
        var t = 0.0
        while t <= 2000 {
            clock.frame(t)
            t += 1000.0 / 60
        }
        XCTAssertEqual(anchors.fired.count, 3)
        XCTAssertTrue(clock.callbacks.isEmpty)
    }

    func testStateChangeOutsideFrameKeepsAnchorTiming() throws {
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock)
        let anchors = Anchors()
        _ = try engine.play(req(holdMs: 2000, listener: anchors))
        let b = try engine.play(req(holdMs: 4000)).handle!
        clock.run(0, 700)
        clock.frame(1500)
        b.cancel(.teardown)
        clock.run(1516, 3000)
        let expired = try XCTUnwrap(anchors.fired.first { $0.hasPrefix("onHoldExpired") })
        let at = Int(expired.components(separatedBy: "@")[1])!
        XCTAssertTrue((2600...2620).contains(at), "onHoldExpired 应在 2600ms 附近，实际 \(at)")
    }

    // #1 / #5：RenderSlot 逐帧不重排、测量用正在渲染的
    func testRenderSlotRelayoutsOnlyWhenRenderedContentChanges() throws {
        var layouts = 0
        var redraws = 0
        let slot = RenderSlot<String>(onRelease: { _ in }, onLayoutChanged: { layouts += 1 }, onRedraw: { redraws += 1 })
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock)
        slot.attach(try engine.play(req(slot: "v")).handle!, "A")
        XCTAssertEqual(layouts, 1)
        clock.run(0, 400)
        XCTAssertEqual(layouts, 1, "动画逐帧只重绘，不重排")
        XCTAssertGreaterThan(redraws, 10)
        slot.attach(try engine.play(req(slot: "v", mode: .reverse)).handle!, "B")
        XCTAssertEqual(layouts, 1)
        XCTAssertEqual(slot.layoutPayload, "A", "交接期间按正在倒放的前一次测量")
        clock.run(416, 1200)
        XCTAssertEqual(layouts, 2)
        XCTAssertEqual(slot.layoutPayload, "B")
    }

    // #2：iOS 与 Android 同口径——内容已释放 → 拒绝，而不是建空粒子场「隐形播放」
    func testReleasedContentOnHandoffIsRejectedNotPlayedInvisibly() throws {
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock)
        let anchors = Anchors()
        let a = try engine.play(req(slot: "v")).handle!
        clock.run(0, 300)
        let src = Releasable()
        let tgt = Releasable()
        let b = try engine.play(req(listener: anchors, slot: "v", mode: .reverse, src: src, tgt: tgt)).handle!
        src.released = true // view.detach() 释放了等交接句柄的像素
        tgt.released = true
        clock.run(316, 1200)
        XCTAssertEqual(a.state, .completed)
        XCTAssertEqual(b.endReason, .rejected)
        XCTAssertEqual(b.rejectReason, .contentUnavailable)
        XCTAssertTrue(anchors.fired.isEmpty, "不应隐形播放并发锚点")
    }

    func testReverseFromStaticHoldWithReleasedContentFallsBackToTeardown() throws {
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock)
        let src = Releasable()
        let h = try engine.play(req(src: src)).handle!
        clock.run(0, 800)
        src.released = true
        XCTAssertTrue(h.cancel(.reverse))
        XCTAssertEqual(h.state, .completed)
        XCTAssertEqual(h.endReason, .teardown, "与 Android 一致：不能重建就降为 teardown")
    }

    // #8：未启动的句柄不持粒子场
    func testHandleWaitingForEvictedReverseHoldsNoParticleData() throws {
        let clock = ManualClock()
        let engine = VisualEngine(clock: clock, config: EngineConfig(overflowStrategy: .dropOldest, particleBudget: 40))
        _ = try engine.play(req())
        clock.run(0, 300)
        guard case let .queued(b) = try engine.play(req(mode: .reverse)) else { return XCTFail("expected queued") }
        XCTAssertTrue(b.isAwaitingHandoff)
        XCTAssertFalse(b.holdsParticleData)
        clock.run(316, 1200)
        XCTAssertEqual(b.state, .active)
    }
}
