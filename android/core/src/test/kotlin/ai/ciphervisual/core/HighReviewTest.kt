package ai.ciphervisual.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * code-review high（HEAD e1f640d）发现项的回归用例（core 层）。Android 框架层的见 :cipher-visual 的 mockk 测试。
 */
class HighReviewTest {
    private fun block(w: Int = 40) = IntArrayPixels(w, 12, IntArray(w * 12) { 0xFF112233.toInt() })

    private fun req(
        holdMs: Double = 5000.0,
        listener: VisualListener? = null,
        slot: Any? = null,
        mode: CancelMode = CancelMode.TEARDOWN,
        src: PixelSource = block(),
    ) = PlayRequest(IrTemplates.dissolve(holdMs), src, block(), listener, slot = slot, preemptMode = mode)

    private class Anchors : VisualListener {
        val fired = ArrayList<String>()
        override fun onAnchor(handle: VisualHandle, anchorId: String) {
            fired += "$anchorId@${handle.timeMs.toInt()}"
        }
    }

    // ---------------------------------------------------------------- #9 静态停留不逐帧空转（选 a：FrameClock 延时回调）

    @Test
    fun staticHoldWaitsForNextAnchorWithSingleDelayedWake() {
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val anchors = Anchors()
        val h = (engine.play(req(listener = anchors)) as PlayResult.Started).handle
        clock.run(0.0, 700.0)
        assertEquals(RenderInstruction.StaticTarget, h.currentRender())
        assertFalse(clock.immediatePending, "画面静止后不应再要下一帧")
        assertTrue(clock.delayedPending, "只挂一个等 onHoldExpired 的延时回调")
        val before = clock.delivered
        clock.run(716.0, 6000.0) // 60Hz 推帧约 318 次
        assertEquals(1, clock.delivered - before, "整个 hold 期间只被唤醒一次（到期那帧）")
        assertEquals(listOf("onBurst", "onResolve", "onHoldExpired"), anchors.fired.map { it.substringBefore('@') }, "到期照常发 onHoldExpired")
        assertTrue(h.timeMs >= 5600.0)
        assertFalse(clock.subscribed, "锚点发完后退订")
    }

    @Test
    fun clockWithoutDelayedSupportStillWorksViaDefaultImplementation() {
        // 只实现 post / remove 的旧时钟（如尚未升级的 CipherHaptic 适配）：退化为逐帧唤醒，行为正确
        class LegacyClock : FrameClock {
            val callbacks = ArrayList<FrameCallback>()
            override fun postFrameCallback(callback: FrameCallback) {
                callbacks += callback
            }
            override fun removeFrameCallback(callback: FrameCallback) {
                callbacks -= callback
            }
            fun frame(ms: Double) {
                val cbs = callbacks.toList()
                callbacks.clear()
                cbs.forEach { it.doFrame((ms * 1_000_000).toLong()) }
            }
        }
        val clock = LegacyClock()
        val engine = VisualEngine(clock)
        val anchors = Anchors()
        engine.play(req(holdMs = 1000.0, listener = anchors))
        var t = 0.0
        while (t <= 2000.0) {
            clock.frame(t)
            t += 1000.0 / 60
        }
        assertEquals(3, anchors.fired.size)
        assertTrue(clock.callbacks.isEmpty())
    }

    @Test
    fun stateChangeOutsideFrameDuringDelayedWaitKeepsAnchorTiming() {
        // A、B 都在静态停留等锚点；帧外 teardown B 不应推迟 A 的 onHoldExpired
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val anchors = Anchors()
        engine.play(req(holdMs = 2000.0, listener = anchors))
        val b = (engine.play(req(holdMs = 4000.0)) as PlayResult.Started).handle
        clock.run(0.0, 700.0)
        clock.frame(1500.0)
        b.cancel(CancelMode.TEARDOWN)
        clock.run(1516.0, 3000.0)
        val expired = anchors.fired.first { it.startsWith("onHoldExpired") }
        val at = expired.substringAfter('@').toInt()
        assertTrue(at in 2600..2620, "onHoldExpired 应在 2600ms 附近触发，实际 $at")
    }

    // ---------------------------------------------------------------- #1 / #5 RenderSlot：逐帧不重排、测量用正在渲染的

    @Test
    fun renderSlotRelayoutsOnlyWhenRenderedContentChanges() {
        var layouts = 0
        var redraws = 0
        val slot = RenderSlot<String>(onRelease = {}, onLayoutChanged = { layouts++ }, onRedraw = { redraws++ })
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val a = (engine.play(req(slot = "v")) as PlayResult.Started).handle
        slot.attach(a, "A")
        assertEquals(1, layouts)
        clock.run(0.0, 400.0)
        assertEquals(1, layouts, "动画逐帧只重绘，不重排")
        assertTrue(redraws > 10)
        val b = (engine.play(req(slot = "v", mode = CancelMode.REVERSE)) as PlayResult.Queued).handle
        slot.attach(b, "B")
        assertEquals(1, layouts, "新句柄进等交接位，正在渲染的没变，不重排")
        assertEquals("A", slot.layoutPayload, "交接期间按正在倒放的前一次测量")
        clock.run(416.0, 1200.0)
        assertEquals(2, layouts, "交接顶上那一刻重排一次")
        assertEquals("B", slot.layoutPayload)
    }

    // ---------------------------------------------------------------- #3 监听器在 active 回调里抛异常：不留幽灵句柄

    private class Boom : RuntimeException("listener boom")

    @Test
    fun listenerThrowingOnActiveLeavesNoGhostHandle() {
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val anchors = ArrayList<String>()
        val thrower = object : VisualListener {
            override fun onStateChanged(handle: VisualHandle, state: HandleState) {
                if (state == HandleState.ACTIVE) throw Boom()
            }
            override fun onAnchor(handle: VisualHandle, anchorId: String) {
                anchors += anchorId
            }
        }
        assertFailsWith<Boom> { engine.play(req(listener = thrower, slot = "v")) }
        assertTrue(engine.activeHandles.isEmpty(), "抛出的请求不应留在运行列表")
        assertEquals(0, engine.particleUsage)
        clock.run(0.0, 1000.0)
        assertTrue(anchors.isEmpty(), "幽灵句柄不应继续发锚点")
        assertFalse(clock.subscribed)
        // 渲染位也已清理：下一个请求不会去「取消」它
        val log = ArrayList<String>()
        val next = engine.play(req(slot = "v", listener = object : VisualListener {
            override fun onStateChanged(handle: VisualHandle, state: HandleState) {
                log += "h${handle.id}:${state.wire}"
            }
        }))
        assertIs<PlayResult.Started>(next)
        assertEquals(listOf("h${next.handle.id}:active"), log)
    }

    // ---------------------------------------------------------------- #10 release 中监听器抛异常不卡死引擎

    @Test
    fun releaseWithThrowingListenerDoesNotWedgeHandoffs() {
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val thrower = object : VisualListener {
            override fun onStateChanged(handle: VisualHandle, state: HandleState) {
                if (state == HandleState.COMPLETED) throw Boom()
            }
        }
        engine.play(req(listener = thrower))
        assertFailsWith<Boom> { engine.release() }
        // 引擎仍可用：reverse 交接照常放行
        val a = (engine.play(req(slot = "v")) as PlayResult.Started).handle
        clock.run(0.0, 300.0)
        val b = (engine.play(req(slot = "v", mode = CancelMode.REVERSE)) as PlayResult.Queued).handle
        clock.run(316.0, 1200.0)
        assertEquals(HandleState.COMPLETED, a.state)
        assertEquals(HandleState.ACTIVE, b.state, "release 抛异常后交接放行不能失效")
    }

    // ---------------------------------------------------------------- #7 不把库自身的异常当成内容不可读

    @Test
    fun libraryBugInFieldConstructionIsNotMaskedAsContentUnavailable() {
        val engine = VisualEngine(ManualClock())
        val buggy = object : PixelSource {
            override val width = 40
            override val height = 12
            override fun argb(x: Int, y: Int): Int = throw IllegalArgumentException("library bug")
        }
        val e = assertFailsWith<IllegalArgumentException> { engine.play(req(src = buggy, slot = "v")) }
        assertEquals("library bug", e.message)
        assertTrue(engine.activeHandles.isEmpty())
    }

    // ---------------------------------------------------------------- #8 未启动的句柄不持粒子场

    @Test
    fun handleWaitingForEvictedReverseHoldsNoParticleData() {
        val clock = ManualClock()
        val engine = VisualEngine(clock, EngineConfig(overflowStrategy = OverflowStrategy.DROP_OLDEST, particleBudget = 40))
        engine.play(req())
        clock.run(0.0, 300.0)
        val b = (engine.play(req(mode = CancelMode.REVERSE)) as PlayResult.Queued).handle
        assertTrue(b.isAwaitingHandoff)
        assertFalse(b.holdsParticleData, "等被挤者倒放期间不持粒子场")
        clock.run(316.0, 1200.0)
        assertEquals(HandleState.ACTIVE, b.state)
    }
}
